package com.homefinance.app.feature.recurring

import androidx.compose.runtime.Immutable
import com.homefinance.app.data.model.RecurrenceFrequency
import com.homefinance.app.data.model.RecurringScheduleStatus
import java.time.Instant
import java.time.LocalDate

/**
 * Everything the Recurring tab draws, as one immutable snapshot.
 *
 * A sealed hierarchy rather than the form's data class, because this screen has no in-progress work to
 * protect: it is a read of the database, and the three states really are mutually exclusive. Drawing
 * rows beside a spinner, or beside an error, would be the same mistake
 * [com.homefinance.app.feature.transactions.TransactionsUiState] avoids.
 */
@Immutable
sealed interface RecurringListUiState {

    /** The first read, before the database has answered. */
    data object Loading : RecurringListUiState

    /**
     * The schedules, in the order the query returns them: active first, then paused, then stopped, and
     * soonest due date first within each group.
     *
     * An empty list is a success and is drawn as an invitation to add the first schedule - unlike
     * [Failed], which must never be dressed as "you have nothing", because a household who cannot read
     * their schedules still has them.
     *
     * @param actionError why the last pause/resume/stop attempt failed, or `null`. It sits *beside* the
     *   rows rather than replacing them: the failure of one action must not take the list away.
     */
    data class Ready(
        val schedules: List<RecurringScheduleCard>,
        val actionError: ActionError? = null,
    ) : RecurringListUiState

    /** The query failed; [cause] is kept so a test can prove which failure reached the screen. */
    data class Failed(val cause: Throwable) : RecurringListUiState
}

/**
 * One schedule as the recurring screens draw it. The list draws part of it; the details screen draws
 * all of it.
 *
 * A display type rather than the database projection, so neither screen has to decide what a `PENDING`
 * occurrence with a past date means, and so a row cannot be drawn from a value that has already gone
 * stale: [nextDueState] was read against one known "today", and the view model rebuilds the whole card
 * when the underlying query emits.
 *
 * @param addedAt when the schedule was created, for the details card. It is the schedule's own creation
 *   stamp and is never rewritten by an edit, so it answers "when did I set this up".
 * @param startDate the day of the first occurrence, and the anchor every later one falls from.
 * @param endDate the last day an occurrence may fall on, or `null` for "until paused".
 * @param nextDueDate the earliest still-unresolved occurrence, or `null` when nothing is outstanding.
 * @param nextDueState how that date reads against today, or `null` when there is no such date.
 */
@Immutable
data class RecurringScheduleCard(
    val id: Long,
    val name: String,
    val description: String?,
    val categoryName: String,
    /** The category's stored mark, or `null` when nobody chose one. Drawn as the card's mark. */
    val categoryIconKey: String? = null,
    val amountPaise: Long,
    val frequency: RecurrenceFrequency,
    val intervalCount: Int,
    val status: RecurringScheduleStatus,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val addedAt: Instant,
    val nextDueDate: LocalDate?,
    val nextDueState: OccurrenceDisplayState?,
)

/**
 * One occurrence as the recurring screens draw it.
 *
 * The state is derived here, once, against the view model's own "today", so a row cannot be drawn as
 * "overdue" by one screen and "due" by another, and the settle dialogs get the schedule's name and
 * category name from the row they were opened on rather than from a second read that could disagree.
 *
 * @param transactionRemoved true when the occurrence is marked paid but the expense it was paid as is no
 *   longer in the ledger. The occurrence keeps its "Paid" chip - the payment *was* recorded - and the row
 *   says the entry is gone rather than being quietly relabelled as unpaid.
 */
@Immutable
data class RecurringOccurrenceItem(
    val id: Long,
    val scheduleName: String,
    val categoryName: String,
    /** The category's stored mark, or `null` when nobody chose one. Drawn as the row's mark. */
    val categoryIconKey: String? = null,
    val dueDate: LocalDate,
    val amountPaise: Long,
    val state: OccurrenceDisplayState,
    val transactionRemoved: Boolean,
)

/**
 * Why a pause, resume or stop failed.
 *
 * Kept apart from [RecurringListUiState.Failed] on purpose: a failed *read* means the screen has
 * nothing truthful to show, while a failed *action* means the list is still exactly as valid as it was
 * and one thing did not happen. Collapsing the two would throw the household's schedules off screen
 * because a single tap went wrong.
 */
@Immutable
sealed interface ActionError {

    /** The write itself failed. [cause] is kept for tests and a future crash log. */
    data class Failed(val cause: Throwable) : ActionError

    /**
     * The generation run failed, so some due dates may not be filled in yet.
     *
     * Reported rather than swallowed: the schedules on screen are true, but a next due date that is a
     * month behind because the last few runs failed is a quiet lie. Nothing was lost - generation is
     * idempotent and simply resumes on the next visit - so this asks for nothing but honesty.
     */
    data class GenerationFailed(val cause: Throwable) : ActionError
}
