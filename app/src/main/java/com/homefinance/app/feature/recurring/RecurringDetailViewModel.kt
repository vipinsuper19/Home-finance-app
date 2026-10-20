package com.homefinance.app.feature.recurring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.homefinance.app.HomeFinanceApp
import com.homefinance.app.data.repository.RecurringRepository
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs one schedule's details screen: its own figures, the one occurrence that is waiting, and the three
 * things the household can do to the schedule itself - change it, stop it generating for a while, or stop
 * it for good.
 *
 * ### Where the data comes from
 *
 * Whatever [observeScheduleAndOccurrences] emits: the schedule joined to its category's name, and every
 * occurrence of it - waiting dates soonest first, then settled history newest first. Nothing here polls
 * and nothing here re-queries after a write. Recording
 * a payment, skipping an occurrence, pausing or stopping all write to the database, and these queries are
 * what push the result back. That is why nothing in this class has to refresh anything, and why the
 * settle flows report no result through navigation.
 *
 * ### What it does *not* do
 *
 * It does not generate occurrences. The Recurring tab does that when it is opened, and this screen is only
 * reachable from a row on it, so a second trigger here would be a second way to write the same rows for no
 * benefit. It also does not mark anything paid because a date passed: the outstanding occurrence is
 * *offered*, and the household decides.
 *
 * ### Failing
 *
 * The read is folded into [ScheduleAndOccurrencesRead], which keeps "the schedule has gone" apart from "the
 * read failed". A failed *action* is kept apart from both (see [ActionError]): the figures on screen are
 * still true, so throwing them away because one tap went wrong would lose the household their own data in
 * order to report a message.
 *
 * @param scheduleId the schedule to show, carried by the route rather than held anywhere else.
 * @param today the day the screen considers to be today, used to read each occurrence as upcoming, due,
 *   overdue, paid or skipped. It is a constructor parameter so a test can pin it; production supplies the
 *   device's real day.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecurringDetailViewModel(
    private val repository: RecurringRepository,
    private val scheduleId: Long,
    private val today: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val retryRequests = MutableStateFlow(0)

    private val actionError = MutableStateFlow<ActionError?>(null)

    /**
     * The two settle flows, shared verbatim with the occurrence history.
     *
     * They live outside this class so both screens cannot disagree about what "already paid" means, and so
     * the whole of validation, the guard against a second tap and the translation of the repository's
     * outcome is testable without a screen. This class only launches them, because a view model is what
     * owns a coroutine scope.
     */
    private val occurrenceActions = OccurrenceActionsController(repository)

    /** The open payment sheet, or `null` when it is closed. */
    val pay: StateFlow<PayOccurrenceState?> = occurrenceActions.pay

    /** The open "skip this occurrence" dialog, or `null` when it is closed. */
    val skip: StateFlow<SkipOccurrenceState?> = occurrenceActions.skip

    val uiState: StateFlow<RecurringDetailUiState> =
        combine(
            retryRequests.flatMapLatest { repository.observeScheduleAndOccurrences(scheduleId) },
            actionError,
        ) { read, failure -> read.toUiState(failure) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
                initialValue = RecurringDetailUiState.Loading,
            )

    /** Starts over after a failed read. */
    fun retry() {
        retryRequests.update { it + 1 }
    }

    /**
     * Stops this schedule generating anything new, keeping it and everything already generated.
     *
     * Occurrences already on the screen stay usable - the household may still want to pay or skip one -
     * because a pause is not a deletion and not an edit.
     */
    fun pause() {
        runAction { repository.pause(scheduleId) }
    }

    /**
     * Starts the schedule generating again from today.
     *
     * Nothing is back-filled for the period it was paused: pausing means "stop expecting this", so the
     * dates that fell inside the pause are not brought back as a sudden pile of overdue bills.
     */
    fun resume() {
        runAction { repository.resume(scheduleId, today) }
    }

    /**
     * Stops the schedule for good.
     *
     * One-way by design - there is no "un-retire" - so the screen asks first. The schedule and its whole
     * history stay exactly where they are; this only ends the rule. Nothing already recorded as paid is
     * touched, and no total moves.
     */
    fun retire() {
        runAction { repository.retire(scheduleId) }
    }

    /** Opens the payment sheet for [occurrence]. Nothing is written until the household confirms. */
    fun requestPay(occurrence: RecurringOccurrenceItem) {
        occurrenceActions.requestPay(occurrence.toSettleTarget())
    }

    fun onPayAmountChange(value: String) {
        occurrenceActions.onPayAmountChange(value)
    }

    /** Records the payment. The sheet closes on success; a refusal is shown inside it. */
    fun confirmPay() {
        viewModelScope.launch { occurrenceActions.confirmPay() }
    }

    fun dismissPay() {
        occurrenceActions.dismissPay()
    }

    /** Opens the skip confirmation for [occurrence]. Nothing is written until the household confirms. */
    fun requestSkip(occurrence: RecurringOccurrenceItem) {
        occurrenceActions.requestSkip(occurrence.toSettleTarget())
    }

    /** Marks the occurrence as deliberately not paid. No expense is written and no total moves. */
    fun confirmSkip() {
        viewModelScope.launch { occurrenceActions.confirmSkip() }
    }

    fun dismissSkip() {
        occurrenceActions.dismissSkip()
    }

    /**
     * Runs one of the three schedule actions, reporting a failure without disturbing the read.
     *
     * A "nothing changed" result is not treated as a failure: the screen reads the schedule's own status,
     * so a pause that found it already paused simply leaves the control where the database says it is.
     */
    private fun runAction(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
                actionError.value = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                actionError.value = ActionError.Failed(failure)
            }
        }
    }

    private fun ScheduleAndOccurrencesRead.toUiState(failure: ActionError?): RecurringDetailUiState =
        when (this) {
            ScheduleAndOccurrencesRead.Loading -> RecurringDetailUiState.Loading
            ScheduleAndOccurrencesRead.Missing -> RecurringDetailUiState.NotFound
            is ScheduleAndOccurrencesRead.Failed -> RecurringDetailUiState.Failed(cause)
            is ScheduleAndOccurrencesRead.Rows -> {
                val items = occurrences.map { it.toItem(today) }

                RecurringDetailUiState.Content(
                    schedule = schedule.toCard(today),
                    // The earliest still-pending occurrence, settled or not - an overdue one is still the
                    // most urgent thing the household can act on, which is exactly why it is offered.
                    outstanding = items.filter { !it.state.isSettled() }.minByOrNull { it.dueDate },
                    recentOccurrences = items.take(RECENT_OCCURRENCE_COUNT),
                    occurrenceCount = items.size,
                    today = today,
                    paidCount = items.count { it.state == OccurrenceDisplayState.PAID },
                    // Due and upcoming are counted together: a date that is due today is not late, and
                    // the three figures have to add up with the rows the household can see.
                    upcomingCount = items.count {
                        it.state == OccurrenceDisplayState.UPCOMING ||
                            it.state == OccurrenceDisplayState.DUE
                    },
                    overdueCount = items.count { it.state == OccurrenceDisplayState.OVERDUE },
                    actionError = failure,
                )
            }
        }

    companion object {

        private const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L

        /** How many occurrences the details screen's short history shows before offering the full list. */
        private const val RECENT_OCCURRENCE_COUNT = 5

        /** Builds the view model against the application's own repository. */
        fun factory(scheduleId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY],
                ) { "RecurringDetailViewModel needs the Application to reach the repository" }

                RecurringDetailViewModel(
                    repository = (application as HomeFinanceApp).recurringRepository,
                    scheduleId = scheduleId,
                )
            }
        }

        /** Builds the view model over [repository] with [today] pinned, for tests. */
        fun factory(
            repository: RecurringRepository,
            scheduleId: Long,
            today: LocalDate,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { RecurringDetailViewModel(repository, scheduleId, today) }
        }
    }
}
