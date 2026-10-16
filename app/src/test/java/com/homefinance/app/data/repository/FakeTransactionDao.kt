package com.homefinance.app.data.repository

import com.homefinance.app.data.local.dao.TransactionDao
import com.homefinance.app.data.local.entity.TransactionEntity
import com.homefinance.app.data.model.TransactionType
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory [TransactionDao] for JVM tests.
 *
 * It sorts with the same rule as the real query - `transaction_date DESC, id DESC` - because the
 * repository tests care about which rows came back, not about SQLite's execution plan. Ordering
 * itself is asserted against a real in-memory Room database in
 * `com.homefinance.app.data.local.TransactionDaoTest`.
 */
class FakeTransactionDao : TransactionDao {

    private val rows: MutableMap<Long, TransactionEntity> = LinkedHashMap()
    private var nextId: Long = 1L

    /** Every entity passed to [insert], in call order, exactly as it arrived. */
    val inserted: MutableList<TransactionEntity> = mutableListOf()

    /** Every entity passed to [update], in call order, exactly as it arrived. */
    val updated: MutableList<TransactionEntity> = mutableListOf()

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
        rows.remove(transaction.id)
    }

    private companion object {
        val NEWEST_FIRST: Comparator<TransactionEntity> =
            compareByDescending<TransactionEntity> { it.transactionDate }.thenByDescending { it.id }
    }
}
