package com.homefinance.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.homefinance.app.data.model.RecurringOccurrenceStatus
import java.time.Instant
import java.time.LocalDate

/**
 * One date on which a [RecurringScheduleEntity] falls due, and what became of it.
 *
 * ### Three things, three rows
 *
 * A schedule, a scheduled occurrence, and an actual transaction are three different facts:
 *
 *  1. the **schedule** says "rent, ₹15,000, monthly from the 5th";
 *  2. the **occurrence** says "the rent was expected on 5 October" - and nothing more. Generating it
 *     writes no ledger entry and moves no total, which is why a bill can fall due without the
 *     household's expenses changing;
 *  3. the **transaction** is the money actually moving, written only when the household records the
 *     payment, and linked back through [transactionId].
 *
 * ### Only terminal states are stored
 *
 * [status] is one of `PENDING`, `PAID` or `SKIPPED`. "Upcoming", "due today" and "overdue" are
 * deliberately *not* stored: they are the same `PENDING` row read on a different day, and a stored
 * flag would have to be rewritten by something at midnight, would be wrong in between, and would
 * never be right on a device that had been asleep. They are derived from the due date against
 * today's local date where they are displayed.
 *
 * ### One occurrence per schedule per date, enforced by the database
 *
 * The unique index on `(schedule_id, due_date)` is the **final authority** on duplicates. Generation
 * can therefore be run twice, interrupted, run concurrently, or replayed after a crash, and the
 * worst it can do is fail to insert a row that is already there. A "has this already been generated?"
 * read in application code would be a second statement that a second run could interleave with,
 * which is exactly the shape of bug that quietly bills a household twice.
 *
 * ### The money is a snapshot
 *
 * [amountPaise] is copied from the schedule when the occurrence is generated. It is what the
 * household was expecting *then*. Editing the schedule's amount later does not rewrite it, so an
 * overdue occurrence keeps saying what was owed on the day it fell due, and the history cannot be
 * made to disagree with itself.
 *
 * ### Deleting
 *
 * Occurrences are never deleted, only resolved: [RecurringOccurrenceStatus.PAID] and
 * [RecurringOccurrenceStatus.SKIPPED] are terminal, and a `SKIPPED` occurrence is kept for the same
 * reason a `PAID` one is - so that it is never generated again. The foreign key to
 * [RecurringScheduleEntity] refuses to delete a schedule that still has occurrences, and the foreign
 * key to [TransactionEntity] *clears the link* (`ON DELETE SET NULL`) if the household deletes the
 * actual expense from the ledger: the occurrence then still honestly says the payment was recorded,
 * and the screen says that the entry it linked to is no longer in the ledger.
 *
 * @param id surrogate key, allocated by SQLite on insert (`0` means "not yet inserted").
 * @param scheduleId the schedule this occurrence belongs to.
 * @param dueDate the calendar day it falls due on; a plain day, stored as an ISO epoch day.
 * @param amountPaise the expected amount at the moment it was generated, in paise.
 * @param status `PENDING`, `PAID` or `SKIPPED`; see the class documentation.
 * @param transactionId the actual expense this occurrence was paid as, or `null` while it is
 *   pending (or after that entry has been deleted from the ledger).
 * @param createdAt audit timestamp (UTC instant) for when the row was written.
 * @param updatedAt audit timestamp for when the row was last changed.
 */
@Entity(
    tableName = "recurring_occurrences",
    foreignKeys = [
        ForeignKey(
            entity = RecurringScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["schedule_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transaction_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        // The duplicate authority, and the index every "this schedule's occurrences" read uses by
        // its leading column.
        Index(value = ["schedule_id", "due_date"], unique = true),
        // A foreign key's child column should be indexed so the parent's deletions/deletions-check
        // do not scan this table.
        Index(value = ["transaction_id"]),
    ],
)
data class RecurringOccurrenceEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "schedule_id")
    val scheduleId: Long,

    @ColumnInfo(name = "due_date")
    val dueDate: LocalDate,

    @ColumnInfo(name = "amount_paise")
    val amountPaise: Long,

    @ColumnInfo(name = "status")
    val status: RecurringOccurrenceStatus = RecurringOccurrenceStatus.PENDING,

    @ColumnInfo(name = "transaction_id")
    val transactionId: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Instant,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
)
