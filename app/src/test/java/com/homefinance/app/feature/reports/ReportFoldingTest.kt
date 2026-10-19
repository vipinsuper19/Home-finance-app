package com.homefinance.app.feature.reports

import com.homefinance.app.data.local.relation.CategoryTotal
import com.homefinance.app.data.local.relation.DailyTotal
import com.homefinance.app.data.local.relation.TransactionTotals
import com.homefinance.app.data.local.relation.TransactionWithCategory
import com.homefinance.app.data.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The folding rules: how the days the database answered with become the buckets, the trend points and
 * the breakdown rows a screen draws.
 *
 * Everything here is a pure function of a [ReportReads], so the whole of the report's arrangement is
 * covered without a database, a dispatcher or a device.
 */
class ReportFoldingTest {

    // ----------------------------------------------------------------- buckets

    @Test
    fun aMonthIsCutIntoWeeksRunningFromItsFirstDay() {
        val period = ReportPeriod(endMonth = NOVEMBER, range = ReportRange.MONTH)

        val buckets = foldBuckets(period, dailyInPeriod = emptyList())

        // Five buckets of seven days, with the last one short: 29 + 6 would run past the 30th.
        assertEquals(
            listOf(
                LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 8),
                LocalDate.of(2026, 11, 15),
                LocalDate.of(2026, 11, 22),
                LocalDate.of(2026, 11, 29),
            ),
            buckets.map { it.startDate },
        )
        assertTrue(buckets.all { it.kind == ReportBucketKind.WEEK })
    }

    @Test
    fun everyDayOfThePeriodFallsInExactlyOneBucket() {
        val period = ReportPeriod(endMonth = NOVEMBER, range = ReportRange.MONTH)

        val buckets = foldBuckets(period, dailyInPeriod = emptyList())
        val covered = buckets.flatMap { bucket ->
            val end = minOf(bucket.startDate.plusDays(6L), period.to)
            generateSequence(bucket.startDate) { day -> if (day < end) day.plusDays(1L) else null }.toList()
        }

        // 30 days in November, none of them counted twice and none of them left out.
        assertEquals(30, covered.size)
        assertEquals(covered.size, covered.toSet().size)
        assertEquals(period.from, covered.first())
        assertEquals(period.to, covered.last())
    }

    @Test
    fun aQuietWeekIsPresentAndWorthZeroRatherThanMissing() {
        val period = ReportPeriod(endMonth = NOVEMBER, range = ReportRange.MONTH)

        val buckets = foldBuckets(period, dailyInPeriod = listOf(daily(LocalDate.of(2026, 11, 3), expense = 500L)))

        val quiet = buckets.single { it.startDate == LocalDate.of(2026, 11, 8) }

        // A chart that skipped the week would close the gap and misrepresent the month's shape.
        assertTrue(quiet.isEmpty)
        assertEquals(0L, quiet.incomePaise)
        assertEquals(0L, quiet.expensePaise)
        assertEquals(500L, buckets.first { it.startDate == LocalDate.of(2026, 11, 1) }.expensePaise)
    }

    @Test
    fun aLongerPeriodIsCutIntoWholeMonthsIncludingTheEmptyOnes() {
        val period = ReportPeriod(endMonth = NOVEMBER, range = ReportRange.THREE_MONTHS)

        val buckets = foldBuckets(
            period,
            dailyInPeriod = listOf(daily(LocalDate.of(2026, 11, 3), income = 900L)),
        )

        assertEquals(
            listOf(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1)),
            buckets.map { it.startDate },
        )
        assertTrue(buckets.all { it.kind == ReportBucketKind.MONTH })
        assertEquals(listOf(0L, 0L, 900L), buckets.map { it.incomePaise })
    }

    @Test
    fun daysOutsideThePeriodNeverReachThePeriodChart() {
        val period = ReportPeriod(endMonth = NOVEMBER, range = ReportRange.MONTH)

        val buckets = foldBuckets(
            period,
            // The reader is handed the trend window's days too, so the period's own chart has to
            // filter: October's money is not November's.
            dailyInPeriod = listOf(
                daily(LocalDate.of(2026, 10, 31), income = 5_000L),
                daily(LocalDate.of(2026, 11, 1), income = 700L),
                daily(LocalDate.of(2026, 12, 1), income = 9_000L),
            ),
        )

        assertEquals(700L, buckets.sumOf { it.incomePaise })
    }

    // ------------------------------------------------------------------- trend

    @Test
    fun theTrendIsSixMonthsEndingAtThePeriodsOwnMonth() {
        val period = ReportPeriod(endMonth = NOVEMBER, range = ReportRange.MONTH)

        val trend = foldTrend(
            period,
            daily = listOf(
                daily(LocalDate.of(2026, 6, 30), income = 100L),
                daily(LocalDate.of(2026, 11, 1), income = 900L),
            ),
        )

        assertEquals(
            listOf(6, 7, 8, 9, 10, 11).map { YearMonth.of(2026, it) },
            trend.map { it.month },
        )
        assertEquals(listOf(100L, 0L, 0L, 0L, 0L, 900L), trend.map { it.incomePaise })
    }

    @Test
    fun theTrendNeverReachesForwardOfThePeriodsOwnMonth() {
        val period = ReportPeriod(endMonth = YearMonth.of(2026, 9), range = ReportRange.MONTH)

        val trend = foldTrend(period, daily = listOf(daily(LocalDate.of(2026, 12, 1), income = 999L)))

        // A month the household has not reached is not a month the report may draw.
        assertEquals(YearMonth.of(2026, 9), trend.last().month)
        assertEquals(0L, trend.sumOf { it.incomePaise })
    }

    @Test
    fun aMonthWithNothingInItIsDrawnAsAMonthThatStoodStill() {
        val trend = foldTrend(
            ReportPeriod(endMonth = NOVEMBER, range = ReportRange.MONTH),
            daily = emptyList(),
        )

        assertEquals(ReportPeriod.TREND_MONTHS, trend.size)
        assertTrue(trend.all { it.incomePaise == 0L && it.expensePaise == 0L })
        assertTrue(trend.all { it.netPaise == 0L })
    }

    // ------------------------------------------------------------------ shares

    @Test
    fun eachCategoryIsSizedAgainstTheDirectionsOwnTotal() {
        val shares = listOf(
            categoryTotal(id = 1L, name = "Groceries", total = 24_000L, count = 8L),
            categoryTotal(id = 2L, name = "Rent", total = 12_000L, count = 1L),
        ).toShares(directionTotalPaise = 48_000L)

        assertEquals(listOf(50, 25), shares.map { it.percent })
        // The query's own order is kept: nothing here offers a second opinion about it.
        assertEquals(listOf("Groceries", "Rent"), shares.map { it.categoryName })
        assertEquals(listOf(8L, 1L), shares.map { it.transactionCount })
    }

    @Test
    fun aShareAgainstNoTotalIsZeroRatherThanADivision() {
        val shares = listOf(
            categoryTotal(id = 1L, name = "Groceries", total = 0L, count = 2L),
        ).toShares(directionTotalPaise = 0L)

        assertEquals(listOf(0), shares.map { it.percent })
    }

    // ----------------------------------------------------------------- summary

    @Test
    fun theSummaryAddsUpAndCarriesItsCounts() {
        val summary = ReportSummary.of(
            totals = TransactionTotals(
                incomePaise = 200_000L,
                expensePaise = 74_000L,
                incomeCount = 2L,
                expenseCount = 9L,
            ),
            previousTotals = TransactionTotals.None,
        )

        assertEquals(200_000L, summary.incomePaise)
        assertEquals(74_000L, summary.expensePaise)
        assertEquals(126_000L, summary.netPaise)
        assertEquals(11L, summary.transactionCount)
        assertFalse(summary.isEmpty)
    }

    @Test
    fun spendingMoreThanCameInLeavesANegativeNet() {
        val summary = ReportSummary.of(
            totals = TransactionTotals(
                incomePaise = 50_000L,
                expensePaise = 68_000L,
                incomeCount = 1L,
                expenseCount = 4L,
            ),
            previousTotals = TransactionTotals.None,
        )

        assertEquals(-18_000L, summary.netPaise)
    }

    @Test
    fun thereIsNoSavingsRateWhenNothingCameIn() {
        val summary = ReportSummary.of(
            totals = TransactionTotals(
                incomePaise = 0L,
                expensePaise = 68_000L,
                incomeCount = 0L,
                expenseCount = 4L,
            ),
            previousTotals = TransactionTotals.None,
        )

        // There is no rate of saving on nothing, and a percentage of a zero denominator is not a rate.
        assertNull(summary.savingsRatePercent)
    }

    @Test
    fun theSavingsRateIsTheShareOfIncomeThatStayed() {
        val summary = ReportSummary.of(
            totals = TransactionTotals(
                incomePaise = 200_000L,
                expensePaise = 74_000L,
                incomeCount = 1L,
                expenseCount = 3L,
            ),
            previousTotals = TransactionTotals.None,
        )

        assertEquals(63, summary.savingsRatePercent)
    }

    @Test
    fun aHouseholdThatOverspentHasANegativeSavingsRateRatherThanZero() {
        val summary = ReportSummary.of(
            totals = TransactionTotals(
                incomePaise = 50_000L,
                expensePaise = 68_000L,
                incomeCount = 1L,
                expenseCount = 4L,
            ),
            previousTotals = TransactionTotals.None,
        )

        // Rounding this up to "0%" would hide the month that went backwards.
        assertEquals(-36, summary.savingsRatePercent)
    }

    @Test
    fun aChangeIsMeasuredAgainstThePeriodOfTheSameLengthBefore() {
        val summary = ReportSummary.of(
            totals = TransactionTotals(110_000L, 55_000L, 1L, 2L),
            previousTotals = TransactionTotals(100_000L, 50_000L, 1L, 2L),
        )

        assertEquals(10, summary.incomeChangePercent)
        assertEquals(10, summary.expenseChangePercent)
        assertEquals(10, summary.netChangePercent)
    }

    @Test
    fun thereIsNoChangeBadgeAgainstAQuietEarlierPeriod() {
        val summary = ReportSummary.of(
            totals = TransactionTotals(110_000L, 55_000L, 1L, 2L),
            previousTotals = TransactionTotals.None,
        )

        assertNull(summary.incomeChangePercent)
        assertNull(summary.expenseChangePercent)
        assertNull(summary.netChangePercent)
    }

    // ----------------------------------------------------------------- content

    @Test
    fun oneReadFoldsIntoTheWholeReport() {
        val period = ReportPeriod(endMonth = NOVEMBER, range = ReportRange.MONTH)

        val content = ReportReads(
            period = period,
            totals = TransactionTotals(30_000L, 20_000L, 1L, 4L),
            previousTotals = TransactionTotals(20_000L, 40_000L, 1L, 4L),
            expenseBreakdown = listOf(categoryTotal(2L, "Rent", 12_000L, 1L)),
            incomeBreakdown = listOf(categoryTotal(5L, "Salary", 30_000L, 1L)),
            dailyTotals = listOf(
                daily(LocalDate.of(2026, 11, 2), expense = 20_000L),
                daily(LocalDate.of(2026, 11, 1), income = 30_000L),
            ),
            largest = listOf(
                transaction(id = 9L, type = TransactionType.INCOME, amountPaise = 30_000L, date = LocalDate.of(2026, 11, 1)),
            ),
        ).toContent(NOVEMBER)

        assertEquals(30_000L, content.summary.incomePaise)
        assertEquals(10_000L, content.summary.netPaise)
        // ₹12,000 of the period's ₹20,000 of spending, and all of its income.
        assertEquals(60, content.expenses.single().percent)
        assertEquals(100, content.incomes.single().percent)
        assertEquals(ReportPeriod.TREND_MONTHS, content.trend.size)
        assertEquals(listOf(9L), content.topAmounts.map { it.id })
        // The report is scoped to the month the test says it is, so there is nowhere to step forward.
        assertFalse(content.canGoForward)
        assertTrue(content.isCurrentMonth)
    }

    @Test
    fun aPeriodWithNothingInItIsAContentThatSaysSo() {
        val content = ReportReads(
            period = ReportPeriod(endMonth = NOVEMBER, range = ReportRange.MONTH),
            totals = TransactionTotals.None,
            previousTotals = TransactionTotals.None,
            expenseBreakdown = emptyList(),
            incomeBreakdown = emptyList(),
            dailyTotals = emptyList(),
            largest = emptyList(),
        ).toContent(NOVEMBER)

        assertTrue(content.isEmpty)
        assertTrue(content.hasNoExpenses)
        assertTrue(content.hasNoIncome)
        assertNull(content.summary.savingsRatePercent)
        assertTrue(content.topAmounts.isEmpty())
        // The chart still has its weeks, and they are all worth zero.
        assertEquals(5, content.buckets.size)
    }

    // ---------------------------------------------------------------- helpers

    private fun daily(date: LocalDate, income: Long = 0L, expense: Long = 0L) =
        DailyTotal(date = date, incomePaise = income, expensePaise = expense)

    private fun categoryTotal(id: Long, name: String, total: Long, count: Long) = CategoryTotal(
        categoryId = id,
        categoryName = name,
        categoryIconKey = null,
        totalPaise = total,
        transactionCount = count,
    )

    private fun transaction(
        id: Long,
        type: TransactionType,
        amountPaise: Long,
        date: LocalDate,
    ) = TransactionWithCategory(
        id = id,
        type = type,
        amountPaise = amountPaise,
        categoryId = 5L,
        description = null,
        notes = null,
        quantityThousandths = null,
        ratePaise = null,
        transactionDate = date,
        createdAt = AUDIT_INSTANT,
        updatedAt = AUDIT_INSTANT,
        categoryName = "Salary",
        categoryIconKey = null,
    )

    private companion object {
        val NOVEMBER: YearMonth = YearMonth.of(2026, 11)
        val AUDIT_INSTANT: Instant = Instant.parse("2026-11-01T06:30:00Z")
    }
}

