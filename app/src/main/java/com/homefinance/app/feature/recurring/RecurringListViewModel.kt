package com.homefinance.app.feature.recurring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.homefinance.app.HomeFinanceApp
import com.homefinance.app.data.local.relation.RecurringScheduleWithProgress
import com.homefinance.app.data.repository.RecurringRepository
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs the Recurring tab: the household's schedules, the filter that decides which of them are on
 * screen, and the three things they can do to one from here - pause it, resume it, or stop it for good.
 *
 * ### The filter is a view, not a query
 *
 * [onFilterChange] narrows the drawn rows by their category's own type and writes nothing. The rows
 * come from one query either way, so switching between "All", "Expense" and "Income" cannot miss a
 * schedule or show one twice, and cannot put the screen back through its loading state.
 *
 * ### The generation trigger lives here
 *
 * Opening this screen is what brings every active schedule's occurrences up to date
 * ([RecurringRepository.generateDueOccurrences]). That is the whole scheduler: no `WorkManager`, no
 * alarms, no notifications, and no background service, because the app is offline and its data is only
 * ever looked at through these screens.
 *
 * Running it here is safe precisely because generating an occurrence is not spending: it can create
 * expectations, never a transaction, and never a total moves because of it. It is also idempotent, so
 * the running it every single time the tab is opened costs one bounded write and produces nothing at
 * all on the second visit of the day.
 *
 * ### Where the data comes from
 *
 * The rows are whatever [RecurringRepository.observeSchedules] emits, joined to their category names in
 * one query. Nothing polls: Room pushes a new list whenever a schedule or an occurrence changes, so
 * marking an occurrence paid on the details screen updates this list, its next due date and its overdue
 * marker with no cooperation from this class. That is also why the pay and skip flows report no result
 * through navigation - there is nothing to pass back.
 *
 * ### Failing, retrying, and failing to *act*
 *
 * The read is mapped through a small internal [SchedulesRead] exactly as the ledger does: `onStart`
 * publishes `Loading`, `catch` turns a failure into `Failed` and ends the flow so a late success cannot
 * reorder the screen, and [retry] re-subscribes from scratch. A failed read degrades to an error view
 * rather than a crash, and the failure is carried in the state rather than logged and dropped - there
 * is no crash reporting for it to go to.
 *
 * A failed *action* is kept apart from a failed *read* (see [ActionError]): the list underneath is
 * still perfectly truthful, so throwing it away because one tap went wrong would lose the household
 * their own data in order to report a message. The strip is dismissed by the next successful action.
 *
 * @param today the day the screen considers to be today, used to read each next-due date as upcoming,
 *   due or overdue. It is a constructor parameter so a test can pin it; production supplies the
 *   device's real day.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecurringListViewModel(
    private val repository: RecurringRepository,
    private val today: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val retryRequests = MutableStateFlow(0)

    private val actionError = MutableStateFlow<ActionError?>(null)

    /**
     * Which direction the household is looking at.
     *
     * Held here rather than in the composable so that a configuration change does not silently widen
     * the list back to "All" - a household reading their income schedules should still be reading
     * them after a rotation. It is *not* persisted: the tab opens on "All" every time, because the
     * first thing a household wants is the whole picture.
     */
    private val filter = MutableStateFlow(ScheduleTypeFilter.ALL)

    val uiState: StateFlow<RecurringListUiState> =
        combine(
            retryRequests.flatMapLatest { observeSchedules() },
            actionError,
            filter,
        ) { read, failure, filter -> read.toUiState(failure, filter) }
            .stateIn(
                scope = viewModelScope,
                // Keeping the subscription alive briefly means switching tabs and coming straight back
                // does not throw the list away and show a spinner again.
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
                initialValue = RecurringListUiState.Loading,
            )

    init {
        generateDueOccurrences()
    }

    /**
     * Narrows the list to one direction, or widens it back to all of them.
     *
     * It changes nothing in the database and writes nothing: the rows are the same rows, filtered
     * where they are drawn. That is why the same flow can carry it beside the query - a change of
     * filter does not have to re-read, and must not, because a re-read would be a chance for the list
     * to flicker back through its loading state on what is only a change of view.
     */
    fun onFilterChange(filter: ScheduleTypeFilter) {
        this.filter.value = filter
    }

    /** Starts over: re-reads the schedules, and tries the generation the failed read suggests failed too. */
    fun retry() {
        retryRequests.update { it + 1 }
        generateDueOccurrences()
    }

    /** Dismisses the action-failure strip without doing anything about it. */
    fun dismissActionError() {
        actionError.value = null
    }

    /**
     * Turns a schedule's "keeping this up" switch on or off: a resume when it is turned on, a pause when
     * it is turned off.
     *
     * Deliberately takes the *wanted* state rather than a verb. The switch is the control, so the two
     * actions are two sides of one choice, and a caller cannot express "pause something already paused"
     * by accident.
     *
     * A pause or resume that changed nothing - because another screen got there first - is not reported
     * as a failure: the switch reads its position from the database, so it simply stays where the
     * database says it is.
     */
    fun setActive(scheduleId: Long, active: Boolean) {
        viewModelScope.launch {
            try {
                if (active) {
                    repository.resume(scheduleId, today)
                } else {
                    repository.pause(scheduleId)
                }
                actionError.value = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                actionError.value = ActionError.Failed(failure)
            }
        }
    }

    /**
     * The generation run, and the one place a *bookkeeping* failure becomes visible.
     *
     * It does not stop the screen drawing: the schedules and the occurrences that already exist are
     * still true, and reading them is what the household came for. It is reported because silently
     * showing a schedule whose next due date is a month out of date would be a lie of omission.
     */
    private fun generateDueOccurrences() {
        viewModelScope.launch {
            try {
                repository.generateDueOccurrences(today)
                if (actionError.value is ActionError.GenerationFailed) actionError.value = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                actionError.value = ActionError.GenerationFailed(failure)
            }
        }
    }

    /**
     * The schedule query as this screen reads it, with its loading and failed states folded in.
     *
     * `catch` ends the flow on failure, so a broken read becomes a message with a retry rather than a
     * spinner that never stops; [retry] is what restarts it.
     */
    private fun observeSchedules(): Flow<SchedulesRead> =
        repository.observeSchedules()
            .map<List<RecurringScheduleWithProgress>, SchedulesRead> { SchedulesRead.Rows(it) }
            .onStart { emit(SchedulesRead.Loading) }
            .catch { failure -> emit(SchedulesRead.Failed(failure)) }

    private fun SchedulesRead.toUiState(
        failure: ActionError?,
        filter: ScheduleTypeFilter,
    ): RecurringListUiState = when (this) {
        SchedulesRead.Loading -> RecurringListUiState.Loading
        is SchedulesRead.Failed -> RecurringListUiState.Failed(cause)
        is SchedulesRead.Rows -> {
            val cards = rows.map { it.toCard(today) }

            RecurringListUiState.Ready(
                schedules = cards.filter { filter.matches(it.categoryType) },
                filter = filter,
                // Read from the *unfiltered* cards: whether the household has any schedules at all is
                // a different question from whether the filter they chose happened to match one, and
                // the two empty screens say different things.
                hasAnySchedule = cards.isNotEmpty(),
                actionError = failure,
            )
        }
    }

    /** The schedule read, in the three shapes the screen needs. */
    private sealed interface SchedulesRead {

        data object Loading : SchedulesRead

        data class Rows(val rows: List<RecurringScheduleWithProgress>) : SchedulesRead

        data class Failed(val cause: Throwable) : SchedulesRead
    }

    companion object {

        private const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L

        /**
         * Builds the view model against the application's own repository.
         *
         * The same one wiring seam as the rest of the app: the application class owns the single
         * database, and there is deliberately no dependency-injection framework.
         */
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY],
                ) { "RecurringListViewModel needs the Application to reach the repository" }

                RecurringListViewModel((application as HomeFinanceApp).recurringRepository)
            }
        }

        /**
         * Builds the view model over [repository] with [today] pinned.
         *
         * The seam a device test uses so that the real screens can be driven without opening the
         * household's database, and the one a JVM test uses to pin the day.
         */
        fun factory(
            repository: RecurringRepository,
            today: LocalDate,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { RecurringListViewModel(repository, today) }
        }
    }
}
