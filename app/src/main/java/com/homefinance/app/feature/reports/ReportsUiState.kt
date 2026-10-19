package com.homefinance.app.feature.reports

import androidx.compose.runtime.Immutable
import com.homefinance.app.data.local.relation.CategoryTotal
import com.homefinance.app.data.local.relation.DailyTotal
import com.homefinance.app.data.local.relation.TransactionTotals
import com.homefinance.app.data.local.relation.TransactionWithCategory
import com.homefinance.app.feature.transactions.TransactionListItem
import com.homefinance.app.feature.transactions.toListItem
import java.time.LocalDate
import java.time.YearMonth

/**
 * Everything the Reports screen renders, as one of exactly three mutually exclusive snapshots.
 *
 * The same shape the ledger and the recurring tab use, and for the same reason: a data class carrying
 * `isLoading`, `items` and `errorMessage` can represent states that do not exist, and - worse - a
 * failed read could leave last month's figures on screen under this month's heading, presenting stale
 * money as current. Here the three outcomes are an exhaustive `when`, and a failure carries **no
 * figures at all**.
 *
 * ### The period is carried by every state
 *
 * [period] and [canGoForward] are on the *interface*, not only on the loaded state, so the month
 * heading and its two arrows stay on screen while a newly chosen period is being read. Without that,
 * changing the period would replace the whole screen with a spinner - including the control the
 * household would need to change it back.
 *
 * Every figure lives only in [Content], which is the one state that has any business drawing money.
 */
@Immutable
sealed interface ReportsUiState {

    /** The window the screen is scoped to, whatever else is happening. */
    val period: ReportPeriod

    /** True while the window still ends before the current month, so the forward arrow is offered. */
    val canGoForward: Boolean

    /**
     * True while the window is the month that is running now.
     *
     * A month that is still going is not the same fact as a month that has finished, so the heading
     * says which one it is. Carried by every state so the sentence does not blink out for the moment a
     * newly chosen period spends being read.
     */
    val isCurrentMonth: Boolean

    /** The read of [period] has not answered yet. */
    data class Loading(
        override val period: ReportPeriod,
        override val canGoForward: Boolean,
        override val isCurrentMonth: Boolean,
    ) : ReportsUiState

    /**
     * The read of [period] failed.
     *
     * [cause] is kept so a test can prove *which* failure reached the screen; the screen itself never
     * renders it, because a SQLite exception message is not something a household can act on.
     */
    data class Failed(
        override val period: ReportPeriod,
        override val canGoForward: Boolean,
        override val isCurrentMonth: Boolean,
        val cause: Throwable,
    ) : ReportsUiState

    /**
     * The report, read successfully.
     *
     * "Read successfully and the period is empty" is a success, not a failure, and it stays inside
     * [Content] so the empty view and the drawn view can never disagree about which one owns the
     * screen. [isCurrentMonth] is carried so the summary can say that the month is still running
     * rather than letting a partial month be read as a finished one.
     */
    data class Content(
        override val period: ReportPeriod,
        override val canGoForward: Boolean,
        override val isCurrentMonth: Boolean,
        val summary: ReportSummary,
        val buckets: List<ReportBucket>,
        val expenses: List<CategoryShare>,
        val incomes: List<CategoryShare>,
        val trend: List<TrendPoint>,
        val topAmounts: List<TransactionListItem>,
    ) : ReportsUiState {

        /** True when the period holds no entry at all - which is said in words, not drawn as zeroes. */
        val isEmpty: Boolean get() = summary.isEmpty

        /** True when there is money in the period but none of it went out, so there is no breakdown. */
        val hasNoExpenses: Boolean get() = expenses.isEmpty()

        /** True when there is money in the period but none of it came in. */
        val hasNoIncome: Boolean get() = incomes.isEmpty()
    }
}

/**
 * The period's headline figures, with each one's change against the period before it.
 *
 * Every amount is an exact `Long` count of paise, summed by the database. The three change figures are
 * `null` rather than `0` when there is nothing honest to compare against: a percentage needs a
 * denominator that is both positive and *real*, and "↑ 100%" against a month in which nothing
 * happened would be arithmetic dressed as information.
 *
 * @param netChangePercent the change in net cash flow, offered only when the earlier period's net was
 *   itself positive. A rise from `-₹5,000` to `-₹1,000` is an improvement, but it is not a percentage
 *   of anything, and a badge reading "↑ 400%" would be worse than no badge.
 */
@Immutable
data class ReportSummary(
    val incomePaise: Long,
    val expensePaise: Long,
    val netPaise: Long,
    val incomeCount: Long,
    val expenseCount: Long,
    val incomeChangePercent: Int? = null,
    val expenseChangePercent: Int? = null,
    val netChangePercent: Int? = null,
) {

    /** How many entries the period holds altogether. */
    val transactionCount: Long get() = incomeCount + expenseCount

    /** True when the period holds nothing at all. */
    val isEmpty: Boolean get() = transactionCount == 0L

    /**
     * The share of income that was not spent, as a whole percentage, or `null` when it cannot be said.
     *
     * `null` when nothing came in, which is the case the whole screen exists to get right: "saved 100%
     * of nothing" is not a saving, and a rate computed over a zero denominator would be a division by
     * zero dressed up as a fact. A negative rate is real and is shown - a household that spent more
     * than it earned has not saved anything, and rounding that up to `0%` would hide it.
     */
    val savingsRatePercent: Int? get() = if (incomePaise <= 0L) null else percentOf(netPaise, incomePaise)

    companion object {

        /** Builds the figures from the two reads they are made of. */
        internal fun of(
            totals: TransactionTotals,
            previousTotals: TransactionTotals,
        ): ReportSummary = ReportSummary(
            incomePaise = totals.incomePaise,
            expensePaise = totals.expensePaise,
            netPaise = totals.netPaise,
            incomeCount = totals.incomeCount,
            expenseCount = totals.expenseCount,
            incomeChangePercent = percentChange(totals.incomePaise, previousTotals.incomePaise),
            expenseChangePercent = percentChange(totals.expensePaise, previousTotals.expensePaise),
            netChangePercent = percentChange(totals.netPaise, previousTotals.netPaise),
        )
    }
}

/**
 * One bar's worth of a chart: a stretch of time, and what came in and went out during it.
 *
 * Deliberately a *date and a grain* rather than a finished label. The wording under a bar - `1 Nov`
 * for a week, `Nov` for a month - is a translation, so it is produced where `stringResource` and the
 * project's date formatter are reachable, and the state keeps only the two facts the database
 * answered with.
 *
 * @param startDate the first day the bucket covers, which is what its label is derived from.
 * @param kind whether the bucket is a week of a month or a whole month, so the label can be sized to
 *   the space under it.
 */
@Immutable
data class ReportBucket(
    val startDate: LocalDate,
    val kind: ReportBucketKind,
    val incomePaise: Long,
    val expensePaise: Long,
) {

    /** Money in minus money out for this bucket. */
    val netPaise: Long get() = incomePaise - expensePaise

    /** True when nothing at all was recorded in this bucket, so its bars have no height. */
    val isEmpty: Boolean get() = incomePaise == 0L && expensePaise == 0L
}

/** How wide one bar of a chart is: a week of a month, or a whole month. */
enum class ReportBucketKind { WEEK, MONTH }

/** One month of the trend chart. Months with nothing in them are drawn as zeroes, not skipped. */
@Immutable
data class TrendPoint(
    val month: YearMonth,
    val incomePaise: Long,
    val expensePaise: Long,
) {

    /** That month's net cash flow, which may be negative. */
    val netPaise: Long get() = incomePaise - expensePaise
}

/**
 * One category's share of a breakdown: what it came to, what part of the whole that is, and the
 * category a tap on it opens.
 *
 * @param percent the share of the direction's total, as a whole percentage, rounded half-up in
 *   integer arithmetic. The shares are *individually* rounded, so a list of many small categories can
 *   add up to 99% or 101% - which is the honest behaviour of rounded parts, and is stated here rather
 *   than hidden by adjusting a row to make the column total 100.
 */
@Immutable
data class CategoryShare(
    val categoryId: Long,
    val categoryName: String,
    val categoryIconKey: String?,
    val totalPaise: Long,
    val transactionCount: Long,
    val percent: Int,
)

/**
 * One read of the report, before the period has been folded into what a screen draws.
 *
 * Kept separate from [ReportsUiState] because the screen has no business knowing about figures that
 * have not been arranged yet, and because it is the *whole* of what one report consists of: the two
 * totals, the two breakdowns, the days every chart is folded from, and the biggest entries.
 */
@Immutable
internal data class ReportReads(
    val period: ReportPeriod,
    val totals: TransactionTotals,
    val previousTotals: TransactionTotals,
    val expenseBreakdown: List<CategoryTotal>,
    val incomeBreakdown: List<CategoryTotal>,
    val dailyTotals: List<DailyTotal>,
    val largest: List<TransactionWithCategory>,
)

/**
 * Arranges one read into the report the screen draws.
 *
 * Everything here is folding and arithmetic over rows the database already summed: no query and no
 * mutable state, which is what makes every rule about a period - where its boundaries fall, how a
 * month becomes five weeks, what a bucket holds - testable without a device.
 */
internal fun ReportReads.toContent(today: YearMonth): ReportsUiState.Content {
    val summary = ReportSummary.of(totals, previousTotals)
    val inPeriod = dailyTotals.filter { it.date in period.from..period.to }

    return ReportsUiState.Content(
        period = period,
        canGoForward = period.canGoForward(today),
        isCurrentMonth = period.isCurrentMonth(today),
        summary = summary,
        buckets = foldBuckets(period, inPeriod),
        expenses = expenseBreakdown.toShares(summary.expensePaise),
        incomes = incomeBreakdown.toShares(summary.incomePaise),
        trend = foldTrend(period, dailyTotals),
        topAmounts = largest.map { it.toListItem() },
    )
}

/**
 * Folds the period's days into the buckets its chart draws.
 *
 * A month is drawn as weeks from its first day (`1 Nov`, `8 Nov`, `15 Nov`, `22 Nov`, `29 Nov`) and
 * anything longer as whole months, because that is the grain each width is actually read at: a
 * quarter's daily detail is a hedgehog, and a month's monthly totals is a single bar.
 *
 * A bucket with nothing in it is *present* and worth zero rather than missing. Days are a continuous
 * series, so a quiet week is flat, not absent - and a chart that skipped it would misrepresent the
 * shape of the month by silently closing the gap.
 */
internal fun foldBuckets(period: ReportPeriod, dailyInPeriod: List<DailyTotal>): List<ReportBucket> =
    when (period.range) {
        ReportRange.MONTH -> foldWeeks(period, dailyInPeriod)
        else -> foldMonths(period, dailyInPeriod)
    }

/** The weeks of one month, cut from its first day and seven days at a time. */
private fun foldWeeks(period: ReportPeriod, daily: List<DailyTotal>): List<ReportBucket> {
    val lastDay = period.to
    val buckets = mutableListOf<ReportBucket>()

    var start = period.from
    while (start <= lastDay) {
        // The last bucket of a month is short: the 29th to the 31st, or the 29th alone in February.
        val end = minOf(start.plusDays(DAYS_IN_WEEK - 1L), lastDay)
        val days = daily.filter { it.date in start..end }

        buckets += ReportBucket(
            startDate = start,
            kind = ReportBucketKind.WEEK,
            incomePaise = days.sumOf { it.incomePaise },
            expensePaise = days.sumOf { it.expensePaise },
        )

        start = end.plusDays(1L)
    }

    return buckets
}

/** One bucket per month of the period, earliest first - including months with nothing in them. */
private fun foldMonths(period: ReportPeriod, daily: List<DailyTotal>): List<ReportBucket> {
    val months = generateSequence(period.startMonth) { previous ->
        if (previous < period.endMonth) previous.plusMonths(1L) else null
    }

    return months.map { month ->
        val days = daily.filter { YearMonth.from(it.date) == month }

        ReportBucket(
            startDate = month.atDay(1),
            kind = ReportBucketKind.MONTH,
            incomePaise = days.sumOf { it.incomePaise },
            expensePaise = days.sumOf { it.expensePaise },
        )
    }.toList()
}

/**
 * The last [ReportPeriod.TREND_MONTHS] months ending at the period's own end month, oldest first.
 *
 * The window is built from the calendar rather than from the rows, so a month in which nothing
 * happened is drawn as a month that stood still instead of disappearing from the chart and shortening
 * the household's history. Only months the trend window genuinely covers are invented as zeroes, and
 * none of them is in the future relative to the period itself.
 */
internal fun foldTrend(period: ReportPeriod, daily: List<DailyTotal>): List<TrendPoint> =
    (ReportPeriod.TREND_MONTHS - 1 downTo 0).map { monthsBack ->
        val month = period.endMonth.minusMonths(monthsBack.toLong())
        val days = daily.filter { YearMonth.from(it.date) == month }

        TrendPoint(
            month = month,
            incomePaise = days.sumOf { it.incomePaise },
            expensePaise = days.sumOf { it.expensePaise },
        )
    }

/**
 * Turns grouped category totals into the rows a breakdown draws, each with its share of the whole.
 *
 * The order is the query's own - largest first - and nothing here re-sorts it: re-sorting would be a
 * second opinion about an order the database has already given, and it is the database that can see
 * every group at once.
 */
internal fun List<CategoryTotal>.toShares(directionTotalPaise: Long): List<CategoryShare> =
    map { total ->
        CategoryShare(
            categoryId = total.categoryId,
            categoryName = total.categoryName,
            categoryIconKey = total.categoryIconKey,
            totalPaise = total.totalPaise,
            transactionCount = total.transactionCount,
            percent = if (directionTotalPaise <= 0L) {
                0
            } else {
                percentOf(total.totalPaise, directionTotalPaise)
            },
        )
    }

/**
 * `value` as a whole percentage of the positive `base`, rounded half-up.
 *
 * Integer arithmetic throughout, for the same reason money is: the app does not let a figure pass
 * through a binary fraction and then round it back. `base` is required to be positive - every caller
 * has already decided that a share of nothing is not a share at all, and returns `null` or `0`
 * instead of calling this.
 */
internal fun percentOf(value: Long, base: Long): Int {
    require(base > 0L) { "A percentage of $value needs a positive base, but got $base" }

    val magnitude = if (value < 0L) -value else value
    // A guard rather than a hope: amounts are bounded well below this in practice, and a report must
    // not be able to overflow into a negative percentage on a figure somebody typed by accident.
    val scaled = if (magnitude > Long.MAX_VALUE / PERCENT) Long.MAX_VALUE else magnitude * PERCENT
    val rounded = (scaled + base / 2L) / base
    val signed = if (value < 0L) -rounded else rounded

    return when {
        signed > Int.MAX_VALUE.toLong() -> Int.MAX_VALUE
        signed < Int.MIN_VALUE.toLong() -> Int.MIN_VALUE
        else -> signed.toInt()
    }
}

/**
 * How much `current` moved against `previous`, or `null` when there is nothing honest to say.
 *
 * `null` when the earlier figure was zero or negative: a percentage of "we spent nothing last month"
 * has no meaning, and one of "we were down ₹5,000 and are now down ₹1,000" is worse than no badge at
 * all. A real move from a real base is reported in whole percent, rounded half-up.
 */
internal fun percentChange(current: Long, previous: Long): Int? =
    if (previous <= 0L) null else percentOf(current - previous, previous)

private const val DAYS_IN_WEEK = 7L
private const val PERCENT = 100L
