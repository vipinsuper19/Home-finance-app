package com.homefinance.app.feature.reports

import com.homefinance.app.navigation.CategoryReportRoute
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window a report covers, and the two rules that keep it honest: where its bounds fall, and where
 * the future is refused.
 *
 * Every test pins the calendar rather than reading the clock, so the suite cannot change its answer on
 * the day a month ends.
 */
class ReportPeriodTest {

    // ------------------------------------------------------------------ bounds

    @Test
    fun aMonthRunsFromItsFirstDayToItsLastDay() {
        val period = ReportPeriod(endMonth = NOVEMBER, range = ReportRange.MONTH)

        assertEquals(LocalDate.of(2026, 11, 1), period.from)
        assertEquals(LocalDate.of(2026, 11, 30), period.to)
    }

    @Test
    fun aMonthEndsOnTheRealLastDayOfFebruary() {
        val period = ReportPeriod(endMonth = YearMonth.of(2028, 2), range = ReportRange.MONTH)

        // A leap year, so the window is the 29th and not a hard-coded 28th.
        assertEquals(LocalDate.of(2028, 2, 29), period.to)
    }

    @Test
    fun aQuarterReachesBackTwoMoreMonths() {
        val period = ReportPeriod(endMonth = NOVEMBER, range = ReportRange.THREE_MONTHS)

        assertEquals(YearMonth.of(2026, 9), period.startMonth)
        assertEquals(LocalDate.of(2026, 9, 1), period.from)
        assertEquals(LocalDate.of(2026, 11, 30), period.to)
    }

    @Test
    fun aYearReachesBackElevenMoreMonthsAcrossTheYearBoundary() {
        val period = ReportPeriod(endMonth = YearMonth.of(2026, 2), range = ReportRange.YEAR)

        assertEquals(LocalDate.of(2025, 3, 1), period.from)
        assertEquals(LocalDate.of(2026, 2, 28), period.to)
    }

    // ------------------------------------------------------------------- trend

    @Test
    fun theTrendWindowIsSixMonthsEndingAtThePeriodMonth() {
        val period = ReportPeriod(endMonth = NOVEMBER, range = ReportRange.MONTH)

        assertEquals(LocalDate.of(2026, 6, 1), period.trendFrom)
        assertEquals(LocalDate.of(2026, 11, 30), period.trendTo)
    }

    @Test
    fun theReadStartsAtTheEarliestOfThePeriodAndTheTrend() {
        val month = ReportPeriod(endMonth = NOVEMBER, range = ReportRange.MONTH)
        val year = ReportPeriod(endMonth = NOVEMBER, range = ReportRange.YEAR)

        // A month does not reach as far back as the trend does...
        assertEquals(month.trendFrom, month.earliestReadDate)
        // ...and a year reaches further, so one read still serves both charts.
        assertEquals(year.from, year.earliestReadDate)
        assertTrue(year.earliestReadDate < year.trendFrom)
    }

    // ------------------------------------------------------------- comparisons

    @Test
    fun thePreviousPeriodIsTheSameLengthImmediatelyBefore() {
        val quarter = ReportPeriod(endMonth = NOVEMBER, range = ReportRange.THREE_MONTHS)

        val previous = quarter.previousPeriod

        assertEquals(YearMonth.of(2026, 8), previous.endMonth)
        assertEquals(ReportRange.THREE_MONTHS, previous.range)
        // Aug..Oct, immediately before Sep..Nov: a quarter is never compared with a single month.
        assertEquals(LocalDate.of(2026, 6, 1), previous.from)
        assertEquals(LocalDate.of(2026, 8, 31), previous.to)
    }

    // ------------------------------------------------------------- navigation

    @Test
    fun steppingBackMovesTheBoundaryAMonthWithoutChangingTheLength() {
        val period = ReportPeriod(endMonth = NOVEMBER, range = ReportRange.SIX_MONTHS)

        val stepped = period.previous()

        assertEquals(YearMonth.of(2026, 10), stepped.endMonth)
        assertEquals(ReportRange.SIX_MONTHS, stepped.range)
    }

    @Test
    fun steppingForwardMovesTheBoundaryAMonthWithoutChangingTheLength() {
        val period = ReportPeriod(endMonth = YearMonth.of(2026, 9), range = ReportRange.THREE_MONTHS)

        assertEquals(YearMonth.of(2026, 10), period.next().endMonth)
        assertEquals(ReportRange.THREE_MONTHS, period.next().range)
    }

    @Test
    fun theArrowsStepBackAndForwardIsAlwaysTheInverse() {
        val period = ReportPeriod(endMonth = NOVEMBER, range = ReportRange.MONTH)

        assertEquals(period, period.previous().next())
        assertEquals(period, period.next().previous())
    }

    @Test
    fun forwardIsOfferedBeforeThisMonthAndRefusedAtIt() {
        val today = YearMonth.of(2026, 11)

        assertTrue(ReportPeriod(YearMonth.of(2026, 10), ReportRange.MONTH).canGoForward(today))
        assertFalse(ReportPeriod(today, ReportRange.MONTH).canGoForward(today))
    }

    @Test
    fun theCurrentMonthIsTheMonthThatIsRunningNow() {
        val today = YearMonth.of(2026, 11)

        assertTrue(ReportPeriod(today, ReportRange.MONTH).isCurrentMonth(today))
        assertFalse(ReportPeriod(YearMonth.of(2026, 10), ReportRange.MONTH).isCurrentMonth(today))
    }

    @Test
    fun changingTheLengthKeepsTheMonthTheReportEndsAt() {
        val period = ReportPeriod(endMonth = NOVEMBER, range = ReportRange.MONTH)

        val widened = period.withRange(ReportRange.YEAR)

        assertEquals(NOVEMBER, widened.endMonth)
        assertEquals(ReportRange.YEAR, widened.range)
    }

    // ------------------------------------------------------------------ ranges

    @Test
    fun theReportOpensOnThisMonthReadAsAMonth() {
        val period = ReportPeriod.initial(LocalDate.of(2026, 11, 17))

        assertEquals(NOVEMBER, period.endMonth)
        assertEquals(ReportRange.MONTH, period.range)
    }

    @Test
    fun aLengthThatNamesNoRangeFallsBackToTheShortest() {
        // A route carries the length as a plain number, so an unknown one must answer rather than
        // throw: "this month" is at least true.
        assertEquals(ReportRange.MONTH, ReportRange.fromMonths(0))
        assertEquals(ReportRange.MONTH, ReportRange.fromMonths(2))
        assertEquals(ReportRange.THREE_MONTHS, ReportRange.fromMonths(3))
        assertEquals(ReportRange.SIX_MONTHS, ReportRange.fromMonths(6))
        assertEquals(ReportRange.YEAR, ReportRange.fromMonths(12))
    }

    @Test
    fun everyRangeIsOfferedShortestFirstWithItsOwnLabel() {
        assertEquals(listOf(1, 3, 6, 12), ReportRange.entries.map { it.months })
    }

    @Test
    fun theDrillDownsRouteCarriesTheWindowItWasOpenedFrom() {
        val period = ReportPeriod(endMonth = YearMonth.of(2026, 1), range = ReportRange.SIX_MONTHS)

        // Exactly the conversion `HomeFinanceNavHost` performs when a category row is tapped, and the
        // one the drill-down undoes when it opens. A window that did not survive it would show a
        // different total from the row that was tapped, with nothing on screen to explain why.
        val route = CategoryReportRoute(
            categoryId = 7L,
            rangeMonths = period.range.months,
            endYear = period.endMonth.year,
            endMonth = period.endMonth.monthValue,
        )
        val reopened = ReportPeriod(
            endMonth = YearMonth.of(route.endYear, route.endMonth),
            range = ReportRange.fromMonths(route.rangeMonths),
        )

        assertEquals(period, reopened)
        assertEquals(7L, route.categoryId)
        // A six-month window ending in January reaches back into the previous year, which is what the
        // route's separate year and month fields have to carry between them.
        assertEquals(LocalDate.of(2025, 8, 1), reopened.from)
        assertEquals(LocalDate.of(2026, 1, 31), reopened.to)
    }

    private companion object {
        val NOVEMBER: YearMonth = YearMonth.of(2026, 11)
    }
}
