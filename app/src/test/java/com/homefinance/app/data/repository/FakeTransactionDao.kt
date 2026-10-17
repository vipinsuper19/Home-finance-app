package com.homefinance.app.data.repository

import com.homefinance.app.data.local.dao.TransactionDao
import com.homefinance.app.data.local.entity.TransactionEntity
import com.homefinance.app.data.local.relation.TransactionWithCategory
import com.homefinance.app.data.model.TransactionType
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory [TransactionDao] for JVM tests.
 *
 * It sorts with the same rule as the real query - `transaction_date DESC, id DESC` - because the
 * repository tests care about which rows came back, not about SQLite's execution plan. Ordering
 * itself is asserted against a real in-memory Room database in
 * `com.homefinance.app.data.local.TransactionDaoTest`.
 *
 * @param categoryNames the names [observeAllWithCategory] resolves `category_id` against, standing
 *   in for the `categories` table. A row whose category is absent is **dropped**, which is what
 *   the real `INNER JOIN` does; a fake that invented a name instead would hide a broken join. It
 *   deliberately does not model the category *table* - nothing here should let a test believe it
 *   has exercised the real join, which is asserted against Room in `TransactionDaoTest`.
 * @param initial rows to start with, so a test can seed the ledger without calling [insert] and
 *   then having to explain why.
 */
class FakeTransactionDao(
    private val categoryNames: Map<Long, String> = emptyMap(),
    initial: List<TransactionEntity> = emptyList(),
) : TransactionDao {

    private val rows: MutableMap<Long, TransactionEntity> =
        initial.associateByTo(LinkedHashMap()) { it.id }

    private var nextId: Long = (initial.maxOfOrNull { it.id } ?: 0L) + 1L

    /** Every entity passed to [insert], in call order, exactly as it arrived. */
    val inserted: MutableList<TransactionEntity> = mutableListOf()

    /** Every entity passed to [update], in call order, exactly as it arrived. */
    val updated: MutableList<TransactionEntity> = mutableListOf()

    /** Every id passed to [deleteById], in call order. */
    val deletedIds: MutableList<Long> = mutableListOf()

    /**
     * When set, the observable category joins fail with it instead of emitting.
     *
     * Mutable so a test can make an attempt fail, then let a retry succeed - a failure that could
     * not be recovered from would only ever exercise half of the retry path. Assignment and read
     * are single-threaded within a test, so this does not need synchronising.
     */
    var failure: Throwable? = null

    /**
     * When set, removes fail with it instead of removing anything.
     *
     * Mutable for the same reason [failure] is: a test has to be able to make a delete fail and then
     * let the retry succeed. It models a database that cannot be written *at all* - nothing is
     * removed, so a caller that reported a success anyway would be caught.
     */
    var deleteFailure: Throwable? = null

    override suspend fun insert(transaction: TransactionEntity): Long {
        val id = if (transaction.id != 0L) transaction.id else nextId++
        rows[id] = transaction.copy(id = id)
        inserted += transaction
        return id
    }

    override suspend fun update(transaction: TransactionEntity) {
        rows[transaction.id] = transaction
        updated += transaction
    }

    override suspend fun getById(id: Long): TransactionEntity? = rows[id]

    override fun observeAll(): Flow<List<TransactionEntity>> =
        flowOf(rows.values.sortedWith(NEWEST_FIRST))

    override fun observeAllWithCategory(): Flow<List<TransactionWithCategory>> {
        val failure = failure
        if (failure != null) return flow { throw failure }

        return flowOf(
            rows.values.sortedWith(NEWEST_FIRST).mapNotNull { row -> withCategory(row) },
        )
    }

    override fun observeByIdWithCategory(id: Long): Flow<TransactionWithCategory?> {
        val failure = failure
        if (failure != null) return flow { throw failure }

        return flowOf(rows[id]?.let { row -> withCategory(row) })
    }

    override fun observeInDateRange(from: LocalDate, to: LocalDate): Flow<List<TransactionEntity>> =
        flowOf(rows.values.filter { it.transactionDate in from..to }.sortedWith(NEWEST_FIRST))

    override fun observeByTypeInDateRange(
        type: TransactionType,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<TransactionEntity>> = flowOf(
        rows.values
            .filter { it.type == type && it.transactionDate in from..to }
            .sortedWith(NEWEST_FIRST),
    )

    override suspend fun delete(transaction: TransactionEntity) {
        deleteFailure?.let { throw it }

        rows.remove(transaction.id)
    }

    override suspend fun deleteById(id: Long): Int {
        deleteFailure?.let { throw it }

        deletedIds += id
        return if (rows.remove(id) != null) 1 else 0
    }

    /** Joins one row to its category's name the way the real query does, dropping it if absent. */
    private fun withCategory(row: TransactionEntity): TransactionWithCategory? =
        categoryNames[row.categoryId]?.let { name ->
            TransactionWithCategory(
                id = row.id,
                type = row.type,
                amountPaise = row.amountPaise,
                categoryId = row.categoryId,
                description = row.description,
                notes = row.notes,
                quantityThousandths = row.quantityThousandths,
                ratePaise = row.ratePaise,
                transactionDate = row.transactionDate,
                createdAt = row.createdAt,
                updatedAt = row.updatedAt,
                categoryName = name,
            )
        }

    private companion object {
        val NEWEST_FIRST: Comparator<TransactionEntity> =
            compareByDescending<TransactionEntity> { it.transactionDate }.thenByDescending { it.id }
    }
}
