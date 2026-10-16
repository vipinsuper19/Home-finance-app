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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Transactions screen's state machine, driven with a fake DAO behind the real repository.
 *
 * These run on the JVM in milliseconds and are fully deterministic: `Dispatchers.Main` - which
 * `viewModelScope` uses and which does not exist off-device - is replaced with a test dispatcher,
 * and the state is never read until the work has been advanced to completion. Nothing here needs
 * a device, a database file, or the passage of real time.
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
        assertEquals(TransactionsUiState.Content(emptyList()), viewModel.uiState.value)
    }

    // ---------------------------------------------------------------- content

    @Test
    fun storedTransactionsAreExposedNewestFirst() = runTest {
        val viewModel = viewModelWith(
            transactions = listOf(
                transactionRow(id = 1L, categoryId = 1L, transactionDate = LocalDate.of(2026, 9, 1)),
                transactionRow(id = 2L, categoryId = 1L, transactionDate = LocalDate.of(2026, 10, 15)),
                transactionRow(id = 3L, categoryId = 1L, transactionDate = LocalDate.of(2026, 10, 15)),
            ),
            categoryNames = mapOf(1L to "Groceries"),
        )
        collectUiState(viewModel)
        advanceUntilIdle()

        val items = viewModel.content().items

        assertEquals(listOf(3L, 2L, 1L), items.map { it.id })
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
        assertEquals(listOf("Salary", "Groceries"), viewModel.content().items.map { it.categoryName })
    }

    @Test
    fun aRowCarriesEverythingTheScreenDraws() = runTest {
        val date = LocalDate.of(2026, 10, 15)
        val viewModel = viewModelWith(
            transactions = listOf(
                transactionRow(
                    id = 7L,
                    categoryId = 1L,
                    type = TransactionType.EXPENSE,
                    amountPaise = 273_405L,
                    description = "Weekly shop",
                    transactionDate = date,
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
                transactionDate = date,
                amountPaise = 273_405L,
                type = TransactionType.EXPENSE,
            ),
            viewModel.content().items.single(),
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
                    transactionDate = LocalDate.of(2026, 10, 1),
                ),
                transactionRow(
                    id = 2L,
                    categoryId = 20L,
                    type = TransactionType.EXPENSE,
                    transactionDate = LocalDate.of(2026, 10, 15),
                ),
            ),
            categoryNames = mapOf(10L to "Salary", 20L to "Groceries"),
        )
        collectUiState(viewModel)
        advanceUntilIdle()

        assertEquals(
            listOf(TransactionType.EXPENSE, TransactionType.INCOME),
            viewModel.content().items.map { it.type },
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

        assertEquals(beyondDoublePrecision, viewModel.content().items.single().amountPaise)
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

        assertEquals(listOf(1L), viewModel.content().items.map { it.id })
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
        TransactionsViewModel(TransactionRepository(dao, FakeCategoryDao()))
            .also { viewModelStore.put("transactions", it) }

    private fun transactionRow(
        id: Long,
        categoryId: Long,
        type: TransactionType = TransactionType.EXPENSE,
        amountPaise: Long = 12_345L,
        description: String? = null,
        transactionDate: LocalDate = LocalDate.of(2026, 10, 15),
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
    }
}
