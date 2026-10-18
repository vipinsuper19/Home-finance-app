package com.homefinance.app.feature.recurring

import androidx.compose.runtime.Immutable
import com.homefinance.app.data.model.RecurrenceFrequency
import com.homefinance.app.data.model.RecurringScheduleStatus
import com.homefinance.app.data.model.TransactionType
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
     * An empty list is a success. Which of the two empty screens it is depends on [hasAnySchedule]:
     * a household with no schedules at all is invited to add the first one, while a household whose
     * filter simply excluded every row is told that, because "you have nothing" and "you are looking
     * at a narrower slice than you thought" are two different things to say about their own money.
     * Neither is [Failed], which must never be dressed as "you have nothing": a household who cannot
     * read their schedules still has them.
     *
     * [schedules] is the *filtered* list, and it is the only list the screen draws.
     *
     * @param filter which direction the household is looking at. Held here, in the view model's own
     *   state, rather than in the composable's memory so that it survives a configuration change and
     *   so that "what is on screen" is one value a test can assert.
     * @param hasAnySchedule whether the database holds at least one schedule of *any* type. It is what
     *   tells the two empty screens apart, and it is carried rather than recomputed from [schedules]
     *   because a filtered-to-nothing list says nothing about what was filtered out. It defaults to
     *   "there are rows", which is the honest reading for the two callers that have no filter to apply -
     *   a test drawing an empty list, and a preview - so an empty list means "the household has none"
     *   unless a caller explicitly says otherwise.
     * @param actionError why the last pause/resume/stop attempt failed, or `null`. It sits *beside* the
     *   rows rather than replacing them: the failure of one action must not take the list away.
     */
    data class Ready(
        val schedules: List<RecurringScheduleCard>,
        val filter: ScheduleTypeFilter = ScheduleTypeFilter.ALL,
        val hasAnySchedule: Boolean = schedules.isNotEmpty(),
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
    /**
     * The category's own type, which is the direction of the money. Drawn as the mark's colour and
     * filtered on by [ScheduleTypeFilter]; nothing on the card is derived from it.
     */
    val categoryType: TransactionType,
    /** The category's stored mark, or `null` when nobody chose one. Drawn as the card's mark. */
    val categoryIconKey: String? = null,
    val amountPaise: Long,
    /**
     * What one unit is called, or `null` when the household did not price it by unit. Printed beside
     * the amount - "₹60.00 / litre" - never on its own.
     */
    val unit: String? = null,
    /** The price of one unit in paise, or `null`. Given with [unit] or not at all. */
    val ratePaise: Long? = null,
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
    /**
     * The category's own type, which is the direction of the money this occurrence settles.
     *
     * Carried so the payment sheet can say which direction the entry it is about to write will be,
     * rather than having to ask a second source that could disagree with the row it was opened from.
     */
    val categoryType: TransactionType,
    /** The category's stored mark, or `null` when nobody chose one. Drawn as the row's mark. */
    val categoryIconKey: String? = null,
    val dueDate: LocalDate,
    val amountPaise: Long,
    val state: OccurrenceDisplayState,
    val transactionRemoved: Boolean,
)

/**
 * Which direction of schedule the list is showing.
 *
 * A schedule's direction is its category's own type - see [RecurringScheduleCard.categoryType] - so
 * this filters on a fact every row already carries rather than on anything the screen decides. It is
 * the same All / Expense / Income choice the ledger's own filter bar offers, deliberately: a
 * household who has learned what those three words mean on one screen should not have to learn them
 * again on the next, and the mock-up that asked for this bar draws them in that order.
 *
 * `ALL` is not "no filter" as a special case: it is the choice that matches everything, so the bar
 * always has exactly one selected chip and the screen never has to explain what is unselected.
 */
@Immutable
enum class ScheduleTypeFilter {

    /** Every schedule, whatever its direction. */
    ALL,

    /** Only schedules filed under an expense category: the ones that will cost money. */
    EXPENSE,

    /** Only schedules filed under an income category: the ones that will bring money in. */
    INCOME,
    ;

    /** Whether a schedule filed under a category of [type] belongs in this view. */
    fun matches(type: TransactionType): Boolean = when (this) {
        ALL -> true
        EXPENSE -> type == TransactionType.EXPENSE
        INCOME -> type == TransactionType.INCOME
    }
}

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
