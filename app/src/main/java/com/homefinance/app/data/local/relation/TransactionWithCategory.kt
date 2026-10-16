package com.homefinance.app.data.local.relation

import androidx.room.ColumnInfo
import com.homefinance.app.data.model.TransactionType
import java.time.LocalDate

/**
 * One ledger row read together with the name of the category it is filed under.
 *
 * This is a **read-only projection over a join**, not an entity: it has no table, is never
 * inserted and is never updated. It exists because the list needs the category's *name*, which
 * lives in another table, and resolving that one name per row in application code would be the
 * classic N+1 query - a ledger with two hundred entries would ask the database two hundred extra
 * questions to render one screen. SQL already knows how to do this in a single statement.
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

    @ColumnInfo(name = "transaction_date")
    val transactionDate: LocalDate,

    @ColumnInfo(name = "category_name")
    val categoryName: String,
)
