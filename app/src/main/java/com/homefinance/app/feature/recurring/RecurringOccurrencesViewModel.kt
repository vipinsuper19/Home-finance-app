package com.homefinance.app.feature.recurring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.homefinance.app.HomeFinanceApp
import com.homefinance.app.data.repository.RecurringRepository
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs one schedule's occurrence list: every date it has generated - waiting dates soonest first,
 * then settled history newest first - and the two things the household can still do to a date that has
 * not been settled.
 *
 * ### Where the data comes from
 *
 * Whatever [observeScheduleAndOccurrences] emits. It reads the whole aggregate rather than only the
 * occurrence list, because the title is the schedule's own name and reading it from the row the household
 * tapped is how the screen cannot end up labelled with a name the database has since changed. Nothing here
 * polls: recording a payment or skipping an occurrence writes to the database, and this query is what
 * pushes the result back - which is why the dialogs report no result through navigation.
 *
 * ### What it does not do
 *
 * It never generates occurrences, and it never settles one by itself. Generation is the Recurring tab's job
 * when it is opened; settling happens only inside the dialogs, through the repository's conditional update
 * and its single ledger write, so a bill whose date passed while the phone was off is still sitting there
 * unpaid until the household says otherwise.
 *
 * @param scheduleId the schedule whose history is shown, carried by the route rather than held anywhere
 *   else.
 * @param today the day the screen considers to be today, used to read each date as upcoming, due,
 *   overdue, paid or skipped. It is a constructor parameter so a test can pin it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecurringOccurrencesViewModel(
    repository: RecurringRepository,
    scheduleId: Long,
    private val today: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val retryRequests = MutableStateFlow(0)

    /**
     * The two settle flows, shared verbatim with the details screen so the two cannot disagree about what
     * "already paid" means. This class only launches them, because a view model is what owns a scope.
     */
    private val occurrenceActions = OccurrenceActionsController(repository)

    /** The open "mark as paid" dialog, or `null` when it is closed. */
    val pay: StateFlow<PayOccurrenceState?> = occurrenceActions.pay

    /** The open "skip this occurrence" dialog, or `null` when it is closed. */
    val skip: StateFlow<SkipOccurrenceState?> = occurrenceActions.skip

    val uiState: StateFlow<RecurringOccurrencesUiState> =
        retryRequests
            .flatMapLatest { repository.observeScheduleAndOccurrences(scheduleId) }
            .map<ScheduleAndOccurrencesRead, RecurringOccurrencesUiState> { read ->
                read.toUiState()
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
                initialValue = RecurringOccurrencesUiState.Loading,
            )

    /** Starts over after a failed read. */
    fun retry() {
        retryRequests.update { it + 1 }
    }

    /** Opens the payment dialog for [occurrence]. Nothing is written until the household confirms. */
    fun requestPay(occurrence: RecurringOccurrenceItem) {
        occurrenceActions.requestPay(occurrence.toSettleTarget())
    }

    fun onPayAmountChange(value: String) {
        occurrenceActions.onPayAmountChange(value)
    }

    /** Records the payment. The dialog closes on success; a refusal is shown inside it. */
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
     * The read as this screen draws it.
     *
     * The name comes from the schedule in the same read rather than from a caller, so the title and the
     * rows can never describe two different schedules.
     */
    private fun ScheduleAndOccurrencesRead.toUiState(): RecurringOccurrencesUiState = when (this) {
        ScheduleAndOccurrencesRead.Loading -> RecurringOccurrencesUiState.Loading
        ScheduleAndOccurrencesRead.Missing -> RecurringOccurrencesUiState.NotFound
        is ScheduleAndOccurrencesRead.Failed -> RecurringOccurrencesUiState.Failed(cause)
        is ScheduleAndOccurrencesRead.Rows -> RecurringOccurrencesUiState.Content(
            scheduleName = schedule.name,
            occurrences = occurrences.map { it.toItem(today) },
        )
    }

    companion object {

        private const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L

        /** Builds the view model against the application's own repository. */
        fun factory(scheduleId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY],
                ) { "RecurringOccurrencesViewModel needs the Application to reach the repository" }

                RecurringOccurrencesViewModel(
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
            initializer { RecurringOccurrencesViewModel(repository, scheduleId, today) }
        }
    }
}
