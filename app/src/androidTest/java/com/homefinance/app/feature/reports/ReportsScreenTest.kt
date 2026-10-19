package com.homefinance.app.feature.reports

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homefinance.app.R
import com.homefinance.app.core.ui.theme.HomeFinanceTheme
import com.homefinance.app.core.util.CurrencyFormatter
import com.homefinance.app.core.util.DateFormatter
import com.homefinance.app.data.model.TransactionType
import com.homefinance.app.feature.transactions.TransactionListItem
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Reports dashboard, rendered on a device from a state handed straight to it.
 *
 * `ReportsViewModelTest` pins what the figures *are*; what only a device can show is that they survive
 * being drawn - that every amount is printed to the paise, that a comparison badge appears only when
 * there is something to compare against, that an empty period is said in words rather than drawn as a
 * chart of zeroes, that a failed read carries no figures from the month before, and that a category row
 * is the way into that category's own report.
 *
 * A figure can legitimately appear more than once - the same total is in the summary, in a ring's
 * centre and in a legend - so where that is possible the assertion is "this text is on screen" rather
 * than "this text is on screen exactly once".
 */
@RunWith(AndroidJUnit4::class)
class ReportsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    // ------------------------------------------------------------------ summary

    @Test
    fun everySummaryFigureIsNamedAndPrintedToThePaise() {
        render(content())

        assertShown(text(R.string.reports_summary_income))
        assertShown(text(R.string.reports_summary_expense))
        assertShown(text(R.string.reports_summary_net))

        assertShown(CurrencyFormatter.formatInr(200_000L))
        assertShown(CurrencyFormatter.formatInr(34_000L))
        assertShown(CurrencyFormatter.formatInr(166_000L))
    }

    @Test
    fun theSavingsRateIsStatedAsAShareOfIncomeWhenThereIsIncome() {
        render(content())

        assertShown(text(R.string.reports_savings_rate, 83))
    }

    @Test
    fun aMonthWithNoIncomeStatesNoSavingsRateAtAll() {
        render(
            content(
                summary = summary(incomePaise = 0L, expensePaise = 34_000L, incomeCount = 0L),
                incomes = emptyList(),
            ),
        )

        // A rate of saving on no income is not a rate, and 0% would read as "we saved nothing" when the
        // truth is that nothing came in at all.
        assertNotShown(text(R.string.reports_savings_rate, 0))
    }

    @Test
    fun aMonthThatWentBackwardsSaysHowMuchMoreWasSpentThanCameIn() {
        render(
            content(
                summary = summary(incomePaise = 50_000L, expensePaise = 68_000L),
                topAmounts = emptyList(),
            ),
        )

        // A loss is shown as a loss, and said in words rather than as a negative percentage of income.
        assertShown(CurrencyFormatter.formatInr(-18_000L))
        assertShown(text(R.string.reports_savings_rate_negative, 36))
    }

    // --------------------------------------------------------------- comparison

    @Test
    fun aComparisonBadgeNamesTheMonthItIsMeasuredAgainst() {
        render(
            content(
                summary = summary(
                    incomeChangePercent = 12,
                    expenseChangePercent = -8,
                    netChangePercent = 20,
                ),
            ),
        )

        val againstOctober = text(
            R.string.reports_comparison_vs_month,
            DateFormatter.formatShortMonth(YearMonth.of(2026, 10).atDay(1)),
        )

        assertShown(text(R.string.reports_comparison_up, 12, againstOctober))
        // A fall is printed as a magnitude with a down arrow, so the reader is never asked to subtract a
        // negative.
        assertShown(text(R.string.reports_comparison_down, 8, againstOctober))
    }

    @Test
    fun thereIsNoBadgeAgainstAPeriodThatHadNothingToCompare() {
        render(
            content(
                summary = summary(
                    incomeChangePercent = null,
                    expenseChangePercent = null,
                    netChangePercent = null,
                ),
            ),
        )

        val againstOctober = text(
            R.string.reports_comparison_vs_month,
            DateFormatter.formatShortMonth(YearMonth.of(2026, 10).atDay(1)),
        )

        // "Up 100%" against a month in which nothing happened would be arithmetic dressed as news.
        assertNotShown(text(R.string.reports_comparison_up, 12, againstOctober))
        assertNotShown(text(R.string.reports_comparison_flat, againstOctober))
    }

    // ------------------------------------------------------------------ period

    @Test
    fun theWindowIsNamedAndThisMonthIsSaidToBeStillRunning() {
        render(content())

        assertShown(DateFormatter.formatMonth(NOVEMBER))
        assertShown(text(R.string.reports_current_month_note))
    }

    @Test
    fun theForwardArrowIsAbsentAtThisMonthAndPresentBeforeIt() {
        render(content())

        composeTestRule.onNodeWithContentDescription(text(R.string.reports_period_previous)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(text(R.string.reports_period_next)).assertDoesNotExist()
    }

    @Test
    fun theForwardArrowIsOfferedOnceTheWindowIsNotThisMonth() {
        render(
            content(
                period = ReportPeriod(NOVEMBER.minusMonths(1L), ReportRange.MONTH),
                canGoForward = true,
                isCurrentMonth = false,
            ),
        )

        // Stepping back is what makes the way forward exist, so the arrow must come back with it.
        composeTestRule.onNodeWithContentDescription(text(R.string.reports_period_next)).assertIsDisplayed()
        assertNotShown(text(R.string.reports_current_month_note))
    }

    @Test
    fun eitherArrowMovesTheWindowAndDoesNothingElse() {
        var steppedBack = 0
        var steppedForward = 0

        render(
            content(
                period = ReportPeriod(NOVEMBER.minusMonths(1L), ReportRange.MONTH),
                canGoForward = true,
                isCurrentMonth = false,
            ),
            onPreviousPeriod = { steppedBack++ },
            onNextPeriod = { steppedForward++ },
        )

        composeTestRule.onNodeWithContentDescription(text(R.string.reports_period_previous)).performClick()
        composeTestRule.onNodeWithContentDescription(text(R.string.reports_period_next)).performClick()

        assertEquals(1, steppedBack)
        assertEquals(1, steppedForward)
    }

    @Test
    fun theRangeMenuOffersEveryLengthAndReportsTheOneChosen() {
        var chosen: ReportRange? = null

        render(content(), onRangeChange = { chosen = it })

        composeTestRule.onNodeWithContentDescription(text(R.string.reports_range_label)).performClick()

        assertShown(text(R.string.reports_range_three_months))
        assertShown(text(R.string.reports_range_six_months))
        assertShown(text(R.string.reports_range_year))

        composeTestRule.onNodeWithText(text(R.string.reports_range_year)).performClick()

        assertEquals(ReportRange.YEAR, chosen)
    }

    // ---------------------------------------------------------------- breakdown

    @Test
    fun everyCategoryIsListedWithItsAmountAndItsShare() {
        render(content())

        composeTestRule.onNodeWithText(text(R.string.reports_expense_breakdown_title)).performScrollTo()
            .assertIsDisplayed()
        assertShown("Groceries")
        assertShown("Rent")
        assertShown("70%")
        assertShown("30%")
        assertShown(CurrencyFormatter.formatInr(10_000L))
    }

    @Test
    fun tappingACategoryOpensItsOwnReportOnTheSameWindow() {
        var opened: Pair<ReportPeriod, Long>? = null

        render(content(), onOpenCategory = { period, categoryId -> opened = period to categoryId })

        composeTestRule.onNodeWithText("Groceries").performScrollTo().performClick()

        // The period travels with the tap, so the drill-down opens on the window being read.
        assertEquals(PERIOD, opened!!.first)
        assertEquals(2L, opened!!.second)
    }

    @Test
    fun aDirectionWithNothingInItIsSaidRatherThanDrawnAsAnEmptyRing() {
        render(content(incomes = emptyList(), summary = summary(incomePaise = 0L, incomeCount = 0L)))

        composeTestRule.onNodeWithText(text(R.string.reports_breakdown_no_income)).performScrollTo()
            .assertIsDisplayed()
        assertNotShown(text(R.string.reports_breakdown_no_expenses))
    }

    // -------------------------------------------------------------------- chart

    @Test
    fun aPeriodWithNothingInItSaysSoInsteadOfDrawingAChartOfZeroes() {
        render(content(buckets = buckets().map { it.copy(incomePaise = 0L, expensePaise = 0L) }))

        composeTestRule.onNodeWithText(text(R.string.reports_chart_no_activity)).performScrollTo()
            .assertIsDisplayed()
    }

    // ------------------------------------------------------- biggest movements

    @Test
    fun theBiggestMovementsAreListedAndOpenTheirOwnEntry() {
        var opened: Long? = null

        render(content(), onOpenTransaction = { opened = it })

        composeTestRule.onNodeWithText(text(R.string.reports_top_amounts_title)).performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Fridge").performScrollTo().performClick()

        assertEquals(9L, opened)
    }

    // ------------------------------------------------------------ other states

    @Test
    fun aPeriodWithNothingInItIsSaidInWordsRatherThanShownAsZeroes() {
        render(
            ReportsUiState.Content(
                period = PERIOD,
                canGoForward = false,
                isCurrentMonth = true,
                summary = summary(incomePaise = 0L, expensePaise = 0L, incomeCount = 0L, expenseCount = 0L),
                buckets = buckets().map { it.copy(incomePaise = 0L, expensePaise = 0L) },
                expenses = emptyList(),
                incomes = emptyList(),
                trend = trend().map { it.copy(incomePaise = 0L, expensePaise = 0L) },
                topAmounts = emptyList(),
            ),
        )

        composeTestRule.onNodeWithText(text(R.string.reports_empty_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.reports_empty_message)).assertIsDisplayed()
        // No section is drawn at all, so nothing on screen can be mistaken for this month's money.
        assertNotShown(text(R.string.reports_expense_breakdown_title))
        assertNotShown(CurrencyFormatter.formatInr(0L))
    }

    @Test
    fun aReadThatFailedSaysSoAndOffersToTryAgain() {
        var retried = 0

        render(
            ReportsUiState.Failed(
                period = PERIOD,
                canGoForward = false,
                isCurrentMonth = true,
                cause = IOException("boom"),
            ),
            onRetry = { retried++ },
        )

        composeTestRule.onNodeWithText(text(R.string.reports_error_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.reports_error_message)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.reports_error_retry)).performClick()

        assertEquals(1, retried)
        // A failure carries no figures at all, so last month's net cannot be left on screen under this
        // month's heading - and the window is still steppable.
        assertNotShown(CurrencyFormatter.formatInr(166_000L))
        composeTestRule.onNodeWithContentDescription(text(R.string.reports_period_previous)).assertIsDisplayed()
    }

    @Test
    fun aReadInProgressSaysItIsReading() {
        render(ReportsUiState.Loading(period = PERIOD, canGoForward = false, isCurrentMonth = true))

        composeTestRule.onNodeWithText(text(R.string.reports_loading)).assertIsDisplayed()
    }

    // ------------------------------------------------------------------ fixtures

    private fun render(
        state: ReportsUiState,
        onPreviousPeriod: () -> Unit = {},
        onNextPeriod: () -> Unit = {},
        onRangeChange: (ReportRange) -> Unit = {},
        onRetry: () -> Unit = {},
        onOpenCategory: (ReportPeriod, Long) -> Unit = { _, _ -> },
        onOpenTransaction: (Long) -> Unit = {},
    ) {
        composeTestRule.setContent {
            HomeFinanceTheme {
                Box {
                    ReportsContent(
                        uiState = state,
                        onPreviousPeriod = onPreviousPeriod,
                        onNextPeriod = onNextPeriod,
                        onRangeChange = onRangeChange,
                        onRetry = onRetry,
                        onOpenCategory = onOpenCategory,
                        onOpenTransaction = onOpenTransaction,
                    )
                }
            }
        }
    }

    /** True when some node on the report reads exactly this. */
    private fun assertShown(expected: String) {
        composeTestRule.onAllNodesWithText(expected).onFirst().assertExists()
    }

    private fun assertNotShown(expected: String) {
        assertEquals(
            "expected nothing on the report to read \"$expected\"",
            0,
            composeTestRule.onAllNodesWithText(expected).fetchSemanticsNodes().size,
        )
    }

    private fun content(
        period: ReportPeriod = PERIOD,
        canGoForward: Boolean = false,
        isCurrentMonth: Boolean = true,
        summary: ReportSummary = summary(),
        buckets: List<ReportBucket> = buckets(),
        expenses: List<CategoryShare> = listOf(
            share(categoryId = 2L, name = "Groceries", totalPaise = 24_000L, count = 2L, percent = 70),
            share(categoryId = 3L, name = "Rent", totalPaise = 10_000L, count = 1L, percent = 30),
        ),
        incomes: List<CategoryShare> = listOf(
            share(categoryId = 1L, name = "Salary", totalPaise = 200_000L, count = 1L, percent = 100),
        ),
        trend: List<TrendPoint> = trend(),
        topAmounts: List<TransactionListItem> = listOf(
            TransactionListItem(
                id = 9L,
                categoryName = "Groceries",
                description = "Fridge",
                transactionDate = LocalDate.of(2026, 11, 9),
                amountPaise = 24_000L,
                type = TransactionType.EXPENSE,
                categoryIconKey = "cart",
            ),
        ),
    ): ReportsUiState.Content = ReportsUiState.Content(
        period = period,
        canGoForward = canGoForward,
        isCurrentMonth = isCurrentMonth,
        summary = summary,
        buckets = buckets,
        expenses = expenses,
        incomes = incomes,
        trend = trend,
        topAmounts = topAmounts,
    )

    private fun summary(
        incomePaise: Long = 200_000L,
        expensePaise: Long = 34_000L,
        incomeCount: Long = 1L,
        expenseCount: Long = 3L,
        incomeChangePercent: Int? = null,
        expenseChangePercent: Int? = null,
        netChangePercent: Int? = null,
    ): ReportSummary = ReportSummary(
        incomePaise = incomePaise,
        expensePaise = expensePaise,
        netPaise = incomePaise - expensePaise,
        incomeCount = incomeCount,
        expenseCount = expenseCount,
        incomeChangePercent = incomeChangePercent,
        expenseChangePercent = expenseChangePercent,
        netChangePercent = netChangePercent,
    )

    private fun share(
        categoryId: Long,
        name: String,
        totalPaise: Long,
        count: Long,
        percent: Int,
    ): CategoryShare = CategoryShare(
        categoryId = categoryId,
        categoryName = name,
        categoryIconKey = null,
        totalPaise = totalPaise,
        transactionCount = count,
        percent = percent,
    )

    private fun buckets(): List<ReportBucket> = listOf(
        ReportBucket(LocalDate.of(2026, 11, 1), ReportBucketKind.WEEK, incomePaise = 200_000L, expensePaise = 24_000L),
        ReportBucket(LocalDate.of(2026, 11, 8), ReportBucketKind.WEEK, incomePaise = 0L, expensePaise = 10_000L),
        ReportBucket(LocalDate.of(2026, 11, 15), ReportBucketKind.WEEK, incomePaise = 0L, expensePaise = 0L),
        ReportBucket(LocalDate.of(2026, 11, 22), ReportBucketKind.WEEK, incomePaise = 0L, expensePaise = 0L),
        ReportBucket(LocalDate.of(2026, 11, 29), ReportBucketKind.WEEK, incomePaise = 0L, expensePaise = 0L),
    )

    private fun trend(): List<TrendPoint> = (5 downTo 0).map { monthsBack ->
        TrendPoint(
            month = NOVEMBER.minusMonths(monthsBack.toLong()),
            incomePaise = 200_000L,
            expensePaise = 34_000L,
        )
    }

    private fun text(id: Int): String = resources.getString(id)

    private fun text(id: Int, vararg formatArgs: Any): String = resources.getString(id, *formatArgs)

    private companion object {

        val NOVEMBER: YearMonth = YearMonth.of(2026, 11)

        val PERIOD: ReportPeriod = ReportPeriod(NOVEMBER, ReportRange.MONTH)
    }
}
