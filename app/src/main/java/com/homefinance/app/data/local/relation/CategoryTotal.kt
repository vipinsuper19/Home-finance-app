package com.homefinance.app.data.local.relation

import androidx.room.ColumnInfo

/**
 * One category's share of a period: what it came to, and how many entries made it up.
 *
 * A **read-only projection over a grouped join**, not an entity. The report's breakdown sections
 * need each category's *name* and *mark* beside its total, which lives in another table; joining it
 * in SQL is what keeps drawing the whole breakdown to one query, in the same way the ledger's own
 * row projection does.
 *
 * The join is an `INNER JOIN` on purpose, for the reason
 * [TransactionWithCategory] gives: `transactions.category_id` is a non-null foreign key whose parent
 * row can never be deleted (categories are retired rather than removed), so there is no such thing as
 * a transaction without a category - and therefore no "uncategorised" bucket to invent. A category
 * that has since been retired is still named here, because the money really was spent under it.
 *
 * @param categoryId the category the entries were filed under, so a row can open that category's
 *   own report.
 * @param categoryName the stored display name.
 * @param categoryIconKey the stored mark, or `null` when nobody ever chose one - in which case the
 *   row's mark is derived from the name, exactly as it is in the ledger.
 * @param totalPaise the summed amount, an exact `Long` count of paise.
 * @param transactionCount how many entries the total was made of.
 */
data class CategoryTotal(
    @ColumnInfo(name = "category_id")
    val categoryId: Long,

    @ColumnInfo(name = "category_name")
    val categoryName: String,

    @ColumnInfo(name = "category_icon_key")
    val categoryIconKey: String? = null,

    @ColumnInfo(name = "total_paise")
    val totalPaise: Long,

    @ColumnInfo(name = "transaction_count")
    val transactionCount: Long,
)
