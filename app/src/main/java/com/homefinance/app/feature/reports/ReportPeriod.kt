package com.homefinance.app.feature.reports

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.homefinance.app.R
import java.time.LocalDate
import java.time.YearMonth

/**
 * How many months a report covers at once.
 *
 * A report is read at one of four widths rather than over an arbitrary pair of dates, and that is a
 * deliberate restriction rather than a missing feature. Every figure on the screen is compared with
 * the *same length* of time immediately before it ("↑ 12% vs Oct"), and every chart is drawn in
 * buckets whose size follows from the width - so a range has to be a length the app can name, and
 * "from the 3rd of March to the 19th of August" is not one. The four are the widths a household
 * actually looks at: this month, a quarter, half a year, a year.
 *
 * The enum's own order is the order the selector offers them, shortest first.
 */
@Immutable
enum class ReportRange(val months: Int, @StringRes val labelRes: Int) {

    /** The month itself - the image's "Monthly". */
    MONTH(months = 1, labelRes = R.string.reports_range_month),

    /** A rolling quarter ending at the selected month. */
    THREE_MONTHS(months = 3, labelRes = R.string.reports_range_three_months),

    /** A rolling half-year ending at the selected month. */
    SIX_MONTHS(months = 6, labelRes = R.string.reports_range_six_months),

    /** A rolling year ending at the selected month. */
    YEAR(months = 12, labelRes = R.string.reports_range_year),
    ;

    companion object {

        /**
         * The range that spans [months] months, or [MONTH] when there is no such range.
         *
         * Used where a range has been through a navigation argument as an ordinary number and has to
         * become a range again. An unknown length falls back to the shortest rather than throwing: a
         * route describes where to go, and a report of this month is a truthful answer to "show me
         * something" where a crash would not be.
         */
        fun fromMonths(months: Int): ReportRange = entries.firstOrNull { it.months == months } ?: MONTH
    }
}

/**
 * The window a report is scoped to: a length, and the month it ends at.
 *
 * ### Why the *end* month is what is stored
 *
 * A household moves through time forwards, so the thing they are looking at is "up to and including
 * November" and the arrows beside it step that boundary. Storing the end means the period a
 * household sees when they step back once is the same length as the one before it, and it means the
 * only comparison that can go wrong - the one against the future - is a question about a single
 * month ([canGoForward]).
 *
 * ### Bounds are inclusive calendar days
 *
 * [from] is the first day of the earliest month in the window and [to] is the last day of the end
 * month, and both are plain [LocalDate]s, exactly as
 * [com.homefinance.app.data.local.entity.TransactionEntity.transactionDate] is stored. A transaction
 * dated either bound belongs to the period; the report never works out a month from a stored string,
 * and no timezone can shift a day across a boundary.
 *
 * ### The trend window
 *
 * [trendFrom]..[trendTo] is the six months ending at the same month as the period, which is what the
 * "recent months" chart compares. It can reach back further than the period itself - a report of
 * November shows June to November in the trend while it totals November alone - and it never reaches
 * forward of the period's own end, so the trend cannot show a month the household has not reached.
 *
 * @param endMonth the last month the report includes.
 * @param range how many months it includes, counted back from [endMonth].
 */
@Immutable
data class ReportPeriod(
    val endMonth: YearMonth,
    val range: ReportRange,
) {

    /** The first day of the period, inclusive. */
    val from: LocalDate get() = endMonth.minusMonths(monthsBack.toLong()).atDay(1)

    /** The last day of the period, inclusive - the end of [endMonth]. */
    val to: LocalDate get() = endMonth.atEndOfMonth()

    /** The first day of the earliest month the trend chart draws. */
    val trendFrom: LocalDate
        get() = endMonth.minusMonths((TREND_MONTHS - 1).toLong()).atDay(1)

    /** The last day of the trend chart's window, which is also the period's own last day. */
    val trendTo: LocalDate get() = to

    /**
     * The earliest day any of the report's reads needs.
     *
     * A period of a year reaches further back than the six-month trend does, and a period of a month
     * does not reach as far. Reading from the earlier of the two means one read serves both charts,
     * and that a bar in the trend chart is a month the database was asked about rather than a month
     * this app assumed was there.
     */
    val earliestReadDate: LocalDate get() = minOf(from, trendFrom)

    /** The earlier month the period starts in, for a label that spans two months. */
    val startMonth: YearMonth get() = endMonth.minusMonths(monthsBack.toLong())

    /**
     * The same length of time immediately before this period - what the change badges compare with.
     *
     * It shares the range and steps the whole window back by its own length, so a quarter is compared
     * with the preceding quarter and never with "the previous month".
     */
    val previousPeriod: ReportPeriod get() = copy(endMonth = endMonth.minusMonths(range.months.toLong()))

    /**
     * The same window one month earlier - what the back arrow does.
     *
     * Stepping a *month* rather than a whole range is what makes the arrows feel like scrolling
     * through time: each tap moves the boundary the household is looking at, not the amount of time
     * they are looking at.
     */
    fun previous(): ReportPeriod = copy(endMonth = endMonth.minusMonths(1L))

    /** The same window one month later - what the forward arrow does, when it is offered at all. */
    fun next(): ReportPeriod = copy(endMonth = endMonth.plusMonths(1L))

    /**
     * True while the window still ends before [today]'s month, so there is later data to show.
     *
     * This is the one rule that keeps the screen honest about the future. A report of a month that has
     * not happened yet would be a page of zeroes wearing a heading that reads like a fact, and the
     * household would have no way to tell "we spent nothing" apart from "this has not happened". The
     * arrow is therefore *absent* rather than disabled-looking-and-doing-nothing, and
     * [ReportsViewModel.onNextPeriod] refuses the step even if it is somehow asked for.
     */
    fun canGoForward(today: YearMonth): Boolean = endMonth < today

    /**
     * True while the window is exactly the month that is now, so "this month" can be said in words.
     *
     * A month that is still running is not the same fact as a month that has finished, and the
     * summary says so rather than letting a household compare a half-finished month with a complete
     * one without knowing.
     */
    fun isCurrentMonth(today: YearMonth): Boolean = endMonth == today

    /** The window with a different length, keeping the month it ends at. */
    fun withRange(range: ReportRange): ReportPeriod = copy(range = range)

    private val monthsBack: Int get() = range.months - 1

    companion object {

        /**
         * How many months the "recent months" chart draws, including the selected one.
         *
         * Six, because that is what fits a small phone's width as labelled columns and what makes the
         * selected month's position in a half-year legible at a glance.
         */
        const val TREND_MONTHS: Int = 6

        /** The report a household lands on: this month, as a month. */
        fun initial(today: LocalDate): ReportPeriod =
            ReportPeriod(endMonth = YearMonth.from(today), range = ReportRange.MONTH)
    }
}
