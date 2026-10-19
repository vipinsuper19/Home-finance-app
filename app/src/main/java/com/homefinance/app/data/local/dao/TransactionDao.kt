package com.homefinance.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.homefinance.app.data.local.entity.TransactionEntity
import com.homefinance.app.data.local.relation.CategoryTotal
import com.homefinance.app.data.local.relation.DailyTotal
import com.homefinance.app.data.local.relation.TransactionTotals
import com.homefinance.app.data.local.relation.TransactionWithCategory
import com.homefinance.app.data.model.TransactionType
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes for [TransactionEntity].
 *
 * Date ranges are inclusive on both ends and are expressed with the same [LocalDate] type stored
 * on the entity, so a caller never has to know that a date is an epoch day underneath.
 *
 * Every listing ends with `ORDER BY transaction_date DESC, id DESC`. The date alone is not
 * deterministic - a household easily records three entries on the same day - and an unstable
 * order makes a list appear to jump around for no reason. The `id` tie-break sorts same-day
 * entries newest-recorded-first, which is stable forever because ids are never reused.
 *
 * ### Reporting reads
 *
 * The aggregate reads at the bottom of this interface are what the Reports screen is built from.
 * They are still just statements - a sum, a grouped sum, a daily sum - and they deliberately take
 * their period as a pair of [LocalDate] bounds rather than as a `YearMonth` or a "month": this layer
 * does not know what a month, a week or a report is, and the folding of days into the buckets a
 * chart draws happens in Kotlin, where [LocalDate] arithmetic is already the single definition of
 * every calendar rule in the app.
 *
 * Money never leaves these queries as anything but an exact `Long` count of paise, summed by SQLite
 * over `INTEGER` columns. Nothing is loaded and totalled in the UI layer: each report figure is one
 * number the database computed.
 *
 * Only `transactions` is read. A recurring schedule's pending occurrence is an expectation in
 * another table and cannot appear in a total; a paid one appears exactly once, as the ordinary
 * entry that recording it wrote.
 */
@Dao
interface TransactionDao {

    /** Inserts a transaction and returns its new `id`. */
    @Insert
    suspend fun insert(transaction: TransactionEntity): Long

    /** Replaces the stored row with the same `id`. */
    @Update
    suspend fun update(transaction: TransactionEntity)

    /** Returns the transaction with [id], or `null` if there is none. */
    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    /** Observes the whole ledger, newest first. */
    @Query("SELECT * FROM transactions ORDER BY transaction_date DESC, id DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    /**
     * Observes the whole ledger, newest first, each row carrying the name of the category it is
     * filed under.
     *
     * One statement, so rendering the list costs one query no matter how many rows there are.
     * The join is an `INNER JOIN` on purpose: `category_id` is a non-null foreign key to a table
     * whose rows are retired rather than deleted, so there is no such thing as a transaction
     * without a category, and an outer join would only invent a `null` case that cannot happen.
     *
     * Ordering matches [observeAll] exactly - `transaction_date DESC, id DESC` - including the
     * `id` tie-break that keeps same-day entries in a stable order.
     */
    @Query(
        """
        SELECT t.id                   AS id,
               t.type                 AS type,
               t.amount_paise         AS amount_paise,
               t.category_id          AS category_id,
               t.description          AS description,
               t.notes                AS notes,
               t.quantity_thousandths AS quantity_thousandths,
               t.rate_paise           AS rate_paise,
               t.transaction_date     AS transaction_date,
               t.created_at           AS created_at,
               t.updated_at           AS updated_at,
               c.name                 AS category_name,
               c.icon_key             AS category_icon_key
        FROM transactions AS t
        INNER JOIN categories AS c ON c.id = t.category_id
        ORDER BY t.transaction_date DESC, t.id DESC
        """,
    )
    fun observeAllWithCategory(): Flow<List<TransactionWithCategory>>

    /**
     * Observes the single transaction with [id], carrying the name of its category, or emits `null`
     * when there is no such transaction any more.
     *
     * A `Flow` rather than a one-shot read because the details screen must keep telling the truth
     * after the entry has been edited from that very screen: Room re-runs this and the figures on
     * screen change with it, instead of the household being shown the row as it was when they opened
     * it.
     *
     * `null` is a value here, not an error. A transaction being deleted - by this device, a moment
     * ago - is an ordinary thing for the household to have done, and the screen answers it with a
     * sentence rather than with a failure.
     */
    @Query(
        """
        SELECT t.id                   AS id,
               t.type                 AS type,
               t.amount_paise         AS amount_paise,
               t.category_id          AS category_id,
               t.description          AS description,
               t.notes                AS notes,
               t.quantity_thousandths AS quantity_thousandths,
               t.rate_paise           AS rate_paise,
               t.transaction_date     AS transaction_date,
               t.created_at           AS created_at,
               t.updated_at           AS updated_at,
               c.name                 AS category_name,
               c.icon_key             AS category_icon_key
        FROM transactions AS t
        INNER JOIN categories AS c ON c.id = t.category_id
        WHERE t.id = :id
        """,
    )
    fun observeByIdWithCategory(id: Long): Flow<TransactionWithCategory?>

    /**
     * Observes the transactions dated between [from] and [to] **inclusive**, newest first.
     */
    @Query(
        "SELECT * FROM transactions WHERE transaction_date BETWEEN :from AND :to " +
            "ORDER BY transaction_date DESC, id DESC",
    )
    fun observeInDateRange(from: LocalDate, to: LocalDate): Flow<List<TransactionEntity>>

    /**
     * Observes the [type] transactions dated between [from] and [to] **inclusive**, newest first.
     */
    @Query(
        "SELECT * FROM transactions WHERE type = :type " +
            "AND transaction_date BETWEEN :from AND :to " +
            "ORDER BY transaction_date DESC, id DESC",
    )
    fun observeByTypeInDateRange(
        type: TransactionType,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<TransactionEntity>>

    /**
     * Removes one explicitly selected transaction - the row itself is the argument, so there is
     * no way to express "delete everything that matches".
     */
    @Delete
    suspend fun delete(transaction: TransactionEntity)

    /**
     * Removes the transaction with [id] and reports how many rows that actually removed.
     *
     * The count is the point. "Delete this row" is an instruction the household gave from a screen
     * that was showing them a row; whether the database still held that row when the instruction
     * arrived is a fact only the database can settle, and the alternative - read the row, then
     * delete it - would decide it a moment too early. The single statement is also atomic, so two
     * deletions of the same entry cannot both report success.
     *
     * `0` therefore means "there was nothing to delete", which the caller reports honestly rather
     * than as a failure of the deletion itself. Deleting by primary key is as narrow as [delete]:
     * there is still no way to express "delete everything that matches".
     */
    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    // ------------------------------------------------------------------ reporting

    /**
     * What the period [from] to [to] adds up to, in one row: each direction's total and its count.
     *
     * ### One row, always
     *
     * `SUM` over no rows is `NULL`, so every sum is wrapped in `COALESCE(..., 0)`: an empty period
     * answers with four zeroes rather than with no row at all, which is what lets the report treat
     * "nothing happened" as a successful read of nothing rather than as a missing answer.
     *
     * ### One statement, so the four figures agree
     *
     * The two totals and the two counts are computed by the same scan of the same rows. Reading them
     * as four queries would let a write land between them, and a report that said "₹85,500.00 across
     * 28 transactions" where the two numbers came from two different states of the table would be
     * wrong in the one way a financial screen must never be wrong.
     *
     * The direction is compared against the enum's stored text - `'INCOME'` / `'EXPENSE'`, see
     * [com.homefinance.app.data.local.converter.HomeFinanceTypeConverters] - and the amount is only
     * selected when the row is of that direction, so a total can never pick up the other one.
     */
    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN t.type = 'INCOME' THEN t.amount_paise ELSE 0 END), 0)
                   AS income_paise,
               COALESCE(SUM(CASE WHEN t.type = 'EXPENSE' THEN t.amount_paise ELSE 0 END), 0)
                   AS expense_paise,
               COALESCE(SUM(CASE WHEN t.type = 'INCOME' THEN 1 ELSE 0 END), 0)
                   AS income_count,
               COALESCE(SUM(CASE WHEN t.type = 'EXPENSE' THEN 1 ELSE 0 END), 0)
                   AS expense_count
        FROM transactions AS t
        WHERE t.transaction_date BETWEEN :from AND :to
        """,
    )
    fun observeTotals(from: LocalDate, to: LocalDate): Flow<TransactionTotals>

    /**
     * The period's [type] entries grouped by category, largest total first.
     *
     * This is what both breakdown sections of the report are drawn from: the expense breakdown asks
     * for `EXPENSE` and the income breakdown for `INCOME`, and each group carries the category's own
     * name and stored mark so a row can be drawn - and tapped - without a second read.
     *
     * `ORDER BY total_paise DESC` is the ordering the sections are read in, and it is decided here
     * rather than in the UI for the reason every other order in this file is: the database can see
     * every group at once. `name` breaks ties, so two categories that happen to total the same
     * amount cannot swap places between two reads of unchanged data.
     *
     * A category with no rows in the period is simply absent - there is nothing to show for it -
     * and the empty period answers with an empty list rather than with a fabricated row.
     */
    @Query(
        """
        SELECT c.id                AS category_id,
               c.name              AS category_name,
               c.icon_key          AS category_icon_key,
               SUM(t.amount_paise) AS total_paise,
               COUNT(*)            AS transaction_count
        FROM transactions AS t
        INNER JOIN categories AS c ON c.id = t.category_id
        WHERE t.type = :type
          AND t.transaction_date BETWEEN :from AND :to
        GROUP BY c.id
        ORDER BY total_paise DESC, c.name COLLATE NOCASE ASC
        """,
    )
    fun observeCategoryTotals(
        type: TransactionType,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<CategoryTotal>>

    /**
     * One row per calendar day that has any entry in the period, oldest day first.
     *
     * The finest grain the report's charts need; the report folds these days into the weeks or months
     * a bar is drawn for - see [DailyTotal] for why that folding is not done here.
     *
     * A day with nothing recorded is absent rather than present as a zero. Days are a *continuous*
     * series and an absent one is filled in by the caller that knows which days the period contains,
     * so a period of thirty days always draws thirty bars and a month with a quiet week still shows
     * that week as flat rather than skipping it. Storing zeroes here would put the two facts - "no
     * row" and "a row worth nothing" - into the same shape for no gain.
     */
    @Query(
        """
        SELECT t.transaction_date AS day,
               COALESCE(SUM(CASE WHEN t.type = 'INCOME' THEN t.amount_paise ELSE 0 END), 0)
                   AS income_paise,
               COALESCE(SUM(CASE WHEN t.type = 'EXPENSE' THEN t.amount_paise ELSE 0 END), 0)
                   AS expense_paise
        FROM transactions AS t
        WHERE t.transaction_date BETWEEN :from AND :to
        GROUP BY t.transaction_date
        ORDER BY t.transaction_date ASC
        """,
    )
    fun observeDailyTotals(from: LocalDate, to: LocalDate): Flow<List<DailyTotal>>

    /**
     * The period's [categoryId] entries, newest first, each carrying its category's name and mark.
     *
     * The category's own report reads this to list what the figure above it is made of. It is a
     * *listing*, not an aggregate, for the same reason the ledger is: the rows themselves are what
     * the household is looking at.
     *
     * The filter is the category alone, and it needs no direction: a category belongs to exactly one
     * direction, and every transaction filed under it is of that direction.
     */
    @Query(
        """
        SELECT t.id                   AS id,
               t.type                 AS type,
               t.amount_paise         AS amount_paise,
               t.category_id          AS category_id,
               t.description          AS description,
               t.notes                AS notes,
               t.quantity_thousandths AS quantity_thousandths,
               t.rate_paise           AS rate_paise,
               t.transaction_date     AS transaction_date,
               t.created_at           AS created_at,
               t.updated_at           AS updated_at,
               c.name                 AS category_name,
               c.icon_key             AS category_icon_key
        FROM transactions AS t
        INNER JOIN categories AS c ON c.id = t.category_id
        WHERE t.category_id = :categoryId
          AND t.transaction_date BETWEEN :from AND :to
        ORDER BY t.transaction_date DESC, t.id DESC
        """,
    )
    fun observeForCategoryInDateRange(
        categoryId: Long,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<TransactionWithCategory>>

    /**
     * The largest entries in the period, biggest first - the report's "biggest movements" list.
     *
     * `LIMIT` is applied by the database, so a period holding thousands of entries still answers
     * with [limit] rows rather than with all of them. `id DESC` breaks ties, so two entries worth the
     * same amount keep a stable order between reads instead of swapping places.
     *
     * Only `INCOME` and `EXPENSE` rows exist to be selected, so the list needs no direction of its
     * own: the row it draws carries its own type, and the section shows both.
     */
    @Query(
        """
        SELECT t.id                   AS id,
               t.type                 AS type,
               t.amount_paise         AS amount_paise,
               t.category_id          AS category_id,
               t.description          AS description,
               t.notes                AS notes,
               t.quantity_thousandths AS quantity_thousandths,
               t.rate_paise           AS rate_paise,
               t.transaction_date     AS transaction_date,
               t.created_at           AS created_at,
               t.updated_at           AS updated_at,
               c.name                 AS category_name,
               c.icon_key             AS category_icon_key
        FROM transactions AS t
        INNER JOIN categories AS c ON c.id = t.category_id
        WHERE t.transaction_date BETWEEN :from AND :to
        ORDER BY t.amount_paise DESC, t.id DESC
        LIMIT :limit
        """,
    )
    fun observeLargestInDateRange(
        from: LocalDate,
        to: LocalDate,
        limit: Int,
    ): Flow<List<TransactionWithCategory>>
}
