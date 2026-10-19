package com.homefinance.app.data.repository

import com.homefinance.app.data.local.dao.CategoryDao
import com.homefinance.app.data.local.dao.TransactionDao
import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.data.local.relation.CategoryTotal
import com.homefinance.app.data.local.relation.DailyTotal
import com.homefinance.app.data.local.relation.TransactionTotals
import com.homefinance.app.data.local.relation.TransactionWithCategory
import com.homefinance.app.data.model.TransactionType
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/**
 * The sanctioned way for a report to read the household's ledger.
 *
 * ### Why this exists, and what it deliberately is not
 *
 * Reporting is a *reading* of data that already exists, so this class holds no rules of its own: it
 * is a pass-through to the aggregate statements on [TransactionDao], exactly as
 * [TransactionRepository.observeAllWithCategory] is a pass-through to the ledger's join. What it adds
 * is a name for the seam. A report asks for a period, a direction and a category and gets back
 * figures; nothing above this line knows whether those came from one query or from a cursor walked in
 * a loop, and nothing above this line can accidentally write.
 *
 * There is deliberately **no write method here at all** - not one, not even a delete - for the reason
 * the whole design rests on: a report is a picture of what happened. A screen that can move money
 * while also claiming to summarise it is a screen whose figures can be stale by the time they are
 * read, and this class makes that impossible to express rather than merely discouraged.
 *
 * ### Expected money is not actual money
 *
 * Every figure here is summed from `transactions`. A recurring schedule's *occurrence* is an
 * expectation stored in a different table, and it moves no total - so a bill that falls due, or one
 * that is late, cannot appear in a report any more than it can appear in the household's balance.
 * The single way an expectation ever becomes a report figure is by being **paid**, which writes an
 * ordinary transaction through [TransactionRepository] and marks the occurrence; from that moment the
 * money is real, listed once, and summed once.
 *
 * That is not a filter this class applies - there is nothing to filter. It is a consequence of the
 * two tables being separate, and it is what makes "pending recurring occurrences do not affect actual
 * financial totals" a property of the schema rather than a rule somebody has to remember.
 *
 * ### Periods are date bounds
 *
 * Every read takes an inclusive `[from]`..`[to]` pair of [LocalDate]s rather than a `YearMonth` or a
 * "month", because this app computes every calendar rule in Kotlin against [LocalDate] and never
 * against a stored string, and because a report is not always a month: the same read answers a
 * quarter, a year, or the six months a trend chart spans.
 *
 * @param transactionDao the aggregate and listing reads. Reading has no invariants to defend, so
 *   there is nothing for this layer to validate.
 * @param categoryDao used for exactly one thing: the category a drill-down report is *about*, which
 *   has to be readable even in a period that holds none of its transactions.
 */
class ReportRepository(
    private val transactionDao: TransactionDao,
    private val categoryDao: CategoryDao,
) {

    /**
     * What the ledger between [from] and [to] **inclusive** adds up to.
     *
     * The one read behind the report's summary: each direction's total, each direction's number of
     * entries, and - derived from the two totals - the net cash flow.
     */
    fun observeTotals(from: LocalDate, to: LocalDate): Flow<TransactionTotals> =
        transactionDao.observeTotals(from, to)

    /**
     * The period's [type] entries grouped by category, largest first.
     *
     * One read serves both breakdown sections of the report: the expense one asks for
     * [TransactionType.EXPENSE] and the income one for [TransactionType.INCOME].
     */
    fun observeCategoryTotals(
        type: TransactionType,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<CategoryTotal>> = transactionDao.observeCategoryTotals(type, from, to)

    /**
     * One row per day of the period that has any entry, oldest first.
     *
     * The days the charts are folded from; a day with nothing recorded is absent, and the caller that
     * knows which days the period contains fills it in.
     */
    fun observeDailyTotals(from: LocalDate, to: LocalDate): Flow<List<DailyTotal>> =
        transactionDao.observeDailyTotals(from, to)

    /**
     * The period's entries filed under [categoryId], newest first, carrying their category's name.
     *
     * What one category's report lists, and what the same screen derives its day headings, its
     * average and its largest entries from - they are all readings of this one list rather than
     * three more questions to the database.
     */
    fun observeForCategoryInDateRange(
        categoryId: Long,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<TransactionWithCategory>> =
        transactionDao.observeForCategoryInDateRange(categoryId, from, to)

    /**
     * The period's largest entries, biggest first, at most [limit] of them - the report's "biggest
     * movements" list.
     */
    fun observeLargestInDateRange(
        from: LocalDate,
        to: LocalDate,
        limit: Int,
    ): Flow<List<TransactionWithCategory>> = transactionDao.observeLargestInDateRange(from, to, limit)

    /**
     * The category with [categoryId], or `null` when there has never been one.
     *
     * A one-row read of a table that is never hard-deleted; it exists so a category's own report can
     * name itself and draw its own mark even when the period it is scoped to holds none of its
     * transactions.
     */
    fun observeCategory(categoryId: Long): Flow<CategoryEntity?> = categoryDao.observeById(categoryId)
}
