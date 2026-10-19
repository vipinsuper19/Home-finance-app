package com.homefinance.app.feature.reports

import androidx.compose.runtime.Immutable
import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.data.local.relation.DailyTotal
import com.homefinance.app.data.local.relation.TransactionTotals
import com.homefinance.app.data.local.relation.TransactionWithCategory
import com.homefinance.app.data.model.TransactionType
import com.homefinance.app.feature.transactions.TransactionListItem
import com.homefinance.app.feature.transactions.toListItem
import java.time.LocalDate
import java.time.YearMonth

/**
 * Everything one category's report renders, as one of exactly five mutually exclusive snapshots.
 *
 * The same shape as the Reports tab's state, and for the same reason: the three outcomes of a read -
 * loading, failed, loaded - are an exhaustive `when` rather than a set of flags, and a failure carries
 * no figures at all. Two states are added here that the tab does not need, because a drill-down can be
 * *about* something that is not there:
 *
 *  - [Missing] - the category id names nothing. The app never deletes a category (history has to keep
 *    its name), so this can only happen for an id that never existed, but the screen still has to have
 *    an answer, and "no such category" is a different answer from "no transactions".
 *  - an empty [Content] - the category exists and simply has nothing in this period, which is drawn as
 *    a figure of `₹0.00` with a sentence beside it rather than as a failure.
 *
 * The period is carried by every state, so the heading and its arrows survive a reload exactly as they
 * do on the Reports tab.
 */
@Immutable
sealed interface CategoryReportUiState {

    /** The window the report is scoped to, whatever else is happening. */
    val period: ReportPeriod

    /** True while the window still ends before the current month, so the forward arrow is offered. */
    val canGoForward: Boolean

    /** True while the window is the month that is running now. */
    val isCurrentMonth: Boolean

    /** The read has not answered yet. */
    data class Loading(
        override val period: ReportPeriod,
        override val canGoForward: Boolean,
        override val isCurrentMonth: Boolean,
    ) : CategoryReportUiState

    /** The read failed. `cause` is kept for tests; it is never rendered. */
    data class Failed(
        override val period: ReportPeriod,
        override val canGoForward: Boolean,
        override val isCurrentMonth: Boolean,
        val cause: Throwable,
    ) : CategoryReportUiState

    /** There is no category with the requested id. */
    data class Missing(
        override val period: ReportPeriod,
        override val canGoForward: Boolean,
        override val isCurrentMonth: Boolean,
    ) : CategoryReportUiState

    /**
     * The category, and what it did during the period.
     *
     * @param type the direction the category belongs to, which is what every amount on this screen is
     *   coloured by and what decides whether the heading says "expenses" or "income".
     * @param totalPaise the summed amount, an exact `Long` count of paise, `0` when the period is empty.
     * @param averagePaise what one entry came to on average, or `null` when there are no entries - there
     *   is no average of nothing, and `₹0.00` would be a claim rather than an absence.
     * @param sharePercent this category's part of the period's total for its direction, or `null` when
     *   that total is zero, in which case there is no share to state.
     * @param trend the period's bucket totals for this category alone, oldest bucket first.
     * @param largest the biggest few entries, biggest first, which is what the breakdown grid in the
     *   design becomes: this app has no subcategories, so the ranked bars are the largest entries in
     *   the category rather than an invented taxonomy.
     * @param groups every entry in the period, newest day first, each day carrying its own total.
     */
    data class Content(
        override val period: ReportPeriod,
        override val canGoForward: Boolean,
        override val isCurrentMonth: Boolean,
        val categoryId: Long,
        val categoryName: String,
        val categoryIconKey: String?,
        val type: TransactionType,
        val totalPaise: Long,
        val transactionCount: Long,
        val averagePaise: Long?,
        val sharePercent: Int?,
        val trend: List<ReportBucket>,
        val largest: List<TransactionListItem>,
        val groups: List<CategoryDayGroup>,
    ) : CategoryReportUiState {

        /** True when the period holds nothing for this category. */
        val isEmpty: Boolean get() = transactionCount == 0L
    }
}

/**
 * One day's entries in a category, under the heading that names it.
 *
 * Carries the day's own total because a category's report is read as "how much, and when": the total
 * under the heading is the figure the day's rows are the explanation of, and computing it here keeps
 * the list from having to add up its own rows while drawing.
 */
@Immutable
data class CategoryDayGroup(
    val date: LocalDate,
    val totalPaise: Long,
    val items: List<TransactionListItem>,
)

/**
 * One read of a category's report, before the period has been folded into what a screen draws.
 *
 * Small on purpose: three reads, and everything else on the screen is folded from them. The entries
 * themselves are read because the screen *lists* them - the total, the count, the average, the trend
 * and the largest entries are all readings of that one list rather than five more questions to the
 * database. Summing the rows the screen is already drawing cannot put a figure on screen that is not
 * in the list.
 */
@Immutable
internal data class CategoryReads(
    val period: ReportPeriod,
    val category: CategoryEntity?,
    val directionTotals: TransactionTotals,
    val entries: List<TransactionWithCategory>,
)

/** How many of a category's largest entries the drill-down names. */
private const val LARGEST_LIMIT = 5

/**
 * Arranges one read into the report the screen draws.
 *
 * Folding and integer arithmetic over rows the database returned, with no query and no mutable state,
 * so every rule about the drill-down - the average, the share, the day totals, what "largest" means -
 * is testable without a device.
 */
internal fun CategoryReads.toContent(today: YearMonth): CategoryReportUiState {
    val category = category ?: return CategoryReportUiState.Missing(
        period = period,
        canGoForward = period.canGoForward(today),
        isCurrentMonth = period.isCurrentMonth(today),
    )

    val items = entries.map { it.toListItem() }
    // An exact sum of the rows that are drawn: no rounding can enter between a row and the total it is
    // part of, and the number under the heading is always the number the rows add up to.
    val total = items.sumOf { it.amountPaise }
    val count = items.size.toLong()
    val directionTotal = if (category.type == TransactionType.INCOME) {
        directionTotals.incomePaise
    } else {
        directionTotals.expensePaise
    }

    return CategoryReportUiState.Content(
        period = period,
        canGoForward = period.canGoForward(today),
        isCurrentMonth = period.isCurrentMonth(today),
        categoryId = category.id,
        categoryName = category.name,
        categoryIconKey = category.iconKey,
        type = category.type,
        totalPaise = total,
        transactionCount = count,
        averagePaise = if (count == 0L) null else (total + count / 2L) / count,
        sharePercent = if (directionTotal <= 0L) null else percentOf(total, directionTotal),
        trend = foldBuckets(period, items.toDailyTotals()),
        largest = items
            .sortedWith(compareByDescending<TransactionListItem> { it.amountPaise }.thenByDescending { it.id })
            .take(LARGEST_LIMIT),
        groups = items
            // The rows arrive newest day first, and `groupBy` keeps the order it met each day in, so
            // the headings come out newest first without a second sort.
            .groupBy { it.transactionDate }
            .map { (date, dayItems) ->
                CategoryDayGroup(
                    date = date,
                    totalPaise = dayItems.sumOf { it.amountPaise },
                    items = dayItems,
                )
            },
    )
}

/**
 * The category's rows as daily sums, so a month of one category can be folded into weeks by the same
 * function the Reports tab folds a whole month with.
 *
 * A category belongs to exactly one direction, so one of the two totals is always zero - which is not a
 * special case here, it is simply what the rows say.
 */
private fun List<TransactionListItem>.toDailyTotals(): List<DailyTotal> =
    groupBy { it.transactionDate }
        .map { (date, dayItems) ->
            DailyTotal(
                date = date,
                incomePaise = dayItems.filter { it.type == TransactionType.INCOME }.sumOf { it.amountPaise },
                expensePaise = dayItems.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountPaise },
            )
        }
