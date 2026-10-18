package com.homefinance.app.data.local.relation

import androidx.room.ColumnInfo
import com.homefinance.app.data.model.RecurrenceFrequency
import com.homefinance.app.data.model.RecurringScheduleStatus
import java.time.Instant
import java.time.LocalDate

/**
 * One recurring schedule read together with the name of its category, and the date it next falls
 * due.
 *
 * This is a **read-only projection over a join**, not an entity: it has no table, is never inserted
 * and is never updated. It exists for exactly the reason
 * [com.homefinance.app.data.local.relation.TransactionWithCategory] does - the list and the details
 * screen both need the category's *name* beside the figures, and resolving one name per row in
 * application code would be the classic N+1 query.
 *
 * ### Why "next due" is computed here and nowhere else
 *
 * [nextDueDate] is the earliest still-unresolved occurrence of this schedule, worked out by SQL as a
 * correlated `MIN`. It is not a column on the schedule, and that is deliberate: a stored "next due
 * date" is a second copy of a fact that the occurrences already hold, and the two would drift the
 * first time an occurrence was paid, skipped or generated. It is also not a *status* - whether that
 * date is upcoming, today or already past is derived from today's date where it is displayed, because
 * a row read at 23:59 and 00:01 must not have to be rewritten to stay true.
 *
 * `null` means "nothing is unresolved": every occurrence so far has been paid or skipped, or none
 * has been generated yet (a schedule whose start date is beyond the planning horizon, or one that has
 * been retired).
 *
 * ### Every column the entity has, and two more
 *
 * The projection carries the whole schedule rather than the handful of fields a row draws, so the
 * list and the details screen read the same shape and neither has to grow a second query later.
 *
 * @param nextDueDate the earliest `PENDING` occurrence's due date, or `null` when there is none.
 * @param categoryName the name of the category occurrences will be filed under. `INNER JOIN`, because
 *   a schedule's category is a non-null foreign key to a table whose rows are retired rather than
 *   deleted - there is no such thing as a schedule without a category.
 */
data class RecurringScheduleWithProgress(
    @ColumnInfo(name = "id")
    val id: Long,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "description")
    val description: String?,

    @ColumnInfo(name = "category_id")
    val categoryId: Long,

    @ColumnInfo(name = "amount_paise")
    val amountPaise: Long,

    @ColumnInfo(name = "frequency")
    val frequency: RecurrenceFrequency,

    @ColumnInfo(name = "interval_count")
    val intervalCount: Int,

    @ColumnInfo(name = "start_date")
    val startDate: LocalDate,

    @ColumnInfo(name = "end_date")
    val endDate: LocalDate?,

    @ColumnInfo(name = "status")
    val status: RecurringScheduleStatus,

    @ColumnInfo(name = "generated_through")
    val generatedThrough: LocalDate?,

    @ColumnInfo(name = "created_at")
    val createdAt: Instant,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,

    @ColumnInfo(name = "next_due_date")
    val nextDueDate: LocalDate?,

    @ColumnInfo(name = "category_name")
    val categoryName: String,
)
