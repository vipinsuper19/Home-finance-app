package com.homefinance.app.data.local.relation

import androidx.room.ColumnInfo
import com.homefinance.app.data.model.TransactionType
import java.time.Instant
import java.time.LocalDate

/**
 * One transaction read together with the name of the category it is filed under.
 *
 * This is a **read-only projection over a join**, not an entity: it has no table, is never
 * inserted and is never updated. It exists because three screens need a category's *name* beside
 * the figures - the ledger's rows, the details of one entry, and the form that edits one - and
 * resolving that one name per row in application code would be the classic N+1 query: a ledger
 * with two hundred entries would ask the database two hundred extra questions to render one
 * screen. SQL already knows how to do this in a single statement.
 *
 * ### Every column the entity has, and one more
 *
 * The projection carries the whole transaction, not just the handful of fields the ledger list
 * draws. That is deliberate: a row read for the list and a row read for its details screen are the
 * same row, and two projections of one table would be two places to keep in step every time a
 * column is added. The list simply does not look at the fields it does not draw, and the extra
 * columns cost one query nothing - they are in the same page of the same row.
 *
 * Every property is mapped with an explicit [ColumnInfo] name, and the query aliases every
 * selected column to match, so adding a column to either table cannot silently shift a value into
 * the wrong property.
 *
 * The enum, date and timestamp conversions are the ones already registered on
 * [com.homefinance.app.data.local.HomeFinanceDatabase], so `type` arrives as a
 * [TransactionType] and `transaction_date` as a timezone-free [LocalDate] - exactly as they do
 * when the entity is read directly.
 *
 * @param categoryName the name of the joined category. Never `null`: the join is an `INNER JOIN`
 *   and `transactions.category_id` is a `NOT NULL` foreign key whose parent row can never be
 *   deleted (see [com.homefinance.app.data.local.entity.CategoryEntity]'s deletion policy), so
 *   every transaction is guaranteed to have a category to join to.
 */
data class TransactionWithCategory(
    @ColumnInfo(name = "id")
    val id: Long,

    @ColumnInfo(name = "type")
    val type: TransactionType,

    @ColumnInfo(name = "amount_paise")
    val amountPaise: Long,

    @ColumnInfo(name = "category_id")
    val categoryId: Long,

    @ColumnInfo(name = "description")
    val description: String?,

    @ColumnInfo(name = "notes")
    val notes: String?,

    @ColumnInfo(name = "quantity_thousandths")
    val quantityThousandths: Long?,

    @ColumnInfo(name = "rate_paise")
    val ratePaise: Long?,

    @ColumnInfo(name = "transaction_date")
    val transactionDate: LocalDate,

    @ColumnInfo(name = "created_at")
    val createdAt: Instant,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,

    @ColumnInfo(name = "category_name")
    val categoryName: String,
)
