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
        SELECT t.id                 AS id,
               t.type               AS type,
               t.amount_paise       AS amount_paise,
               t.category_id        AS category_id,
               t.description        AS description,
               t.transaction_date   AS transaction_date,
               c.name               AS category_name
        FROM transactions AS t
        INNER JOIN categories AS c ON c.id = t.category_id
        ORDER BY t.transaction_date DESC, t.id DESC
        """,
    )
    fun observeAllWithCategory(): Flow<List<TransactionWithCategory>>

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
}
