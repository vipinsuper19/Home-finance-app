package com.homefinance.app.data.local.relation

import androidx.room.ColumnInfo
import com.homefinance.app.data.model.RecurrenceFrequency
import com.homefinance.app.data.model.RecurringOccurrenceStatus
import com.homefinance.app.data.model.RecurringScheduleStatus
import com.homefinance.app.data.model.TransactionType
import java.time.LocalDate

/**
 * One occurrence that is still waiting, read together with the schedule that produced it and that
 * schedule's category.
 *
 * This is a **read-only projection over a join**, not an entity, and it answers a different question
 * from [RecurringOccurrenceDetail]: not "what became of the dates of *this* schedule", but "which
 * dates across *every* schedule can still be acted on". That is what the Home dashboard draws, and it
 * is why the join carries the schedule's rule as well as its name - a row there says what each date is
 * and how it comes round ("Monthly (on 5th)"), so the frequency and the interval have to arrive with
 * the row rather than through a second read.
 *
 * ### What a caller may assume
 *
 * The query that builds this projection returns only occurrences whose own status is `PENDING`. So
 * [status] is always `PENDING` in practice, and it is carried anyway: it is the column the reading is
 * derived from, and a projection that quietly dropped it would make
 * [com.homefinance.app.feature.recurring.occurrenceDisplayState] look like it had been handed a fact
 * when it had been handed an assumption.
 *
 * A paid or skipped date is deliberately absent, not merely filtered later: an occurrence that has
 * been dealt with is not work, and drawing one beside the ones that are waiting would be the screen
 * asking twice for the same bill.
 *
 * A paused or retired schedule's dates are **not** absent, and [scheduleStatus] is carried so a caller
 * can say why: pausing or stopping a schedule stops it generating more dates, but the dates it has
 * already generated stay payable, so hiding them here would hide a bill the household still owes. See
 * [com.homefinance.app.data.local.dao.RecurringDao.observeOutstandingOccurrences] for the whole
 * argument.
 *
 * @param scheduleId the schedule the date belongs to, so a row can open that schedule's details
 *   without a second read.
 * @param scheduleStatus the state of that schedule. Always `ACTIVE` in the common case; `PAUSED` or
 *   `RETIRED` means this date predates the schedule being paused or stopped and is the last of what it
 *   generated.
 * @param amountPaise what was expected on [dueDate], which is the occurrence's own figure - never the
 *   schedule's current one, which an edit may since have changed.
 * @param frequency and [intervalCount] the rule the date came from, for a row's "Monthly (on 5th)"
 *   summary. Only used as a *translation* by the screen; nothing here is recomputed from them.
 * @param categoryName the category the payment will be filed under, and [categoryType] that
 *   category's own direction - which is the direction of the money, and the same value the recording
 *   of a payment files the entry under.
 * @param categoryIconKey that category's stored mark, or `null` when nobody ever chose one.
 */
data class OutstandingOccurrence(
    @ColumnInfo(name = "id")
    val id: Long,

    @ColumnInfo(name = "schedule_id")
    val scheduleId: Long,

    @ColumnInfo(name = "due_date")
    val dueDate: LocalDate,

    @ColumnInfo(name = "amount_paise")
    val amountPaise: Long,

    @ColumnInfo(name = "status")
    val status: RecurringOccurrenceStatus,

    @ColumnInfo(name = "schedule_name")
    val scheduleName: String,

    @ColumnInfo(name = "schedule_status")
    val scheduleStatus: RecurringScheduleStatus,

    @ColumnInfo(name = "frequency")
    val frequency: RecurrenceFrequency,

    @ColumnInfo(name = "interval_count")
    val intervalCount: Int,

    @ColumnInfo(name = "category_id")
    val categoryId: Long,

    @ColumnInfo(name = "category_name")
    val categoryName: String,

    @ColumnInfo(name = "category_type")
    val categoryType: TransactionType,

    @ColumnInfo(name = "category_icon_key")
    val categoryIconKey: String? = null,
)
