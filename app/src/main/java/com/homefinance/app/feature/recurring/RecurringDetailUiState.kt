package com.homefinance.app.feature.recurring

import androidx.compose.runtime.Immutable

/**
 * Everything one schedule's details screen draws, as one immutable snapshot.
 *
 * A sealed hierarchy rather than a data class, because there is no in-progress work here to protect: it
 * is a read of the database plus one schedule, and the four states really are mutually exclusive.
 *
 * @see ScheduleAndOccurrencesRead for why "the schedule has gone" is kept apart from "the read failed".
 */
@Immutable
sealed interface RecurringDetailUiState {

    /** The first read, before the database has answered. */
    data object Loading : RecurringDetailUiState

    /** The schedule is not there any more. */
    data object NotFound : RecurringDetailUiState

    /** The read failed; [cause] is kept so a test can prove which failure reached the screen. */
    data class Failed(val cause: Throwable) : RecurringDetailUiState

    /**
     * The schedule, what it is waiting on, and what it has already done.
     *
     * @param schedule the schedule's own facts, with its next due date already read against today.
     * @param outstanding the earliest occurrence that is still pending - **not** the earliest that is
     *   overdue. A household opening a schedule wants the one thing they can act on next, and if nothing
     *   is pending there is genuinely nothing to offer, however far back the last paid date is.
     * @param recentOccurrences the newest occurrences, for the short history at the foot of the screen.
     *   The full list lives on the occurrence history screen.
     * @param occurrenceCount how many occurrences exist in total, so the history's own button can say
     *   whether it is worth opening without that screen having to be read first.
     * @param actionError why the last pause, resume or stop failed, or `null`. It sits *beside* the
     *   schedule rather than replacing it - the facts underneath are still perfectly true.
     */
    data class Content(
        val schedule: RecurringScheduleCard,
        val outstanding: RecurringOccurrenceItem?,
        val recentOccurrences: List<RecurringOccurrenceItem>,
        val occurrenceCount: Int,
        val actionError: ActionError? = null,
    ) : RecurringDetailUiState
}
