package com.homefinance.app.feature.reports

import androidx.lifecycle.ViewModelStore
import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.data.local.entity.TransactionEntity
import com.homefinance.app.data.model.TransactionType
import com.homefinance.app.data.repository.FakeCategoryDao
import com.homefinance.app.data.repository.FakeTransactionDao
import com.homefinance.app.data.repository.ReportRepository
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The category report's state machine: what one category came to, and what it was made of.
 *
 * Driven over the in-memory ledger with the day pinned, so "this month" and the averages have fixed
 * answers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoryReportViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    private val viewModelStore = ViewModelStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------ states

    @Test
    fun theInitialStateIsLoadingTheWindowItWasOpenedWith() = runTest {
        val viewModel = viewModelWith(categoryId = GROCERIES)

        // Nothing has asked for the report yet, so the screen must be able to say "reading".
        assertTrue(viewModel.uiState.value is CategoryReportUiState.Loading)
        assertEquals(THIS_MONTH, viewModel.uiState.value.period.endMonth)
    }

    @Test
    fun aCategoryWithNothingInThePeriodIsEmptyRatherThanAFailure() = runTest {
        val viewModel = viewModelWith(categoryId = GROCERIES, transactions = emptyList())
        collect(viewModel)

        val content = viewModel.content()

        assertTrue(content.isEmpty)
        assertEquals(0L, content.totalPaise)
        assertEquals(0L, content.transactionCount)
        // There is no average of nothing, and no share of a total that does not exist.
        assertNull(content.averagePaise)
        assertNull(content.sharePercent)
        assertTrue(content.largest.isEmpty())
        assertTrue(content.groups.isEmpty())
    }

    @Test
    fun anIdThatNamesNoCategoryIsMissingRatherThanEmpty() = runTest {
        val viewModel = viewModelWith(categoryId = 9_999L)
        collect(viewModel)

        val state = viewModel.uiState.value

        // "This category does not exist" and "this category had a quiet month" are different answers,
        // and the screen has to be able to give the right one.
        assertTrue("expected a missing state, was $state", state is CategoryReportUiState.Missing)
    }

    @Test
    fun aFailedReadIsAFailureThatCarriesNoFigures() = runTest {
        val dao = ledger()
        val viewModel = viewModelOver(dao, categoryDao = categoryDao())
        collect(viewModel)
        assertEquals(34_000L, viewModel.content().totalPaise)

        dao.failure = IOException("disk gone")
        viewModel.retry()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is CategoryReportUiState.Failed)
        // The heading and its arrows survive, so the household can still step to another month.
        assertEquals(THIS_MONTH, viewModel.uiState.value.period.endMonth)
    }

    @Test
    fun aRetryAfterAFailureReadsAgain() = runTest {
        val dao = ledger()
        val viewModel = viewModelOver(dao, categoryDao = categoryDao())
        collect(viewModel)

        dao.failure = IOException("transient")
        viewModel.retry()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is CategoryReportUiState.Failed)

        dao.failure = null
        viewModel.retry()
        advanceUntilIdle()

        assertEquals(34_000L, viewModel.content().totalPaise)
    }

    // ------------------------------------------------------------------ figures

    @Test
    fun theReportNamesTheCategoryItIsAbout() = runTest {
        val viewModel = viewModelWith(categoryId = GROCERIES)
        collect(viewModel)

        val content = viewModel.content()

        assertEquals("Groceries", content.categoryName)
        assertEquals(GROCERIES, content.categoryId)
        assertEquals("cart", content.categoryIconKey)
        assertEquals(TransactionType.EXPENSE, content.type)
    }

    @Test
    fun theTotalAndTheCountAreReadingsOfTheSameRows() = runTest {
        val viewModel = viewModelWith(categoryId = GROCERIES)
        collect(viewModel)

        val content = viewModel.content()

        // 24,000 + 4,000 + 6,000, over three rows.
        assertEquals(34_000L, content.totalPaise)
        assertEquals(3L, content.transactionCount)
        assertEquals(2, content.groups.size)
    }

    @Test
    fun theAverageIsTheTotalOverTheCountRoundedHalfUp() = runTest {
        val viewModel = viewModelWith(categoryId = GROCERIES)
        collect(viewModel)

        // 34,000 over three entries is 11,333.33...
        assertEquals(11_333L, viewModel.content().averagePaise)
    }

    @Test
    fun anExactHalfOfAPaisaRoundsAwayFromZero() = runTest {
        val viewModel = viewModelWith(
            categoryId = GROCERIES,
            transactions = listOf(
                transaction(id = 1L, categoryId = GROCERIES, amountPaise = 1_000L, date = SOME_DAY),
                transaction(id = 2L, categoryId = GROCERIES, amountPaise = 2_001L, date = SOME_DAY),
            ),
        )
        collect(viewModel)

        // 1,500.5 paise: the average is money, and money is rounded half-up everywhere in this app.
        assertEquals(1_501L, viewModel.content().averagePaise)
    }

    @Test
    fun theShareIsSizedAgainstThePeriodsWholeSpending() = runTest {
        val viewModel = viewModelWith(categoryId = GROCERIES)
        collect(viewModel)

        // 34,000 of the month's 68,000 of spending.
        assertEquals(50, viewModel.content().sharePercent)
    }

    @Test
    fun anIncomeCategoryIsSizedAgainstThePeriodsIncomeNotItsSpending() = runTest {
        val viewModel = viewModelWith(categoryId = SALARY)
        collect(viewModel)

        val content = viewModel.content()

        assertEquals(TransactionType.INCOME, content.type)
        assertEquals(90_000L, content.totalPaise)
        // The whole month's income came from the one category, so its share is all of it.
        assertEquals(100, content.sharePercent)
    }

    @Test
    fun onlyThisCategorysOwnEntriesReachTheReport() = runTest {
        val viewModel = viewModelWith(categoryId = GROCERIES)
        collect(viewModel)

        val content = viewModel.content()

        // Rent's 34,000 is in the same month but not in this report.
        assertEquals(34_000L, content.totalPaise)
        assertEquals(3L, content.transactionCount)
        assertTrue(content.groups.flatMap { it.items }.all { it.categoryName == "Groceries" })
    }

    // ------------------------------------------------------------------- charts

    @Test
    fun theTrendIsThisCategorysOwnWeeksOfTheMonth() = runTest {
        val viewModel = viewModelWith(categoryId = GROCERIES)
        collect(viewModel)

        val trend = viewModel.content().trend

        // A one-month report is bucketed by week, and the 2nd and the 9th fall in different weeks.
        assertEquals(5, trend.size)
        assertTrue(trend.all { it.kind == ReportBucketKind.WEEK })
        assertEquals(24_000L, trend.first().expensePaise)
        assertEquals(10_000L, trend[1].expensePaise)
        // The weeks of the category add up to the figure above them, and to nothing else.
        assertEquals(34_000L, trend.sumOf { it.expensePaise })
    }

    @Test
    fun theLargestAmountsAreThisCategorysBiggestFew() = runTest {
        val viewModel = viewModelWith(categoryId = GROCERIES)
        collect(viewModel)

        val largest = viewModel.content().largest

        assertEquals(listOf(24_000L, 6_000L, 4_000L), largest.map { it.amountPaise })
        assertTrue(largest.all { it.categoryName == "Groceries" })
    }

    @Test
    fun theLargestAmountsAreCappedAtFive() = runTest {
        val viewModel = viewModelWith(
            categoryId = GROCERIES,
            transactions = (1L..8L).map { index ->
                transaction(id = index, categoryId = GROCERIES, amountPaise = index * 100L, date = SOME_DAY)
            },
        )
        collect(viewModel)

        assertEquals(5, viewModel.content().largest.size)
        assertEquals(listOf(800L, 700L, 600L, 500L, 400L), viewModel.content().largest.map { it.amountPaise })
    }

    // -------------------------------------------------------------------- groups

    @Test
    fun theEntriesAreGroupedByDayNewestFirstWithEachDaysOwnTotal() = runTest {
        val viewModel = viewModelWith(categoryId = GROCERIES)
        collect(viewModel)

        val groups = viewModel.content().groups

        assertEquals(listOf(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 2)), groups.map { it.date })
        assertEquals(listOf(10_000L, 24_000L), groups.map { it.totalPaise })
        assertEquals(listOf(2, 1), groups.map { it.items.size })
    }

    @Test
    fun everyRowCarriesEverythingTheListDraws() = runTest {
        val viewModel = viewModelWith(categoryId = GROCERIES)
        collect(viewModel)

        val row = viewModel.content().groups.flatMap { it.items }.single { it.id == 3L }

        assertEquals(3L, row.id)
        assertEquals("Groceries", row.categoryName)
        assertEquals("cart", row.categoryIconKey)
        assertEquals(6_000L, row.amountPaise)
        assertEquals("Local shop", row.description)
        assertEquals(LocalDate.of(2026, 11, 9), row.transactionDate)
    }

    // ---------------------------------------------------------------- reactivity

    @Test
    fun recordingAnotherEntryOfThisCategoryChangesTheReport() = runTest {
        val dao = ledger()
        val viewModel = viewModelOver(dao, categoryDao = categoryDao())
        collect(viewModel)

        dao.insert(transaction(id = 0L, categoryId = GROCERIES, amountPaise = 1_000L, date = SOME_DAY))
        advanceUntilIdle()

        assertEquals(35_000L, viewModel.content().totalPaise)
        assertEquals(4L, viewModel.content().transactionCount)
    }

    @Test
    fun recordingAnEntryOfAnotherCategoryLeavesThisReportAlone() = runTest {
        val dao = ledger()
        val viewModel = viewModelOver(dao, categoryDao = categoryDao())
        collect(viewModel)

        dao.insert(transaction(id = 0L, categoryId = RENT, amountPaise = 5_000L, date = SOME_DAY))
        advanceUntilIdle()

        // The report is about one category: a rent rise is not a grocery bill.
        assertEquals(34_000L, viewModel.content().totalPaise)
        assertEquals(3L, viewModel.content().transactionCount)
    }

    @Test
    fun renamingTheCategoryReachesTheHeading() = runTest {
        val categoryDao = categoryDao()
        val viewModel = viewModelOver(
            dao = FakeTransactionDao(categoryNames = CATEGORY_NAMES, initial = monthlySpending()),
            categoryDao = categoryDao,
        )
        collect(viewModel)
        assertEquals("Groceries", viewModel.content().categoryName)

        categoryDao.update(
            CategoryEntity(
                id = GROCERIES,
                name = "Food",
                type = TransactionType.EXPENSE,
                iconKey = "cart",
                createdAt = AUDIT_INSTANT,
            ),
        )
        advanceUntilIdle()

        assertEquals("Food", viewModel.content().categoryName)
    }

    // ---------------------------------------------------------------- navigation

    @Test
    fun steppingBackReadsTheSameCategoryOverTheEarlierMonth() = runTest {
        val viewModel = viewModelWith(
            categoryId = GROCERIES,
            transactions = listOf(
                transaction(id = 1L, categoryId = GROCERIES, amountPaise = 700L, date = THIS_MONTH.minusMonths(1L).atDay(9)),
            ),
        )
        collect(viewModel)
        assertEquals(0L, viewModel.content().totalPaise)

        viewModel.onPreviousPeriod()
        advanceUntilIdle()

        val content = viewModel.content()
        assertEquals(THIS_MONTH.minusMonths(1L), content.period.endMonth)
        assertEquals(700L, content.totalPaise)
        assertTrue(content.canGoForward)
    }

    @Test
    fun steppingForwardIsRefusedAtThisMonth() = runTest {
        val viewModel = viewModelWith(categoryId = GROCERIES)
        collect(viewModel)

        viewModel.onNextPeriod()
        advanceUntilIdle()

        assertEquals(THIS_MONTH, viewModel.content().period.endMonth)
    }

    @Test
    fun wideningTheRangeKeepsTheMonthTheReportEndsAt() = runTest {
        val viewModel = viewModelWith(categoryId = GROCERIES)
        collect(viewModel)

        viewModel.onRangeChange(ReportRange.THREE_MONTHS)
        advanceUntilIdle()

        val content = viewModel.content()
        assertEquals(THIS_MONTH, content.period.endMonth)
        assertEquals(ReportRange.THREE_MONTHS, content.period.range)
        // A longer range is bucketed by whole months, which is the same rule the tab uses.
        assertEquals(3, content.trend.size)
        assertTrue(content.trend.all { it.kind == ReportBucketKind.MONTH })
        assertFalse(content.isEmpty)
    }

    // ----------------------------------------------------------------- helpers

    /**
     * Starts collecting the state the way the screen does, and runs the reads behind it to their end.
     *
     * The collector stays subscribed in the background scope, so a later write is seen by the same
     * subscription rather than by a fresh read.
     */
    private fun TestScope.collect(viewModel: CategoryReportViewModel) {
        backgroundScope.launch { viewModel.uiState.collect { } }
        advanceUntilIdle()
    }

    private fun CategoryReportViewModel.content(): CategoryReportUiState.Content {
        val state = uiState.value
        assertTrue("expected the report to have loaded, was $state", state is CategoryReportUiState.Content)
        return state as CategoryReportUiState.Content
    }

    private fun viewModelWith(
        categoryId: Long,
        transactions: List<TransactionEntity> = monthlySpending(),
    ): CategoryReportViewModel = viewModelOver(
        categoryId = categoryId,
        dao = ledger(transactions),
        categoryDao = categoryDao(),
    )

    /** The in-memory ledger, wired with the names and marks the real join would have supplied. */
    private fun ledger(transactions: List<TransactionEntity> = monthlySpending()): FakeTransactionDao =
        FakeTransactionDao(
            categoryNames = CATEGORY_NAMES,
            initial = transactions,
            categoryIconKeys = CATEGORY_ICON_KEYS,
        )

    private fun viewModelOver(
        dao: FakeTransactionDao,
        categoryDao: FakeCategoryDao,
        categoryId: Long = GROCERIES,
    ): CategoryReportViewModel = CategoryReportViewModel(
        categoryId = categoryId,
        initialPeriod = ReportPeriod(THIS_MONTH, ReportRange.MONTH),
        reports = ReportRepository(dao, categoryDao),
        today = TODAY,
    ).also { viewModelStore.put("categoryReport", it) }

    /**
     * November for the seeded categories: groceries on the 2nd and the 9th, rent on the 4th, and a
     * salary on the 1st - so every figure the tests assert is one the ledger really adds up to.
     */
    private fun monthlySpending(): List<TransactionEntity> = listOf(
        transaction(id = 1L, categoryId = SALARY, type = TransactionType.INCOME, amountPaise = 90_000L, date = THIS_MONTH.atDay(1)),
        transaction(id = 2L, categoryId = GROCERIES, amountPaise = 24_000L, date = THIS_MONTH.atDay(2)),
        transaction(id = 3L, categoryId = GROCERIES, amountPaise = 6_000L, date = LocalDate.of(2026, 11, 9), description = "Local shop"),
        transaction(id = 4L, categoryId = GROCERIES, amountPaise = 4_000L, date = LocalDate.of(2026, 11, 9)),
        transaction(id = 5L, categoryId = RENT, amountPaise = 34_000L, date = THIS_MONTH.atDay(4)),
    )

    private fun categoryDao(): FakeCategoryDao = FakeCategoryDao(
        initial = listOf(
            CategoryEntity(
                id = SALARY,
                name = "Salary",
                type = TransactionType.INCOME,
                iconKey = "payments",
                createdAt = AUDIT_INSTANT,
            ),
            CategoryEntity(
                id = GROCERIES,
                name = "Groceries",
                type = TransactionType.EXPENSE,
                iconKey = "cart",
                createdAt = AUDIT_INSTANT,
            ),
            CategoryEntity(
                id = RENT,
                name = "Rent",
                type = TransactionType.EXPENSE,
                iconKey = "home",
                createdAt = AUDIT_INSTANT,
            ),
        ),
    )

    private fun transaction(
        id: Long,
        categoryId: Long,
        type: TransactionType = TransactionType.EXPENSE,
        amountPaise: Long = 1_000L,
        date: LocalDate,
        description: String? = null,
    ): TransactionEntity = TransactionEntity(
        id = id,
        type = type,
        amountPaise = amountPaise,
        categoryId = categoryId,
        description = description,
        transactionDate = date,
        createdAt = AUDIT_INSTANT,
        updatedAt = AUDIT_INSTANT,
    )

    private companion object {
        const val SALARY: Long = 1L
        const val GROCERIES: Long = 2L
        const val RENT: Long = 3L

        val CATEGORY_NAMES: Map<Long, String> = mapOf(
            SALARY to "Salary",
            GROCERIES to "Groceries",
            RENT to "Rent",
        )

        /** The marks the categories store, which the listing read joins in beside their names. */
        val CATEGORY_ICON_KEYS: Map<Long, String> = mapOf(
            SALARY to "payments",
            GROCERIES to "cart",
            RENT to "home",
        )

        val AUDIT_INSTANT: Instant = Instant.parse("2026-11-03T06:30:00Z")

        val TODAY: LocalDate = LocalDate.of(2026, 11, 17)

        val THIS_MONTH: YearMonth = YearMonth.from(TODAY)

        val SOME_DAY: LocalDate = LocalDate.of(2026, 11, 9)
    }
}
