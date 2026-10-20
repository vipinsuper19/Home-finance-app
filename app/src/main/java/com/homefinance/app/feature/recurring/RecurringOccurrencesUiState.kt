package com.homefinance.app.feature.recurring

import androidx.compose.runtime.Immutable

/**
 * Everything one schedule's occurrence history draws, as one immutable snapshot.
 *
 * A sealed hierarchy rather than a data class, for the same reason as
 * [RecurringDetailUiState]: it is a read of the database plus one schedule, and the four states are
 * mutually exclusive.
 *
 * There is deliberately no failed-*action* slot here. This screen owns no action on the schedule itself;
 * the only things it can do are settle an occurrence, and those failures are reported inside the dialog
 * that asked for them, where the household is already looking.
 */
@Immutable
sealed interface RecurringOccurrencesUiState {

    /** The first read, before the database has answered. */
    data object Loading : RecurringOccurrencesUiState

    /** The schedule is not there any more. */
    data object NotFound : RecurringOccurrencesUiState

    /** The read failed; [cause] is kept so a test can prove which failure reached the screen. */
    data class Failed(val cause: Throwable) : RecurringOccurrencesUiState

    /**
     * Every occurrence of the schedule: waiting dates soonest first, then settled history newest first.
     *
     * An empty list is a success: a schedule whose first occurrence is further out than the generation
     * horizon has none yet, and that is a fact about the schedule rather than a failure to read it.
     *
     * @param scheduleName the schedule's name, for the title, carried rather than passed in so the screen
     *   cannot end up labelled with a name the database has since changed.
     */
    data class Content(
        val scheduleName: String,
        val occurrences: List<RecurringOccurrenceItem>,
    ) : RecurringOccurrencesUiState
}
