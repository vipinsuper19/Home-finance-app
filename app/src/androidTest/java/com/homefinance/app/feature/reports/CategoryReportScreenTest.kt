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
 * The category drill-down, rendered on a device from a state handed straight to it.
 *
 * The header says which category and which direction it is; the figures below it are the same money
 * the Reports tab showed for that category; the entries are the ones that made the figure up; and each
 * of the three ways out of the screen - back, step to another month, retry a failed read - does what it
 * says.
 */
@RunWith(AndroidJUnit4::class)
class CategoryReportScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    // ------------------------------------------------------------------ header

    @Test
    fun theHeaderNamesTheCategoryItsDirectionAndWhatItCameTo() {
        render(content())

        composeTestRule.onNodeWithText(text(R.string.title_category_details)).assertIsDisplayed()
        assertShown("Groceries")
        assertShown(text(R.string.category_report_type_expense))
        assertShown(CurrencyFormatter.formatInr(34_000L))
        assertShown(text(R.string.category_report_share_expense, 50))
    }

    @Test
    fun anIncomeCategorySaysItCameInAndIsSizedAgainstIncome() {
        render(
            content(
                categoryName = "Salary",
                type = TransactionType.INCOME,
                totalPaise = 90_000L,
                sharePercent = 100,
            ),
        )

        assertShown(text(R.string.category_report_type_income))
        assertShown(text(R.string.category_report_share_income, 100))
        assertNotShown(text(R.string.category_report_share_expense, 100))
    }

    @Test
    fun theStatsNameTheTotalTheCountAndTheAverage() {
        render(content())

        assertShown(text(R.string.category_report_total_spent))
        assertShown(text(R.string.category_report_transactions))
        assertShown("3")
        assertShown(text(R.string.category_report_average))
        assertShown(CurrencyFormatter.formatInr(11_333L))
    }

    // ------------------------------------------------------------------ entries

    @Test
    fun theEntriesAreCountedAndGroupedUnderTheirOwnDay() {
        render(content())

        composeTestRule.onNodeWithText(text(R.string.category_report_transactions_title, 3)).performScrollTo()
            .assertIsDisplayed()
        assertShown(DateFormatter.format(LocalDate.of(2026, 11, 9)))
        assertShown(CurrencyFormatter.formatInr(10_000L))
        assertShown("Milk")
    }

    @Test
    fun tappingAnEntryOpensThatEntry() {
        var opened: Long? = null

        render(content(), onOpenTransaction = { opened = it })

        // "Milk" is the smallest of the three and is not among the largest, so it names exactly one row.
        composeTestRule.onNodeWithText("Milk").performScrollTo().performClick()

        assertEquals(5L, opened)
    }

    // ---------------------------------------------------------- largest amounts

    @Test
    fun theLargestAmountsAreListedWithTheirShareOfTheCategory() {
        render(content())

        composeTestRule.onNodeWithText(text(R.string.category_report_top_amounts)).performScrollTo()
            .assertIsDisplayed()
        assertShown("Bulk shopping")
        assertShown(text(R.string.category_report_share_of_category, 71))
        assertShown(text(R.string.category_report_share_of_category, 18))
    }

    @Test
    fun tappingOneOfTheLargestAmountsOpensThatSameEntry() {
        var opened: Long? = null

        render(content(), onOpenTransaction = { opened = it })

        composeTestRule.onAllNodesWithText("Bulk shopping").onFirst().performScrollTo().performClick()

        assertEquals(2L, opened)
    }

    // -------------------------------------------------------------- other states

    @Test
    fun aCategoryWithAQuietPeriodSaysSoAndDrawsNoChartsOrEntries() {
        render(content(totalPaise = 0L, transactionCount = 0L, averagePaise = null, sharePercent = null, largest = emptyList(), groups = emptyList()))

        // The category is still named and still answered for - zero is this category's answer - and the
        // period is spelled out so there is no doubt which month came to nothing.
        assertShown("Groceries")
        assertShown(
            text(
                R.string.category_report_empty_message,
                DateFormatter.format(PERIOD.from),
                DateFormatter.format(PERIOD.to),
            ),
        )
        // There is no average of no entries, and no chart or list to draw.
        assertNotShown(text(R.string.category_report_average))
        assertNotShown(text(R.string.category_report_top_amounts))
        assertNotShown(text(R.string.category_report_transactions_title, 0))
    }

    @Test
    fun aCategoryTheDatabaseNoLongerHasSaysItIsGoneRatherThanEmpty() {
        render(
            CategoryReportUiState.Missing(
                period = PERIOD,
                canGoForward = false,
                isCurrentMonth = true,
            ),
        )

        assertShown(text(R.string.category_report_missing_title))
        assertShown(text(R.string.category_report_missing_message))
        // Nothing to retry: the row is not slow, it is absent.
        assertNotShown(text(R.string.reports_error_retry))
    }

    @Test
    fun aReadThatFailedOffersToTryAgainAndCarriesNoFigures() {
        var retried = 0

        render(
            CategoryReportUiState.Failed(
                period = PERIOD,
                canGoForward = false,
                isCurrentMonth = true,
                cause = IOException("boom"),
            ),
            onRetry = { retried++ },
        )

        assertShown(text(R.string.reports_error_title))
        composeTestRule.onNodeWithText(text(R.string.reports_error_retry)).performClick()

        assertEquals(1, retried)
        assertNotShown(CurrencyFormatter.formatInr(34_000L))
    }

    // --------------------------------------------------------------- navigation

    @Test
    fun theBackArrowClosesTheReport() {
        var closed = 0

        render(content(), onBack = { closed++ })

        composeTestRule.onNodeWithContentDescription(text(R.string.action_back)).performClick()

        assertEquals(1, closed)
    }

    @Test
    fun theRangeChipsOfferEveryLengthAndReportTheOneChosen() {
        var chosen: ReportRange? = null

        render(content(), onRangeChange = { chosen = it })

        assertShown(text(R.string.reports_range_month))
        assertShown(text(R.string.reports_range_three_months))
        assertShown(text(R.string.reports_range_six_months))

        composeTestRule.onNodeWithText(text(R.string.reports_range_year)).performClick()

        assertEquals(ReportRange.YEAR, chosen)
    }

    // ------------------------------------------------------------------ fixtures

    private fun render(
        state: CategoryReportUiState,
        onBack: () -> Unit = {},
        onPreviousPeriod: () -> Unit = {},
        onNextPeriod: () -> Unit = {},
        onRangeChange: (ReportRange) -> Unit = {},
        onRetry: () -> Unit = {},
        onOpenTransaction: (Long) -> Unit = {},
    ) {
        composeTestRule.setContent {
            HomeFinanceTheme {
                Box {
                    CategoryReportContent(
                        uiState = state,
                        onBack = onBack,
                        onPreviousPeriod = onPreviousPeriod,
                        onNextPeriod = onNextPeriod,
                        onRangeChange = onRangeChange,
                        onRetry = onRetry,
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
        categoryId: Long = 2L,
        categoryName: String = "Groceries",
        categoryIconKey: String? = "cart",
        type: TransactionType = TransactionType.EXPENSE,
        totalPaise: Long = 34_000L,
        transactionCount: Long = 3L,
        averagePaise: Long? = 11_333L,
        sharePercent: Int? = 50,
        trend: List<ReportBucket> = trend(),
        largest: List<TransactionListItem> = listOf(
            item(id = 2L, description = "Bulk shopping", amountPaise = 24_000L, date = LocalDate.of(2026, 11, 2)),
            item(id = 3L, description = "Local shop", amountPaise = 6_000L, date = LocalDate.of(2026, 11, 9)),
        ),
        groups: List<CategoryDayGroup> = listOf(
            CategoryDayGroup(
                date = LocalDate.of(2026, 11, 9),
                totalPaise = 10_000L,
                items = listOf(
                    item(id = 3L, description = "Local shop", amountPaise = 6_000L, date = LocalDate.of(2026, 11, 9)),
                    item(id = 5L, description = "Milk", amountPaise = 4_000L, date = LocalDate.of(2026, 11, 9)),
                ),
            ),
            CategoryDayGroup(
                date = LocalDate.of(2026, 11, 2),
                totalPaise = 24_000L,
                items = listOf(
                    item(id = 2L, description = "Bulk shopping", amountPaise = 24_000L, date = LocalDate.of(2026, 11, 2)),
                ),
            ),
        ),
    ): CategoryReportUiState.Content = CategoryReportUiState.Content(
        period = period,
        canGoForward = canGoForward,
        isCurrentMonth = isCurrentMonth,
        categoryId = categoryId,
        categoryName = categoryName,
        categoryIconKey = categoryIconKey,
        type = type,
        totalPaise = totalPaise,
        transactionCount = transactionCount,
        averagePaise = averagePaise,
        sharePercent = sharePercent,
        trend = trend,
        largest = largest,
        groups = groups,
    )

    private fun item(
        id: Long,
        description: String?,
        amountPaise: Long,
        date: LocalDate,
    ): TransactionListItem = TransactionListItem(
        id = id,
        categoryName = "Groceries",
        description = description,
        transactionDate = date,
        amountPaise = amountPaise,
        type = TransactionType.EXPENSE,
        categoryIconKey = "cart",
    )

    private fun trend(): List<ReportBucket> = listOf(
        ReportBucket(LocalDate.of(2026, 11, 1), ReportBucketKind.WEEK, incomePaise = 0L, expensePaise = 24_000L),
        ReportBucket(LocalDate.of(2026, 11, 8), ReportBucketKind.WEEK, incomePaise = 0L, expensePaise = 10_000L),
        ReportBucket(LocalDate.of(2026, 11, 15), ReportBucketKind.WEEK, incomePaise = 0L, expensePaise = 0L),
        ReportBucket(LocalDate.of(2026, 11, 22), ReportBucketKind.WEEK, incomePaise = 0L, expensePaise = 0L),
        ReportBucket(LocalDate.of(2026, 11, 29), ReportBucketKind.WEEK, incomePaise = 0L, expensePaise = 0L),
    )

    private fun text(id: Int): String = resources.getString(id)

    private fun text(id: Int, vararg formatArgs: Any): String = resources.getString(id, *formatArgs)

    private companion object {

        val NOVEMBER: YearMonth = YearMonth.of(2026, 11)

        val PERIOD: ReportPeriod = ReportPeriod(NOVEMBER, ReportRange.MONTH)
    }
}
