package com.homefinance.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.homefinance.app.data.local.entity.TransactionEntity
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
 * Nothing here totals money. Monthly summaries are a later task; keeping them out means this
 * layer can be tested without agreeing on what a "month" means yet.
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
               c.name                 AS category_name
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
               c.name                 AS category_name
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
}
