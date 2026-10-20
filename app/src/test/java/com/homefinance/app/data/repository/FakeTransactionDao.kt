package com.homefinance.app.data.repository

import com.homefinance.app.data.local.dao.TransactionDao
import com.homefinance.app.data.local.entity.TransactionEntity
import com.homefinance.app.data.local.relation.CategoryTotal
import com.homefinance.app.data.local.relation.DailyTotal
import com.homefinance.app.data.local.relation.TransactionTotals
import com.homefinance.app.data.local.relation.TransactionWithCategory
import com.homefinance.app.data.model.TransactionType
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

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
    /**
     * The marks of those categories, standing in for `categories.icon_key`. Absent means "nobody
     * chose one", which is `null` in the projection - the same thing the real join returns for a
     * category with no stored key.
     */
    private val categoryIconKeys: Map<Long, String> = emptyMap(),
) : TransactionDao {

    private val rows: MutableMap<Long, TransactionEntity> =
        initial.associateByTo(LinkedHashMap()) { it.id }

    private var nextId: Long = (initial.maxOfOrNull { it.id } ?: 0L) + 1L

    /**
     * A counter every write bumps, which only the *report* reads below observe.
     *
     * Room invalidates an observable query when a table it reads is written to, and "the report
     * refreshes when a transaction is recorded, changed or removed" is one of the things the reports
     * have to get right - so a fake that answered once and never again would leave exactly that
     * untestable. The ledger's own reads are deliberately left on [flowOf], so this cannot change the
     * behaviour any test that already covers them depends on.
     */
    private val revision = MutableStateFlow(0)

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
        revision.value += 1
        return id
    }

    override suspend fun update(transaction: TransactionEntity) {
        rows[transaction.id] = transaction
        updated += transaction
        revision.value += 1
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
        revision.value += 1
    }

    override suspend fun deleteById(id: Long): Int {
        deleteFailure?.let { throw it }

        deletedIds += id
        val existed = rows.remove(id) != null
        if (existed) revision.value += 1
        return if (existed) 1 else 0
    }

    /** The period's two totals and two counts, summed exactly as SQLite sums them. */
    override fun observeTotals(from: LocalDate, to: LocalDate): Flow<TransactionTotals> = observed {
        failure?.let { throw it }

        val inRange = rows.values.filter { it.transactionDate in from..to }
        TransactionTotals(
            incomePaise = inRange.filter { it.type == TransactionType.INCOME }.sumOf { it.amountPaise },
            expensePaise = inRange.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountPaise },
            incomeCount = inRange.count { it.type == TransactionType.INCOME }.toLong(),
            expenseCount = inRange.count { it.type == TransactionType.EXPENSE }.toLong(),
        )
    }

    /**
     * The period's [type] entries grouped by category.
     *
     * The ordering copies the query's `ORDER BY total_paise DESC, c.name COLLATE NOCASE ASC`, with
     * `lowercase()` standing in for `COLLATE NOCASE` - which is precisely the part of the real
     * collation this fake cannot promise, and why the ordering itself is asserted against Room in
     * `com.homefinance.app.data.local.TransactionDaoTest`.
     */
    override fun observeCategoryTotals(
        type: TransactionType,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<CategoryTotal>> = observed {
        failure?.let { throw it }

        rows.values
            .filter { it.type == type && it.transactionDate in from..to }
            .groupBy { it.categoryId }
            .mapNotNull { (categoryId, entries) ->
                // The real query INNER JOINs the category, so a row whose category is missing is
                // dropped rather than reported under a name nobody chose.
                val name = categoryNames[categoryId] ?: return@mapNotNull null

                CategoryTotal(
                    categoryId = categoryId,
                    categoryName = name,
                    categoryIconKey = categoryIconKeys[categoryId],
                    totalPaise = entries.sumOf { it.amountPaise },
                    transactionCount = entries.size.toLong(),
                )
            }
            .sortedWith(
                compareByDescending<CategoryTotal> { it.totalPaise }.thenBy { it.categoryName.lowercase() },
            )
    }

    /** One row per day that has any entry, oldest first - the days with nothing are absent. */
    override fun observeDailyTotals(from: LocalDate, to: LocalDate): Flow<List<DailyTotal>> = observed {
        failure?.let { throw it }

        rows.values
            .filter { it.transactionDate in from..to }
            .groupBy { it.transactionDate }
            .map { (date, entries) ->
                DailyTotal(
                    date = date,
                    incomePaise = entries
                        .filter { it.type == TransactionType.INCOME }
                        .sumOf { it.amountPaise },
                    expensePaise = entries
                        .filter { it.type == TransactionType.EXPENSE }
                        .sumOf { it.amountPaise },
                )
            }
            .sortedBy { it.date }
    }

    /** One category's entries in the period, newest first, with their category's name and mark. */
    override fun observeForCategoryInDateRange(
        categoryId: Long,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<TransactionWithCategory>> = observed {
        failure?.let { throw it }

        rows.values
            .filter { it.categoryId == categoryId && it.transactionDate in from..to }
            .sortedWith(NEWEST_FIRST)
            .mapNotNull { row -> withCategory(row) }
    }

    /** The period's biggest entries, biggest first, capped at [limit] exactly as `LIMIT` caps it. */
    override fun observeLargestInDateRange(
        from: LocalDate,
        to: LocalDate,
        limit: Int,
    ): Flow<List<TransactionWithCategory>> = observed {
        failure?.let { throw it }

        rows.values
            .filter { it.transactionDate in from..to }
            .sortedWith(LARGEST_FIRST)
            .take(limit)
            .mapNotNull { row -> withCategory(row) }
    }

    /**
     * Runs [compute] now, and again on every write afterwards.
     *
     * A `map` over [revision] rather than a `flowOf`: the body is re-evaluated per emission, so it
     * reads whatever the rows are at that moment - which is what makes "the report refreshed" a real
     * assertion rather than a claim about the plumbing.
     */
    private fun <T> observed(compute: () -> T): Flow<T> = revision.map { compute() }

    /** Every row, oldest first, for a backup or an export. */
    override suspend fun getAll(): List<TransactionEntity> =
        rows.values.sortedWith(OLDEST_FIRST)

    /**
     * Writes a batch with its ids, `ABORT`-style: a duplicate id throws before anything is stored.
     *
     * The check is deliberate. A restore that carried two rows with one id must fail as a whole, and a
     * fake that overwrote the first would let a broken file look restored.
     */
    override suspend fun insertAll(transactions: List<TransactionEntity>): List<Long> {
        val duplicate = transactions
            .filter { it.id != 0L }
            .groupBy { it.id }
            .entries
            .firstOrNull { it.value.size > 1 }
        check(duplicate == null) { "duplicate transaction id ${duplicate?.key}" }

        return transactions.map { insert(it) }
    }

    override suspend fun deleteAll() {
        deleteFailure?.let { throw it }
        rows.clear()
        revision.value += 1
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
                categoryIconKey = categoryIconKeys[row.categoryId],
            )
        }

    private companion object {
        val NEWEST_FIRST: Comparator<TransactionEntity> =
            compareByDescending<TransactionEntity> { it.transactionDate }.thenByDescending { it.id }

        /** The real `LIMIT` query's `ORDER BY t.amount_paise DESC, t.id DESC`. */
        val LARGEST_FIRST: Comparator<TransactionEntity> =
            compareByDescending<TransactionEntity> { it.amountPaise }.thenByDescending { it.id }

        /** The backup read's `ORDER BY transaction_date ASC, id ASC`. */
        val OLDEST_FIRST: Comparator<TransactionEntity> =
            compareBy<TransactionEntity> { it.transactionDate }.thenBy { it.id }
    }
}
