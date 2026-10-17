package com.homefinance.app.feature.transactions

import androidx.lifecycle.ViewModelStore
import com.homefinance.app.data.local.entity.TransactionEntity
import com.homefinance.app.data.model.TransactionType
import com.homefinance.app.data.repository.FakeCategoryDao
import com.homefinance.app.data.repository.FakeTransactionDao
import com.homefinance.app.data.repository.TransactionRepository
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Transactions screen's state machine, driven with a fake DAO behind the real repository.
 *
 * These run on the JVM in milliseconds and are fully deterministic: `Dispatchers.Main` - which
 * `viewModelScope` uses and which does not exist off-device - is replaced with a test dispatcher,
 * the day the screen believes it is is pinned through the constructor, and the state is never read
 * until the work has been advanced to completion. Nothing here needs a device, a database file, or
 * the passage of real time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    /**
     * Holds the view models so [ViewModelStore.clear] can run their `onCleared`.
     *
     * Without it, `viewModelScope` would still be alive when the test ends, leaving the
     * `WhileSubscribed` sharing coroutine parked on a five-second timer that outlives the test.
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

    // ------------------------------------------------------------------ empty

    @Test
    fun theInitialStateIsLoadingBeforeTheQueryHasAnswered() = runTest {
        // No collection has started, so nothing has been read: the screen must be able to show a
        // loading indicator rather than an empty ledger it has not actually confirmed.
        val viewModel = viewModelWith()

        assertEquals(TransactionsUiState.Loading, viewModel.uiState.value)
    }

    @Test
    fun anEmptyLedgerLoadsAsEmptyContentRatherThanAsAFailure() = runTest {
        val viewModel = viewModelWith()
        collectUiState(viewModel)
        advanceUntilIdle()

        // An empty ledger is a successful read of nothing. Reporting it as an error would tell a
        // household their data is broken on the day they install the app.
        assertTrue(viewModel.content().ledgerIsEmpty)
        assertTrue(viewModel.content().groups.isEmpty())
    }

    // ---------------------------------------------------------------- content

    @Test
    fun storedTransactionsAreExposedNewestFirst() = runTest {
        val viewModel = viewModelWith(
            transactions = listOf(
                transactionRow(id = 1L, categoryId = 1L, transactionDate = LocalDate.of(2026, 10, 1)),
                transactionRow(id = 2L, categoryId = 1L, transactionDate = LocalDate.of(2026, 10, 15)),
                transactionRow(id = 3L, categoryId = 1L, transactionDate = LocalDate.of(2026, 10, 15)),
            ),
            categoryNames = mapOf(1L to "Groceries"),
        )
        collectUiState(viewModel)
        advanceUntilIdle()

        assertEquals(listOf(3L, 2L, 1L), viewModel.content().visibleItems.map { it.id })
    }

    @Test
    fun eachRowCarriesTheNameOfItsOwnCategory() = runTest {
        val viewModel = viewModelWith(
            transactions = listOf(
                transactionRow(id = 1L, categoryId = 10L, transactionDate = LocalDate.of(2026, 10, 1)),
                transactionRow(id = 2L, categoryId = 20L, transactionDate = LocalDate.of(2026, 10, 15)),
            ),
            categoryNames = mapOf(10L to "Groceries", 20L to "Salary"),
        )
        collectUiState(viewModel)
        advanceUntilIdle()

        // Newest first, so the 15 October row leads; each row kept its own category's name.
        assertEquals(listOf("Salary", "Groceries"), viewModel.content().visibleItems.map { it.categoryName })
    }

    @Test
    fun rowsAreSplitUnderADayHeadingAndTheNewestDayLeads() = runTest {
        val viewModel = viewModelWith(
            transactions = listOf(
                transactionRow(id = 2L, categoryId = 1L, transactionDate = TODAY),
                transactionRow(id = 1L, categoryId = 1L, transactionDate = TODAY.minusDays(1)),
            ),
            categoryNames = mapOf(1L to "Groceries"),
        )
        collectUiState(viewModel)
        advanceUntilIdle()

        val groups = viewModel.content().groups

        assertEquals(listOf(TODAY, TODAY.minusDays(1)), groups.map { it.date })
        assertEquals(listOf(DateRelation.TODAY, DateRelation.YESTERDAY), groups.map { it.relation })
    }

    @Test
    fun aRowCarriesEverythingTheScreenDraws() = runTest {
        val viewModel = viewModelWith(
            transactions = listOf(
                transactionRow(
                    id = 7L,
                    categoryId = 1L,
                    type = TransactionType.EXPENSE,
                    amountPaise = 273_405L,
                    description = "Weekly shop",
                    transactionDate = TODAY,
                ),
            ),
            categoryNames = mapOf(1L to "Groceries"),
        )
        collectUiState(viewModel)
        advanceUntilIdle()

        assertEquals(
            TransactionListItem(
                id = 7L,
                categoryName = "Groceries",
                description = "Weekly shop",
                transactionDate = TODAY,
                amountPaise = 273_405L,
                type = TransactionType.EXPENSE,
            ),
            viewModel.content().visibleItems.single(),
        )
    }

    @Test
    fun incomeAndExpenseAreKeptApartSoTheRowCanTellThemApart() = runTest {
        val viewModel = viewModelWith(
            transactions = listOf(
                transactionRow(
                    id = 1L,
                    categoryId = 10L,
                    type = TransactionType.INCOME,
                    transactionDate = TODAY.minusDays(14),
                ),
                transactionRow(
                    id = 2L,
                    categoryId = 20L,
                    type = TransactionType.EXPENSE,
                    transactionDate = TODAY,
                ),
            ),
            categoryNames = mapOf(10L to "Salary", 20L to "Groceries"),
        )
        collectUiState(viewModel)
        advanceUntilIdle()

        assertEquals(
            listOf(TransactionType.EXPENSE, TransactionType.INCOME),
            viewModel.content().visibleItems.map { it.type },
        )
    }

    @Test
    fun amountsAreCarriedAsExactPaiseWithNoFloatingPointInTheWay() = runTest {
        // 2^53 + 1 is the smallest integer a Double cannot represent: it would come back as
        // 2^53. If an amount ever passed through a Float or a Double, this assertion fails.
        val beyondDoublePrecision = 9_007_199_254_740_993L

        val viewModel = viewModelWith(
            transactions = listOf(
                transactionRow(id = 1L, categoryId = 1L, amountPaise = beyondDoublePrecision),
            ),
            categoryNames = mapOf(1L to "Groceries"),
        )
        collectUiState(viewModel)
        advanceUntilIdle()

        assertEquals(beyondDoublePrecision, viewModel.content().visibleItems.single().amountPaise)
    }

    // ------------------------------------------------------------------ filters

    @Test
    fun theScreenOpensOnTheCurrentMonthWhileItHasEntries() = runTest {
        val viewModel = viewModelWith(
            transactions = listOf(
                transactionRow(id = 2L, categoryId = 1L, transactionDate = TODAY),
                transactionRow(id = 1L, categoryId = 1L, transactionDate = TODAY.minusMonths(1)),
            ),
            categoryNames = mapOf(1L to "Groceries"),
        )
        collectUiState(viewModel)
        advanceUntilIdle()

        assertEquals(YearMonth.from(TODAY), viewModel.content().filters.month)
        assertEquals(listOf(2L), viewModel.content().visibleItems.map { it.id })
    }

    @Test
    fun theScreenOpensOnTheNewestMonthWithEntriesWhenTheCurrentMonthIsEmpty() = runTest {
        val viewModel = viewModelWith(
            transactions = listOf(transactionRow(id = 1L, categoryId = 1L, transactionDate = TODAY.minusMonths(1))),
            categoryNames = mapOf(1L to "Groceries"),
        )
        collectUiState(viewModel)
        advanceUntilIdle()

        // Not an October heading over a September ledger.
        assertEquals(YearMonth.from(TODAY.minusMonths(1)), viewModel.content().filters.month)
        assertEquals(listOf(1L), viewModel.content().visibleItems.map { it.id })
    }

    @Test
    fun choosingAMonthShowsThatMonth() = runTest {
        val viewModel = viewModelWith(
            transactions = listOf(
                transactionRow(id = 2L, categoryId = 1L, transactionDate = TODAY),
                transactionRow(id = 1L, categoryId = 1L, transactionDate = TODAY.minusMonths(1)),
            ),
            categoryNames = mapOf(1L to "Groceries"),
        )
        collectUiState(viewModel)
        advanceUntilIdle()

        viewModel.onMonthChange(YearMonth.from(TODAY.minusMonths(1)))
        advanceUntilIdle()

        assertEquals(listOf(1L), viewModel.content().visibleItems.map { it.id })
    }

    @Test
    fun choosingADirectionChipShowsOnlyThatDirection() = runTest {
        val viewModel = viewModelWith(
            transactions = listOf(
                transactionRow(id = 2L, categoryId = 20L, type = TransactionType.EXPENSE),
                transactionRow(id = 1L, categoryId = 10L, type = TransactionType.INCOME),
            ),
            categoryNames = mapOf(10L to "Salary", 20L to "Groceries"),
        )
        collectUiState(viewModel)
        advanceUntilIdle()

        viewModel.onTypeFilterChange(TransactionTypeFilter.INCOME)
        advanceUntilIdle()

        assertEquals(listOf(1L), viewModel.content().visibleItems.map { it.id })
    }

    @Test
    fun typingInTheSearchBoxNarrowsTheRowsAsItIsTyped() = runTest {
        val viewModel = viewModelWith(
            transactions = listOf(
                transactionRow(id = 2L, categoryId = 20L, description = "Chai and biscuits"),
                transactionRow(id = 1L, categoryId = 10L, type = TransactionType.INCOME),
            ),
            categoryNames = mapOf(10L to "Salary", 20L to "Groceries"),
        )
        collectUiState(viewModel)
        advanceUntilIdle()

        viewModel.onQueryChange("chai")
        advanceUntilIdle()

        assertEquals(listOf(2L), viewModel.content().visibleItems.map { it.id })
    }

    @Test
    fun theSearchTextIsEchoedBackSoTheFieldCanShowWhatWasTyped() = runTest {
        val viewModel = viewModelWith(
            transactions = listOf(transactionRow(id = 1L, categoryId = 1L)),
            categoryNames = mapOf(1L to "Groceries"),
        )
        collectUiState(viewModel)
        advanceUntilIdle()

        viewModel.onQueryChange("  groc  ")
        advanceUntilIdle()

        // Untrimmed, so the caret does not jump as the household types a space.
        assertEquals("  groc  ", viewModel.content().filters.query)
    }

    @Test
    fun filteringReusesTheRowsAlreadyReadRatherThanAskingTheDatabaseAgain() = runTest {
        val dao = FakeTransactionDao(mapOf(1L to "Groceries"), listOf(transactionRow(id = 1L, categoryId = 1L)))
        val viewModel = viewModelOver(dao)
        collectUiState(viewModel)
        advanceUntilIdle()

        // The ledger becomes unreadable. If filtering went back to the database, this failure would
        // replace rows that are already in memory - which is exactly what must not happen while a
        // household is typing into a search box.
        dao.failure = IOException("the database is no longer readable")
        viewModel.onQueryChange("groc")
        advanceUntilIdle()

        assertEquals(listOf(1L), viewModel.content().visibleItems.map { it.id })
    }

    @Test
    fun aFilterThatMatchesNothingIsNotAnEmptyLedger() = runTest {
        val viewModel = viewModelWith(
            transactions = listOf(transactionRow(id = 1L, categoryId = 1L)),
            categoryNames = mapOf(1L to "Groceries"),
        )
        collectUiState(viewModel)
        advanceUntilIdle()

        viewModel.onQueryChange("nothing matches this")
        advanceUntilIdle()

        val state = viewModel.content()
        assertTrue(state.groups.isEmpty())
        assertFalse(state.ledgerIsEmpty)
    }

    @Test
    fun clearingTheFiltersPutsEveryControlBack() = runTest {
        val viewModel = viewModelWith(
            transactions = listOf(
                transactionRow(id = 2L, categoryId = 20L, type = TransactionType.EXPENSE, transactionDate = TODAY),
                transactionRow(id = 1L, categoryId = 10L, type = TransactionType.INCOME, transactionDate = TODAY.minusMonths(1)),
            ),
            categoryNames = mapOf(10L to "Salary", 20L to "Groceries"),
        )
        collectUiState(viewModel)
        advanceUntilIdle()

        viewModel.onQueryChange("salary")
        viewModel.onTypeFilterChange(TransactionTypeFilter.INCOME)
        viewModel.onMonthChange(YearMonth.from(TODAY.minusMonths(1)))
        advanceUntilIdle()

        viewModel.clearFilters()
        advanceUntilIdle()

        val state = viewModel.content()
        assertEquals(TransactionFilters(query = "", type = TransactionTypeFilter.ALL, month = YearMonth.from(TODAY)), state.filters)
        // The month follows the ledger again rather than staying pinned to whichever month was on
        // screen when the household gave up on their filters.
        assertEquals(listOf(2L), state.visibleItems.map { it.id })
    }

    // ------------------------------------------------------------------ error

    @Test
    fun aFailedQueryBecomesAnErrorStateCarryingItsCause() = runTest {
        val failure = IOException("the database could not be read")
        val dao = FakeTransactionDao(categoryNames = mapOf(1L to "Groceries"))
        dao.failure = failure
        val viewModel = viewModelOver(dao)

        collectUiState(viewModel)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("expected a failure state, was $state", state is TransactionsUiState.Error)
        assertEquals(failure, (state as TransactionsUiState.Error).cause)
    }

    @Test
    fun aFailureAfterRowsWereShownDoesNotLeaveThoseRowsOnScreen() = runTest {
        val dao = FakeTransactionDao(mapOf(1L to "Groceries"), listOf(transactionRow(id = 1L, categoryId = 1L)))
        val viewModel = viewModelOver(dao)

        collectUiState(viewModel)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is TransactionsUiState.Content)

        // The next read fails. Rows loaded a moment ago must not stay on screen looking current.
        dao.failure = IOException("the database could not be read")
        viewModel.retry()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("a failed reload must not present stale rows, was $state", state is TransactionsUiState.Error)
    }

    @Test
    fun retryReReadsTheLedgerAndCanRecover() = runTest {
        val dao = FakeTransactionDao(mapOf(1L to "Groceries"), listOf(transactionRow(id = 1L, categoryId = 1L)))
        dao.failure = IOException("the database could not be read")
        val viewModel = viewModelOver(dao)

        collectUiState(viewModel)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is TransactionsUiState.Error)

        dao.failure = null
        viewModel.retry()
        advanceUntilIdle()

        assertEquals(listOf(1L), viewModel.content().visibleItems.map { it.id })
    }

    @Test
    fun retryWhileTheFailurePersistsStaysInTheErrorState() = runTest {
        val dao = FakeTransactionDao(mapOf(1L to "Groceries"), listOf(transactionRow(id = 1L, categoryId = 1L)))
        dao.failure = IOException("the database is still unavailable")
        val viewModel = viewModelOver(dao)

        collectUiState(viewModel)
        advanceUntilIdle()

        viewModel.retry()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is TransactionsUiState.Error)
    }

    @Test
    fun aFilterAppliedWhileTheReadIsBrokenDoesNotMaskTheFailure() = runTest {
        // Filters are not a way out of a broken read: a ledger that failed to load must still say
        // so, rather than showing an empty list that looks like a successful search.
        val dao = FakeTransactionDao(mapOf(1L to "Groceries"), listOf(transactionRow(id = 1L, categoryId = 1L)))
        dao.failure = IOException("the database could not be read")
        val viewModel = viewModelOver(dao)

        collectUiState(viewModel)
        advanceUntilIdle()

        viewModel.onQueryChange("groc")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is TransactionsUiState.Error)
    }

    // ---------------------------------------------------------------- fixtures

    /** Starts collecting the state the way the screen does, so the shared flow actually runs. */
    private fun TestScope.collectUiState(viewModel: TransactionsViewModel) {
        backgroundScope.launch { viewModel.uiState.collect { } }
    }

    /** Asserts the state is [TransactionsUiState.Content] and returns it. */
    private fun TransactionsViewModel.content(): TransactionsUiState.Content {
        val state = uiState.value
        assertTrue("expected the ledger to have loaded, was $state", state is TransactionsUiState.Content)
        return state as TransactionsUiState.Content
    }

    private fun viewModelWith(
        transactions: List<TransactionEntity> = emptyList(),
        categoryNames: Map<Long, String> = emptyMap(),
    ): TransactionsViewModel =
        viewModelOver(FakeTransactionDao(categoryNames = categoryNames, initial = transactions))

    /** Wraps [dao] in the real repository, exactly as the application class wires it up. */
    private fun viewModelOver(dao: FakeTransactionDao): TransactionsViewModel =
        TransactionsViewModel(TransactionRepository(dao, FakeCategoryDao()), today = TODAY)
            .also { viewModelStore.put("transactions", it) }

    private fun transactionRow(
        id: Long,
        categoryId: Long,
        type: TransactionType = TransactionType.EXPENSE,
        amountPaise: Long = 12_345L,
        description: String? = null,
        transactionDate: LocalDate = TODAY,
    ): TransactionEntity = TransactionEntity(
        id = id,
        type = type,
        amountPaise = amountPaise,
        categoryId = categoryId,
        description = description,
        transactionDate = transactionDate,
        createdAt = AUDIT_INSTANT,
        updatedAt = AUDIT_INSTANT,
    )

    private companion object {
        val AUDIT_INSTANT: Instant = Instant.parse("2026-10-16T06:30:00Z")

        /** The day the screen believes it is, pinned so the headings never depend on the clock. */
        val TODAY: LocalDate = LocalDate.of(2026, 10, 17)
    }
}
