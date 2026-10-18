package com.homefinance.app.data.local.relation

import androidx.room.ColumnInfo
import com.homefinance.app.data.model.RecurringOccurrenceStatus
import com.homefinance.app.data.model.TransactionType
import java.time.Instant
import java.time.LocalDate

/**
 * One scheduled occurrence read together with the schedule it belongs to and that schedule's
 * category.
 *
 * This is a **read-only projection over a join**, not an entity. It exists so that one query can
 * answer everything the occurrence screens ask: the date, the expected amount and the state of the
 * occurrence itself, plus the schedule's name and category, which the payment dialog has to state
 * plainly ("Shop rent - Groceries, expected ₹15,000.00, due 5 Oct 2026") before the household
 * confirms anything. Reading those per row would be the N+1 query the other projections avoid.
 *
 * @param scheduleName the schedule's display name, carried so a payment dialog can name what is
 *   being paid without a second read.
 * @param categoryId the category the payment's transaction will be filed under.
 * @param categoryName that category's name, for the same reason as [scheduleName].
 * @param transactionId the actual expense this occurrence was paid as, or `null`. It is `null` while
 *   the occurrence is pending, and it also becomes `null` if the household deletes that expense from
 *   the ledger - the occurrence's [status] then still says `PAID`, and the screens say that the entry
 *   it was linked to is no longer recorded.
 */
data class RecurringOccurrenceDetail(
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

    @ColumnInfo(name = "transaction_id")
    val transactionId: Long?,

    @ColumnInfo(name = "created_at")
    val createdAt: Instant,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,

    @ColumnInfo(name = "schedule_name")
    val scheduleName: String,

    @ColumnInfo(name = "category_id")
    val categoryId: Long,

    @ColumnInfo(name = "category_name")
    val categoryName: String,

    /**
     * That category's own type, which is the direction of the money this payment moves.
     *
     * Carried so the payment sheet can say what it is about to write - "this will create a new income
     * transaction" - and colour its heading, without a second read while the sheet is already open. It
     * is the same value [com.homefinance.app.data.repository.RecurringRepository.markOccurrencePaid]
     * files the entry under, so the sheet cannot promise one direction and record the other.
     */
    @ColumnInfo(name = "category_type")
    val categoryType: TransactionType,

    /**
     * The mark that category is drawn with, or `null` when nobody ever chose one - see
     * [com.homefinance.app.data.model.CategoryIconKey]. Read from the schedule's category, which is
     * the category the payment will be filed under, so a row and its dialog agree on the picture as
     * well as on the name.
     */
    @ColumnInfo(name = "category_icon_key")
    val categoryIconKey: String? = null,
)
