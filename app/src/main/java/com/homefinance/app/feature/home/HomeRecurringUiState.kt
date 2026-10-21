package com.homefinance.app.feature.home

import androidx.compose.runtime.Immutable
import com.homefinance.app.data.local.relation.OutstandingOccurrence
import com.homefinance.app.data.model.RecurrenceFrequency
import com.homefinance.app.data.model.RecurringScheduleStatus
import com.homefinance.app.data.model.TransactionType
import com.homefinance.app.feature.recurring.OccurrenceDisplayState
import com.homefinance.app.feature.recurring.occurrenceDisplayState
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * How many upcoming dates the Home section previews before sending the household to the recurring tab.
 *
 * Five, because a preview is what this is: the section is meant to answer "what is coming" at a
 * glance, and a list of every date three months out would turn the dashboard into the full list and
 * bury the two things above it. The preview is also narrowed to one date per category, so a daily
 * milk schedule cannot fill all five rows with the same bill. The header's count is the **total**,
 * counted per category, so a preview of five never pretends there are only five - and nothing is lost
 * by limiting the drawing, because every date is still on the recurring tab, which is one tap away.
 *
 * Overdue and due-today dates are deliberately *not* limited or narrowed: those are the dates that
 * want doing something about, and hiding any of them would be the section deciding for the household
 * which of their own bills matters.
 */
internal const val UPCOMING_PREVIEW_LIMIT = 5

/**
 * What the Home screen's recurring section is showing.
 *
 * A sealed hierarchy rather than a data class with a `loading` flag, for the reason the recurring tab
 * gives: these three states are mutually exclusive, and drawing stale rows beside a spinner (or beside
 * a failure) is how a screen ends up telling two stories at once. A failure here is a failure of the
 * *section*: the rest of the dashboard is unaffected, which is exactly why this is carried inside
 * [HomeUiState] rather than replacing it.
 */
@Immutable
sealed interface HomeRecurringUiState {

    /** The first read, before the database has answered. */
    data object Loading : HomeRecurringUiState

    /**
     * The dates the household can act on, split into the three readings the section draws.
     *
     * Each list is already in the order it is drawn - soonest due date first - because the query
     * supplies that order and nothing here has to re-derive it.
     *
     * @param overdue dates whose day has passed and which are still waiting. Never empty-by-trimming:
     *   every one of them is drawn.
     * @param dueToday dates that fall on today, and [overdue]'s counterpart for the same reason.
     * @param upcoming the next date of each category, soonest first and at most
     *   [UPCOMING_PREVIEW_LIMIT] categories. A category with several waiting dates contributes only
     *   its soonest one.
     * @param upcomingTotal how many categories are coming in all, which is what the group's own
     *   heading counts - so a preview of five can never read as "there are only five".
     * @param hasAnySchedule whether the household has ever set a schedule up, whatever state it is in
     *   now. It is what tells "nothing set up yet", which deserves an invitation, apart from "nothing
     *   waiting", which deserves the truth: a household whose schedules are all paused or settled has
     *   real schedules, and telling them they have none would be a lie about their own records.
     * @param generationFailed true when this visit's run of the recurrence engine failed, so a date
     *   that should have been generated may be missing. Reported rather than swallowed, and *beside*
     *   the rows rather than instead of them: what is on screen is still true, it may simply be
     *   incomplete - see [HomeViewModel].
     */
    data class Ready(
        val overdue: List<HomeRecurringItem>,
        val dueToday: List<HomeRecurringItem>,
        val upcoming: List<HomeRecurringItem>,
        val upcomingTotal: Int = upcoming.size,
        val hasAnySchedule: Boolean = true,
        val generationFailed: Boolean = false,
    ) : HomeRecurringUiState {

        /** True when there is no schedule at all, so the section can invite the first one. */
        val isFreshHousehold: Boolean get() = !hasAnySchedule
    }

    /** The read failed; [cause] is kept so a test can prove which failure reached the section. */
    data class Failed(val cause: Throwable) : HomeRecurringUiState
}

/**
 * One date as the Home section draws it.
 *
 * A display type rather than the database projection, and derived against one known "today" for the
 * same reason [com.homefinance.app.feature.recurring.RecurringOccurrenceItem] is: the reading of a
 * date against today changes every midnight, so it is worked out once, when the row is built, rather
 * than being asked for again in the middle of drawing a list.
 *
 * @param occurrenceId the occurrence itself - never the schedule's id, because a schedule with several
 *   waiting dates has one row per date and the two must not be confused.
 * @param scheduleId the schedule to open when the row is tapped.
 * @param categoryId the category the date is filed under. Home draws one upcoming date per category,
 *   so this is the identity that grouping uses; two schedules under one category share it.
 * @param state where the date stands against today: overdue, due or upcoming. Never paid or skipped -
 *   the query behind this projection returns only dates that are still waiting.
 * @param daysFromToday how many whole days separate the due date from today: `-3` when it fell due
 *   three days ago, `0` for today, `5` for five days ahead. Carried as one signed number rather than
 *   two, so "3 days overdue" and "In 5 days" are read off the same fact and cannot disagree about
 *   which side of today a date is on.
 * @param frequency and [intervalCount] the rule the date came from, resolved into words by the screen.
 * @param scheduleStatus the state of the schedule the date belongs to. It is carried so the row can say
 *   *why* a date from a paused or stopped schedule is still asking for attention: those dates were
 *   generated before the switch was turned off and are still payable, so the section shows them and
 *   names their schedule's state rather than leaving the household to wonder.
 */
@Immutable
data class HomeRecurringItem(
    val occurrenceId: Long,
    val scheduleId: Long,
    val categoryId: Long,
    val scheduleName: String,
    val categoryName: String,
    val categoryType: TransactionType,
    val categoryIconKey: String? = null,
    val dueDate: LocalDate,
    val amountPaise: Long,
    val state: OccurrenceDisplayState,
    val daysFromToday: Long,
    val frequency: RecurrenceFrequency,
    val intervalCount: Int,
    val scheduleStatus: RecurringScheduleStatus = RecurringScheduleStatus.ACTIVE,
)

/**
 * Reads one outstanding date as the Home section draws it.
 *
 * The status the projection carries is handed to
 * [com.homefinance.app.feature.recurring.occurrenceDisplayState] rather than assumed to be `PENDING`:
 * the state is then derived from the row the database actually returned, so a row that somehow
 * arrives settled is *read* as settled rather than drawn as work.
 */
internal fun OutstandingOccurrence.toHomeItem(today: LocalDate): HomeRecurringItem = HomeRecurringItem(
    occurrenceId = id,
    scheduleId = scheduleId,
    categoryId = categoryId,
    scheduleName = scheduleName,
    categoryName = categoryName,
    categoryType = categoryType,
    categoryIconKey = categoryIconKey,
    dueDate = dueDate,
    amountPaise = amountPaise,
    state = occurrenceDisplayState(status = status, dueDate = dueDate, today = today),
    daysFromToday = ChronoUnit.DAYS.between(today, dueDate),
    frequency = frequency,
    intervalCount = intervalCount,
    scheduleStatus = scheduleStatus,
)
