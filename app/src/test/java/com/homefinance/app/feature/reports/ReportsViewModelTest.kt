package com.homefinance.app.feature.reports

import androidx.lifecycle.ViewModelStore
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
 * The Reports tab's state machine, driven over the in-memory ledger.
 *
 * Every test pins the day the screen believes it is, so "this month", "the previous period" and the
 * forward arrow have fixed answers, and none of them depends on when the suite runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    /**
     * Holds the view models so [ViewModelStore.clear] can run their `onCleared`.
     *
     * Without it `viewModelScope` would outlive the test, leaving the `WhileSubscribed` sharing
     * coroutine parked on a five-second timer.
     */
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
    fun theInitialStateIsLoadingThisMonthAsAMonth() = runTest {
        val viewModel = viewModelWith()

        val state = viewModel.uiState.value

        assertTrue("expected a loading state, was $state", state is ReportsUiState.Loading)
        assertEquals(THIS_MONTH, state.period.endMonth)
        assertEquals(ReportRange.MONTH, state.period.range)
        assertTrue(state.isCurrentMonth)
        assertFalse(state.canGoForward)
    }

    @Test
    fun aQuietMonthIsASuccessfulReadOfNothingRatherThanAFailure() = runTest {
        val viewModel = viewModelWith()
        collect(viewModel)

        val content = viewModel.content()

        assertTrue(content.isEmpty)
        assertEquals(0L, content.summary.incomePaise)
        assertEquals(0L, content.summary.expensePaise)
        assertEquals(0L, content.summary.netPaise)
        assertNull(content.summary.savingsRatePercent)
        assertTrue(content.expenses.isEmpty())
        assertTrue(content.incomes.isEmpty())
        assertTrue(content.topAmounts.isEmpty())
    }

    @Test
    fun aFailedReadIsAFailureCarryingNoFiguresAtAll() = runTest {
        val dao = FakeTransactionDao()
        val viewModel = viewModelOver(dao)
        collect(viewModel)

        // A read that breaks after the first answer must not leave the last figures on screen under
        // this month's heading.
        dao.failure = IOException("disk gone")
        viewModel.retry()
        advanceUntilIdle()

        val state = viewModel.uiState.value

        assertTrue("expected a failure, was $state", state is ReportsUiState.Failed)
        assertEquals("disk gone", (state as ReportsUiState.Failed).cause.message)
        // The heading and its arrows survive the failure, so the household can still step away.
        assertEquals(THIS_MONTH, state.period.endMonth)
    }

    @Test
    fun aRetryAfterAFailureReadsThePeriodAgain() = runTest {
        val dao = FakeTransactionDao()
        val viewModel = viewModelOver(dao)
        collect(viewModel)

        dao.failure = IOException("transient")
        viewModel.onRangeChange(ReportRange.THREE_MONTHS)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is ReportsUiState.Failed)

        // A failure a household cannot step out of would only exercise half of the recovery path.
        dao.failure = null
        viewModel.onRangeChange(ReportRange.MONTH)
        viewModel.retry()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is ReportsUiState.Content)
    }

    // ------------------------------------------------------------------ totals

    @Test
    fun incomeAndExpensesAreTotalledSeparatelyAndNetIsTheirDifference() = runTest {
        val viewModel = viewModelWith(
            transactions = listOf(
                transaction(id = 1L, categoryId = SALARY, type = TransactionType.INCOME, amountPaise = 200_000L, date = SOME_DAY),
                transaction(id = 2L, categoryId = GROCERIES, amountPaise = 50_000L, date = SOME_DAY),
                transaction(id = 3L, categoryId = RENT, amountPaise = 24_000L, date = SOME_DAY),
            ),
        )
        collect(viewModel)

        val summary = viewModel.content().summary

        assertEquals(200_000L, summary.incomePaise)
        assertEquals(74_000L, summary.expensePaise)
        assertEquals(126_000L, summary.netPaise)
        assertEquals(1L, summary.incomeCount)
        assertEquals(2L, summary.expenseCount)
        assertEquals(3L, summary.transactionCount)
        assertEquals(63, summary.savingsRatePercent)
    }

    @Test
    fun spendingMoreThanCameInLeavesANegativeNetCashFlow() = runTest {
        val viewModel = viewModelWith(
            transactions = listOf(
                transaction(id = 1L, categoryId = SALARY, type = TransactionType.INCOME, amountPaise = 50_000L, date = SOME_DAY),
                transaction(id = 2L, categoryId = RENT, amountPaise = 68_000L, date = SOME_DAY),
            ),
        )
        collect(viewModel)

        val summary = viewModel.content().summary

        assertEquals(-18_000L, summary.netPaise)
        // Shown as a real negative rate rather than rounded up to a zero that hides the month.
        assertEquals(-36, summary.savingsRatePercent)
    }

    @Test
    fun aMonthWithNoIncomeHasNoSavingsRate() = runTest {
        val viewModel = viewModelWith(
            transactions = listOf(transaction(id = 1L, categoryId = RENT, amountPaise = 24_000L, date = SOME_DAY)),
        )
        collect(viewModel)

        assertNull(viewModel.content().summary.savingsRatePercent)
    }

    // -------------------------------------------------------------- boundaries

    @Test
    fun bothEndsOfTheMonthBelongToItAndTheDaysOutsideDoNot() = runTest {
        val viewModel = viewModelWith(
            transactions = listOf(
                transaction(id = 1L, categoryId = RENT, amountPaise = 100L, date = THIS_MONTH.atDay(1)),
                transaction(id = 2L, categoryId = RENT, amountPaise = 1_000L, date = THIS_MONTH.atEndOfMonth()),
                transaction(id = 3L, categoryId = RENT, amountPaise = 100_000L, date = THIS_MONTH.atDay(1).minusDays(1L)),
                transaction(id = 4L, categoryId = RENT, amountPaise = 1_000_000L, date = THIS_MONTH.atEndOfMonth().plusDays(1L)),
            ),
        )
        collect(viewModel)

        // Inclusive at both ends, exclusive outside them: the only rule that decides where a month
        // starts and stops, asserted with money large enough to be unmistakable.
        assertEquals(1_100L, viewModel.content().summary.expensePaise)
        assertEquals(2L, viewModel.content().summary.expenseCount)
    }

    @Test
    fun aLongerRangeReadsTheWholeRangeInclusiveOfItsFirstMonth() = runTest {
        val viewModel = viewModelWith(
            transactions = listOf(
                transaction(id = 1L, categoryId = RENT, amountPaise = 500L, date = THIS_MONTH.minusMonths(2L).atDay(1)),
                transaction(id = 2L, categoryId = RENT, amountPaise = 700L, date = THIS_MONTH.minusMonths(3L).atEndOfMonth()),
            ),
        )
        collect(viewModel)
        viewModel.onRangeChange(ReportRange.THREE_MONTHS)
        advanceUntilIdle()

        // Three months back from November is September, and August is outside it.
        assertEquals(YearMonth.of(2026, 9), YearMonth.from(viewModel.content().period.from))
        assertEquals(500L, viewModel.content().summary.expensePaise)
    }

    // ---------------------------------------------------------------- breakdown

    @Test
    fun theExpenseBreakdownIsSizedAgainstThePeriodsOwnSpendingLargestFirst() = runTest {
        val viewModel = viewModelWith(
            transactions = listOf(
                transaction(id = 1L, categoryId = GROCERIES, amountPaise = 24_000L, date = SOME_DAY),
                transaction(id = 2L, categoryId = RENT, amountPaise = 12_000L, date = SOME_DAY),
                transaction(id = 3L, categoryId = GROCERIES, amountPaise = 4_000L, date = SOME_DAY),
            ),
        )
        collect(viewModel)

        val expenses = viewModel.content().expenses

        assertEquals(listOf("Groceries", "Rent"), expenses.map { it.categoryName })
        assertEquals(listOf(28_000L, 12_000L), expenses.map { it.totalPaise })
        assertEquals(listOf(70, 30), expenses.map { it.percent })
        assertEquals(listOf(2L, 1L), expenses.map { it.transactionCount })
        assertEquals(listOf(GROCERIES, RENT), expenses.map { it.categoryId })
    }

    @Test
    fun incomeIsBrokenDownByItsOwnCategoriesAndKeptApartFromTheExpenses() = runTest {
        val viewModel = viewModelWith(
            transactions = listOf(
                transaction(id = 1L, categoryId = SALARY, type = TransactionType.INCOME, amountPaise = 90_000L, date = SOME_DAY),
                transaction(id = 2L, categoryId = RENT, amountPaise = 30_000L, date = SOME_DAY),
            ),
        )
        collect(viewModel)

        val content = viewModel.content()

        assertEquals(listOf("Salary"), content.incomes.map { it.categoryName })
        assertEquals(listOf(100), content.incomes.map { it.percent })
        assertEquals(listOf("Rent"), content.expenses.map { it.categoryName })
    }

    @Test
    fun aCategoryWithNothingInThePeriodIsAbsentRatherThanDrawnAsZero() = runTest {
        val viewModel = viewModelWith(
            transactions = listOf(
                transaction(id = 1L, categoryId = GROCERIES, amountPaise = 1_000L, date = THIS_MONTH.minusMonths(1L).atDay(5)),
                transaction(id = 2L, categoryId = RENT, amountPaise = 500L, date = SOME_DAY),
            ),
            categoryNames = mapOf(GROCERIES to "Groceries", RENT to "Rent"),
        )
        collect(viewModel)

        // Groceries was bought last month and not this one, so it does not belong in this month's ring
        // as a zero-width wedge: the breakdown names what was spent, not every category that exists.
        assertEquals(listOf("Rent"), viewModel.content().expenses.map { it.categoryName })
        assertFalse(viewModel.content().isEmpty)
    }

    // ------------------------------------------------------------------ charts

    @Test
    fun aMonthIsBucketedByWeekAndALongerRangeByWholeMonths() = runTest {
        val viewModel = viewModelWith()
        collect(viewModel)

        val weeks = viewModel.content().buckets
        assertEquals(5, weeks.size)
        assertTrue(weeks.all { it.kind == ReportBucketKind.WEEK })

        viewModel.onRangeChange(ReportRange.SIX_MONTHS)
        advanceUntilIdle()

        val months = viewModel.content().buckets
        assertEquals(6, months.size)
        assertTrue(months.all { it.kind == ReportBucketKind.MONTH })
        assertEquals(listOf(6, 7, 8, 9, 10, 11), months.map { YearMonth.from(it.startDate).monthValue })
    }

    @Test
    fun theTrendAlwaysCoversTheLastSixMonthsEvenForAOneMonthReport() = runTest {
        val viewModel = viewModelWith(
            transactions = listOf(
                transaction(id = 1L, categoryId = RENT, amountPaise = 400L, date = THIS_MONTH.minusMonths(3L).atDay(4)),
            ),
        )
        collect(viewModel)

        val trend = viewModel.content().trend

        assertEquals(ReportPeriod.TREND_MONTHS, trend.size)
        assertEquals(THIS_MONTH, trend.last().month)
        // The money is in August, which the one-month report does not total but the trend does draw.
        assertEquals(0L, viewModel.content().summary.expensePaise)
        assertEquals(listOf(0L, 0L, 400L, 0L, 0L, 0L), trend.map { it.expensePaise })
    }

    @Test
    fun theLargestEntriesAreTheBiggestOnesAndOnlyTheFirstFive() = runTest {
        val viewModel = viewModelWith(
            transactions = (1L..7L).map { index ->
                transaction(
                    id = index,
                    categoryId = GROCERIES,
                    amountPaise = index * 1_000L,
                    date = SOME_DAY,
                )
            },
            categoryNames = mapOf(GROCERIES to "Groceries"),
        )
        collect(viewModel)

        // The database applies the limit, so a period of thousands of entries answers with five rows.
        assertEquals(listOf(7L, 6L, 5L, 4L, 3L), viewModel.content().topAmounts.map { it.id })
    }

    // ------------------------------------------------------- reactivity to the ledger

    @Test
    fun recordingATransactionChangesTheReport() = runTest {
        val dao = FakeTransactionDao()
        val viewModel = viewModelOver(dao)
        collect(viewModel)
        assertEquals(0L, viewModel.content().summary.expensePaise)

        dao.insert(transaction(id = 0L, categoryId = RENT, amountPaise = 12_345L, date = SOME_DAY))
        advanceUntilIdle()

        assertEquals(12_345L, viewModel.content().summary.expensePaise)
        assertEquals(1L, viewModel.content().summary.expenseCount)
    }

    @Test
    fun editingATransactionChangesTheReport() = runTest {
        val dao = FakeTransactionDao(
            categoryNames = DEFAULT_CATEGORY_NAMES,
            initial = listOf(transaction(id = 1L, categoryId = RENT, amountPaise = 1_000L, date = SOME_DAY)),
        )
        val viewModel = viewModelOver(dao)
        collect(viewModel)
        assertEquals(1_000L, viewModel.content().summary.expensePaise)

        dao.update(transaction(id = 1L, categoryId = RENT, amountPaise = 2_500L, date = SOME_DAY))
        advanceUntilIdle()

        assertEquals(2_500L, viewModel.content().summary.expensePaise)
        assertEquals(2_500L, viewModel.content().topAmounts.single().amountPaise)
    }

    @Test
    fun removingATransactionChangesTheReport() = runTest {
        val dao = FakeTransactionDao(initial = listOf(transaction(id = 1L, categoryId = RENT, amountPaise = 1_000L, date = SOME_DAY)))
        val viewModel = viewModelOver(dao)
        collect(viewModel)
        assertEquals(1_000L, viewModel.content().summary.expensePaise)

        dao.deleteById(1L)
        advanceUntilIdle()

        assertEquals(0L, viewModel.content().summary.expensePaise)
        assertTrue(viewModel.content().isEmpty)
    }

    // --------------------------------------------------------------- navigation

    @Test
    fun steppingBackMovesTheWindowAMonthAndOffersTheWayForwardAgain() = runTest {
        val viewModel = viewModelWith(
            transactions = listOf(
                transaction(id = 1L, categoryId = RENT, amountPaise = 700L, date = THIS_MONTH.minusMonths(1L).atDay(9)),
            ),
        )
        collect(viewModel)

        viewModel.onPreviousPeriod()
        advanceUntilIdle()

        val content = viewModel.content()
        assertEquals(THIS_MONTH.minusMonths(1L), content.period.endMonth)
        assertTrue(content.canGoForward)
        assertFalse(content.isCurrentMonth)
        assertEquals(700L, content.summary.expensePaise)
    }

    @Test
    fun steppingForwardIsRefusedAtThisMonth() = runTest {
        val viewModel = viewModelWith()
        collect(viewModel)

        // There is no later data to show, and a report of a month that has not happened would be a
        // page of zeroes wearing the heading of a fact.
        viewModel.onNextPeriod()
        advanceUntilIdle()

        assertEquals(THIS_MONTH, viewModel.content().period.endMonth)
    }

    @Test
    fun steppingForwardIsAllowedFromAPastMonth() = runTest {
        val viewModel = viewModelWith()
        collect(viewModel)

        viewModel.onPreviousPeriod()
        advanceUntilIdle()
        assertEquals(THIS_MONTH.minusMonths(1L), viewModel.content().period.endMonth)

        viewModel.onNextPeriod()
        advanceUntilIdle()
        assertEquals(THIS_MONTH, viewModel.content().period.endMonth)
        assertFalse(viewModel.content().canGoForward)
    }

    @Test
    fun changingTheLengthKeepsTheMonthTheReportEndsAt() = runTest {
        val viewModel = viewModelWith()
        collect(viewModel)

        viewModel.onPreviousPeriod()
        advanceUntilIdle()
        viewModel.onRangeChange(ReportRange.YEAR)
        advanceUntilIdle()

        val period = viewModel.content().period
        assertEquals(THIS_MONTH.minusMonths(1L), period.endMonth)
        assertEquals(ReportRange.YEAR, period.range)
        assertEquals(THIS_MONTH.minusMonths(12L).atDay(1), period.from)
    }

    // ----------------------------------------------------------------- helpers

    /**
     * Starts collecting the state the way the screen does, and runs the reads behind it to their end.
     *
     * `stateIn` only starts reading once something is subscribed, so a test that asked for the figures
     * without subscribing would be reading the loading state it started with. The collector lives in
     * the background scope and stays subscribed, so a later write to the ledger is seen by the same
     * subscription - which is what makes "the report refreshed" a real assertion.
     */
    private fun TestScope.collect(viewModel: ReportsViewModel) {
        backgroundScope.launch { viewModel.uiState.collect { } }
        advanceUntilIdle()
    }

    /** Asserts the state is [ReportsUiState.Content] and returns it. */
    private fun ReportsViewModel.content(): ReportsUiState.Content {
        val state = uiState.value
        assertTrue("expected the report to have loaded, was $state", state is ReportsUiState.Content)
        return state as ReportsUiState.Content
    }

    private fun viewModelWith(
        transactions: List<TransactionEntity> = emptyList(),
        categoryNames: Map<Long, String> = DEFAULT_CATEGORY_NAMES,
    ): ReportsViewModel = viewModelOver(FakeTransactionDao(categoryNames = categoryNames, initial = transactions))

    /** Wraps [dao] in the real repository, exactly as the application class wires it up. */
    private fun viewModelOver(dao: FakeTransactionDao): ReportsViewModel =
        ReportsViewModel(ReportRepository(dao, FakeCategoryDao()), today = TODAY)
            .also { viewModelStore.put("reports", it) }

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

        val DEFAULT_CATEGORY_NAMES: Map<Long, String> = mapOf(
            SALARY to "Salary",
            GROCERIES to "Groceries",
            RENT to "Rent",
        )

        val AUDIT_INSTANT: Instant = Instant.parse("2026-11-03T06:30:00Z")

        /** The day the report believes it is, pinned so "this month" never depends on the clock. */
        val TODAY: LocalDate = LocalDate.of(2026, 11, 17)

        val THIS_MONTH: YearMonth = YearMonth.from(TODAY)

        val SOME_DAY: LocalDate = LocalDate.of(2026, 11, 9)
    }
}
