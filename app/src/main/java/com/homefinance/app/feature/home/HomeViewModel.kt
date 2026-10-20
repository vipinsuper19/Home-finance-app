package com.homefinance.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.homefinance.app.HomeFinanceApp
import com.homefinance.app.core.common.PlaceholderData
import com.homefinance.app.data.local.relation.OutstandingOccurrence
import com.homefinance.app.data.repository.RecurringRepository
import com.homefinance.app.feature.recurring.OccurrenceDisplayState
import com.homefinance.app.feature.recurring.isSettled
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs the Home screen: the monthly figures, which are still sample data, and the recurring section,
 * which is the household's own.
 *
 * ### Why the section is read here rather than drawn from the list's state
 *
 * The recurring tab's view model answers a different question - every schedule, with its next date -
 * and its state belongs to that screen's back stack entry, so there is nothing to borrow. This one
 * reads the two facts the section needs in one pass: the outstanding dates themselves, and whether the
 * household has any schedule at all. Both are ordinary `Flow`s off Room, so paying a bill on another
 * screen, adding a schedule, or a date simply arriving all re-emit the section with no cooperation
 * from this class.
 *
 * ### Why opening Home runs the engine
 *
 * [com.homefinance.app.data.repository.RecurrenceGenerator] runs here for the same reason it runs when
 * the recurring tab is opened, and Home is now that reason's first stop: it is the start destination,
 * so it is the screen a household actually opens, and it is the one drawing due dates. Without the run,
 * a schedule whose last generation was more than
 * [com.homefinance.app.data.repository.RecurrenceGenerator.PLANNING_HORIZON_DAYS] days ago would have
 * no row for a date that has since arrived, and Home would show a quiet, wrong "nothing due today".
 *
 * That is safe, and it is safe for reasons the engine owns rather than this class: a generated
 * occurrence is an **expectation**, never a transaction, so no total moves and nothing is spent; the
 * run is idempotent, so a second one writes nothing at all; and the unique index on
 * `(schedule_id, due_date)` is what decides what already exists, so two screens racing to run it
 * cannot produce two rows for one date. In particular, **nothing here writes a ledger entry** - a bill
 * falling due is not a payment, and only a deliberate tap on "Mark as paid" records one.
 *
 * A failed run is *not* a failed read: the rows that already exist are still true and still drawn, so
 * the failure is reported beside them ([HomeRecurringUiState.Ready.generationFailed]) rather than
 * replacing them, and the retry re-runs the engine as well as re-reading.
 *
 * ### Failing, and failing to *act*
 *
 * The read is mapped through a small internal [RecurringRead] exactly as the ledger and the recurring
 * tab do it: `onStart` publishes `Loading`, `catch` turns a failure into `Failed` and ends the flow so
 * a late success cannot reorder the screen, and [retry] re-subscribes from scratch. A failure here
 * degrades the *section* to a sentence with a retry, and the rest of the dashboard keeps drawing - see
 * [HomeUiState].
 *
 * @param today the day the section considers to be today, used both to read each due date as overdue,
 *   due or upcoming and to decide how far ahead to generate. It is a constructor parameter so a test
 *   can pin it; production supplies the device's real day.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val recurring: RecurringRepository,
    private val today: LocalDate = LocalDate.now(),
    /**
     * Whether the balance amount may be drawn.
     *
     * It arrives as a `Flow` rather than a repository so the screen depends only on the one boolean
     * it needs, and a test can pin it without a database. It defaults to `true`, which is the
     * behaviour before Settings existed, so the existing factory and tests keep their meaning. A
     * failure reading the preference falls back to `true` rather than hiding the household's own
     * balance because a setting row could not be read.
     */
    private val showBalance: Flow<Boolean> = flowOf(true),
) : ViewModel() {

    private val retryRequests = MutableStateFlow(0)

    /** True after a run of the recurrence engine failed, until one succeeds. */
    private val generationFailed = MutableStateFlow(false)

    val uiState: StateFlow<HomeUiState> =
        combine(
            retryRequests.flatMapLatest { observeRecurring() },
            generationFailed,
            // A failed settings read shows the balance rather than hiding it: the household asked for
            // a dashboard, and a broken preference row must not look like a private-mode switch.
            showBalance.catch { emit(true) },
        ) { read, behind, balanceVisible -> read.toUiState(behind, balanceVisible) }
            .stateIn(
                scope = viewModelScope,
                // Keeping the subscription alive briefly means coming back to Home - from a bill's
                // details, or from another tab - does not throw the section away and show a spinner.
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
                initialValue = RecurringRead.Loading.toUiState(
                    generationFailed = false,
                    showBalance = true,
                ),
            )

    init {
        bringDueDatesUpToDate()
    }

    /** Starts over: re-reads the section, and retries the engine run a failed read suggests failed too. */
    fun retry() {
        retryRequests.update { it + 1 }
        bringDueDatesUpToDate()
    }

    /**
     * The generation run, and the one place its failure becomes visible.
     *
     * It does not stop the section drawing: what is on screen is true, it may simply be missing dates
     * the run would have created. It is reported because a "Nothing due today" that is only quiet
     * because the rows were never written is exactly the kind of lie this app does not tell.
     */
    private fun bringDueDatesUpToDate() {
        viewModelScope.launch {
            try {
                recurring.generateDueOccurrences(today)
                generationFailed.value = false
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                generationFailed.value = true
            }
        }
    }

    /**
     * The two reads the section is built from, as this screen sees them.
     *
     * `catch` ends the flow on failure, so a broken read becomes a sentence with a retry rather than a
     * spinner that never stops; [retry] is what restarts it.
     */
    private fun observeRecurring(): Flow<RecurringRead> {
        val rows: Flow<RecurringRead> = combine(
            recurring.observeScheduleCount(),
            recurring.observeOutstandingOccurrences(),
        ) { scheduleCount, occurrences ->
            RecurringRead.Rows(scheduleCount = scheduleCount, occurrences = occurrences)
        }

        return rows
            .onStart { emit(RecurringRead.Loading) }
            .catch { failure -> emit(RecurringRead.Failed(failure)) }
    }

    private fun RecurringRead.toUiState(
        generationFailed: Boolean,
        showBalance: Boolean,
    ): HomeUiState = HomeUiState(
        balanceInPaise = PlaceholderData.balanceInPaise,
        incomeInPaise = PlaceholderData.incomeInPaise,
        expenseInPaise = PlaceholderData.expenseInPaise,
        isPlaceholderData = true,
        showBalance = showBalance,
        recurring = when (this) {
            RecurringRead.Loading -> HomeRecurringUiState.Loading
            is RecurringRead.Failed -> HomeRecurringUiState.Failed(cause)
            is RecurringRead.Rows -> toReady(generationFailed)
        },
    )

    /**
     * Splits the outstanding dates into the three groups the section draws.
     *
     * The order inside each group is the query's own - soonest due date first - so nothing is sorted
     * here: re-sorting would be a second opinion about an order the database has already given, and it
     * is the database that can see the whole table.
     *
     * A settled row is dropped rather than drawn: the query already excludes paid and skipped
     * occurrences, and if one ever arrived anyway the honest reading of it is "this is finished with",
     * which is not one of the three groups. Nothing is announced as completed on the strength of a
     * missing transaction either - an occurrence is shown as waiting until the status the database
     * holds says otherwise.
     */
    private fun RecurringRead.Rows.toReady(generationFailed: Boolean): HomeRecurringUiState.Ready {
        val items = occurrences
            .map { it.toHomeItem(today) }
            .filterNot { it.state.isSettled() }
        val upcoming = items.filter { it.state == OccurrenceDisplayState.UPCOMING }

        return HomeRecurringUiState.Ready(
            overdue = items.filter { it.state == OccurrenceDisplayState.OVERDUE },
            dueToday = items.filter { it.state == OccurrenceDisplayState.DUE },
            upcoming = upcoming.take(UPCOMING_PREVIEW_LIMIT),
            // Counted before the preview trims the list, so the heading says how many are coming in
            // all rather than how many happen to be drawn.
            upcomingTotal = upcoming.size,
            hasAnySchedule = scheduleCount > 0,
            generationFailed = generationFailed,
        )
    }

    /** The section's read, in the three shapes the screen needs. */
    private sealed interface RecurringRead {

        data object Loading : RecurringRead

        data class Rows(
            val scheduleCount: Int,
            val occurrences: List<OutstandingOccurrence>,
        ) : RecurringRead

        data class Failed(val cause: Throwable) : RecurringRead
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
                ) { "HomeViewModel needs the Application to reach the repository" }

                val app = application as HomeFinanceApp
                HomeViewModel(
                    recurring = app.recurringRepository,
                    showBalance = app.settingsRepository.preferences
                        .map { it.showBalanceOnHome }
                        .distinctUntilChanged(),
                )            }
        }

        /**
         * Builds the view model over [repository] with [today] pinned.
         *
         * The seam a device test uses so the real screen can be driven without opening the household's
         * database, and the one a JVM test uses to pin the day.
         */
        fun factory(
            repository: RecurringRepository,
            today: LocalDate,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(repository, today) }
        }
    }
}
