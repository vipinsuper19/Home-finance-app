package com.homefinance.app.feature.transactiondetail

import androidx.lifecycle.ViewModelStore
import com.homefinance.app.data.local.dao.TransactionDao
import com.homefinance.app.data.local.entity.TransactionEntity
import com.homefinance.app.data.local.relation.TransactionWithCategory
import com.homefinance.app.data.model.TransactionType
import com.homefinance.app.data.repository.FakeCategoryDao
import com.homefinance.app.data.repository.FakeTransactionDao
import com.homefinance.app.data.repository.TransactionRepository
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
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
 * The details screen's state machine, driven with a fake ledger behind the real repository.
 *
 * Two things have to hold, and both are about *not* lying. A deletion is reported only after the
 * database has removed the row, so a failure leaves the household looking at the transaction that is
 * genuinely still there; and a read that arrives while the screen is deleting something must not be
 * mistaken for news, or a confirmed deletion would end as "not found".
 *
 * `Dispatchers.Main`, which `viewModelScope` needs and which does not exist off-device, is replaced
 * with a test dispatcher, so nothing here needs a device or the passage of time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionDetailViewModelTest {

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

    // ------------------------------------------------------------------ reading

    @Test
    fun theScreenIsLoadingUntilTheDatabaseHasAnswered() {
        val viewModel = viewModelOver(FakeTransactionDao())

        assertEquals(TransactionDetailUiState.Loading, viewModel.uiState.value)
    }

    @Test
    fun theStoredRowIsShownWithItsCategoryName() = runTest {
        val viewModel = viewModelOver(seededDao())
        advanceUntilIdle()

        val content = contentOf(viewModel)
        assertEquals(1L, content.transaction.id)
        assertEquals("Groceries", content.transaction.categoryName)
        assertEquals(9_000L, content.transaction.amountPaise)
        assertEquals("Milk", content.transaction.description)
        assertEquals("Two bottles", content.transaction.notes)
        assertEquals(TODAY.minusDays(2), content.transaction.transactionDate)
        assertEquals(AUDIT_INSTANT, content.transaction.createdAt)
    }

    @Test
    fun aTransactionThatIsNotThereIsReportedRatherThanDrawnBlank() = runTest {
        val viewModel = viewModelOver(seededDao(), transactionId = 42L)
        advanceUntilIdle()

        assertEquals(TransactionDetailUiState.NotFound, viewModel.uiState.value)
    }

    @Test
    fun aFailedReadIsReportedAndCanBeRetried() = runTest {
        val transactions = seededDao()
        transactions.failure = IOException("the database could not be read")
        val viewModel = viewModelOver(transactions)

        advanceUntilIdle()
        val failed = viewModel.uiState.value
        assertTrue("expected a failure, was $failed", failed is TransactionDetailUiState.Failed)

        transactions.failure = null
        viewModel.retry()
        advanceUntilIdle()

        assertEquals("Milk", contentOf(viewModel).transaction.description)
    }

    @Test
    fun anEditFromThisScreenIsSeenWithoutReopeningIt() = runTest {
        val transactions = PushingTransactionDao(row())
        val viewModel = viewModelOver(transactions)
        advanceUntilIdle()

        transactions.push(row().copy(amountPaise = 12_500L))
        advanceUntilIdle()

        // The screen the edit form returns to re-reads the row, so the figure on it is the figure in
        // the database rather than the one it was opened with.
        assertEquals(12_500L, contentOf(viewModel).transaction.amountPaise)
    }

    // ---------------------------------------------------------------- confirming

    @Test
    fun openingTheConfirmationDeletesNothingOnItsOwn() = runTest {
        val transactions = seededDao()
        val viewModel = viewModelOver(transactions)
        advanceUntilIdle()

        viewModel.onDeleteRequested()
        advanceUntilIdle()

        assertEquals(true, contentOf(viewModel).deleteConfirmationVisible)
        assertEquals(emptyList<Long>(), transactions.deletedIds)
        assertTrue(transactions.getById(1L) != null)
    }

    @Test
    fun dismissingTheConfirmationDeletesNothing() = runTest {
        val transactions = seededDao()
        val viewModel = viewModelOver(transactions)
        advanceUntilIdle()

        viewModel.onDeleteRequested()
        viewModel.onDeleteDismissed()
        advanceUntilIdle()

        val content = contentOf(viewModel)
        assertEquals(false, content.deleteConfirmationVisible)
        assertEquals(false, content.deleted)
        assertEquals(emptyList<Long>(), transactions.deletedIds)
        assertTrue(transactions.getById(1L) != null)
    }

    @Test
    fun confirmingRemovesTheRowAndOnlyThenReportsTheDeletion() = runTest {
        val transactions = seededDao()
        val viewModel = viewModelOver(transactions)
        advanceUntilIdle()
        viewModel.onDeleteRequested()

        viewModel.confirmDelete()
        // Nothing has been awaited yet, so the row is still on screen and nothing has been removed:
        // the screen must not show a hole where the transaction was.
        val inFlight = contentOf(viewModel)
        assertEquals(true, inFlight.isDeleting)
        assertEquals(false, inFlight.deleted)

        advanceUntilIdle()

        val finished = contentOf(viewModel)
        assertEquals(true, finished.deleted)
        assertEquals(false, finished.deleteConfirmationVisible)
        assertEquals(listOf(1L), transactions.deletedIds)
    }

    @Test
    fun aSecondConfirmWhileTheFirstIsStillRunningDeletesOnce() = runTest {
        val transactions = seededDao()
        val viewModel = viewModelOver(transactions)
        advanceUntilIdle()
        viewModel.onDeleteRequested()

        viewModel.confirmDelete()
        viewModel.confirmDelete()
        advanceUntilIdle()

        assertEquals(listOf(1L), transactions.deletedIds)
    }

    @Test
    fun aSecondConfirmAfterASuccessfulDeleteDoesNothing() = runTest {
        val transactions = seededDao()
        val viewModel = viewModelOver(transactions)
        advanceUntilIdle()
        viewModel.onDeleteRequested()
        viewModel.confirmDelete()
        advanceUntilIdle()

        viewModel.confirmDelete()
        advanceUntilIdle()

        assertEquals(listOf(1L), transactions.deletedIds)
        assertEquals(true, contentOf(viewModel).deleted)
    }

    // ---------------------------------------------------------------- failures

    @Test
    fun aFailedDeleteIsReportedAndLeavesTheTransactionOnScreen() = runTest {
        val transactions = seededDao()
        transactions.deleteFailure = IOException("the database is read only")
        val viewModel = viewModelOver(transactions)
        advanceUntilIdle()
        viewModel.onDeleteRequested()

        viewModel.confirmDelete()
        advanceUntilIdle()

        val content = contentOf(viewModel)
        assertTrue(content.deleteError is DeleteError.Failed)
        assertEquals(false, content.isDeleting)
        assertEquals(false, content.deleted)
        // The row is genuinely still there, so it is still drawn - not a hole where it used to be.
        assertEquals("Milk", content.transaction.description)
        assertEquals(1L, transactions.getById(1L)?.id)
    }

    @Test
    fun aFailedDeleteCanBeRetriedFromTheStillOpenConfirmation() = runTest {
        val transactions = seededDao()
        transactions.deleteFailure = IOException("the database is locked")
        val viewModel = viewModelOver(transactions)
        advanceUntilIdle()
        viewModel.onDeleteRequested()
        viewModel.confirmDelete()
        advanceUntilIdle()

        transactions.deleteFailure = null
        viewModel.confirmDelete()
        advanceUntilIdle()

        val content = contentOf(viewModel)
        assertEquals(true, content.deleted)
        assertEquals(null, content.deleteError)
    }

    @Test
    fun aDeleteThatRemovedNothingSaysTheTransactionIsGoneRatherThanClaimingASuccess() = runTest {
        val transactions = seededDao()
        val viewModel = viewModelOver(transactions)
        advanceUntilIdle()
        viewModel.onDeleteRequested()

        // Something else removed the row first, so the delete statement matches nothing. Reporting
        // `deleted = true` would claim a removal this screen never performed.
        transactions.deleteById(1L)
        viewModel.confirmDelete()

        // The read that reports the absence may arrive on either side of the delete's answer; both
        // orders must end the same way.
        advanceUntilIdle()

        assertEquals(TransactionDetailUiState.NotFound, viewModel.uiState.value)
    }

    // -------------------------------------------------------- reads during a delete

    @Test
    fun aReadReportingTheMissingRowDoesNotUndoAConfirmedDeletion() = runTest {
        val transactions = PushingTransactionDao(row())
        val viewModel = viewModelOver(transactions)
        advanceUntilIdle()
        viewModel.onDeleteRequested()

        viewModel.confirmDelete()
        advanceUntilIdle()

        // The database announces the row's absence as a consequence of the delete this screen just
        // performed. Letting that through would turn the deletion into "not found" and the screen
        // would never leave for the ledger.
        assertEquals(true, contentOf(viewModel).deleted)
    }

    @Test
    fun aReadArrivingWhileTheConfirmationIsOpenDoesNotCloseIt() = runTest {
        val transactions = PushingTransactionDao(row())
        val viewModel = viewModelOver(transactions)
        advanceUntilIdle()
        viewModel.onDeleteRequested()

        // An unrelated change elsewhere in the table makes the read emit again.
        transactions.push(row().copy(amountPaise = 11_000L))
        advanceUntilIdle()

        val content = contentOf(viewModel)
        assertEquals(true, content.deleteConfirmationVisible)
        assertEquals(11_000L, content.transaction.amountPaise)
    }

    @Test
    fun aReadReportingTheRowGoneWhileNothingIsBeingDeletedIsHonoured() = runTest {
        val transactions = PushingTransactionDao(row())
        val viewModel = viewModelOver(transactions)
        advanceUntilIdle()

        transactions.push(null)
        advanceUntilIdle()

        assertEquals(TransactionDetailUiState.NotFound, viewModel.uiState.value)
    }

    // ---------------------------------------------------------------- fixtures

    private fun contentOf(viewModel: TransactionDetailViewModel): TransactionDetailUiState.Content {
        val state = viewModel.uiState.value
        assertTrue("expected a transaction on screen, was $state", state is TransactionDetailUiState.Content)
        return state as TransactionDetailUiState.Content
    }

    private fun viewModelOver(
        transactionDao: TransactionDao,
        transactionId: Long = 1L,
        confirmBeforeDelete: Flow<Boolean> = flowOf(true),
    ): TransactionDetailViewModel = TransactionDetailViewModel(
        transactions = TransactionRepository(transactionDao, FakeCategoryDao(emptyList())),
        transactionId = transactionId,
        confirmBeforeDelete = confirmBeforeDelete,
    ).also { viewModelStore.put("detail", it) }

    /** A fake ledger holding [row], with the category name its id resolves to. */
    private fun seededDao(): FakeTransactionDao = FakeTransactionDao(
        categoryNames = mapOf(1L to "Groceries"),
        initial = listOf(
            TransactionEntity(
                id = 1L,
                type = TransactionType.EXPENSE,
                amountPaise = 9_000L,
                categoryId = 1L,
                description = "Milk",
                notes = "Two bottles",
                transactionDate = TODAY.minusDays(2),
                createdAt = AUDIT_INSTANT,
                updatedAt = AUDIT_INSTANT,
            ),
        ),
    )

    private fun row(): TransactionWithCategory = TransactionWithCategory(
        id = 1L,
        type = TransactionType.EXPENSE,
        amountPaise = 9_000L,
        categoryId = 1L,
        description = "Milk",
        notes = "Two bottles",
        quantityThousandths = null,
        ratePaise = null,
        transactionDate = TODAY.minusDays(2),
        createdAt = AUDIT_INSTANT,
        updatedAt = AUDIT_INSTANT,
        categoryName = "Groceries",
    )

    // ------------------------------------------------------------------ the preference

    @Test
    fun turningOffTheConfirmationDeletesOnTheFirstTap() = runTest {
        val transactions = seededDao()
        val viewModel = viewModelOver(transactions, confirmBeforeDelete = flowOf(false))
        advanceUntilIdle()

        viewModel.onDeleteRequested()
        advanceUntilIdle()

        val content = contentOf(viewModel)
        assertEquals(false, content.deleteConfirmationVisible)
        assertTrue(content.deleted)
        assertEquals(listOf(1L), transactions.deletedIds)
    }

    @Test
    fun aPreferenceThatCannotBeReadStillAsks() = runTest {
        val transactions = seededDao()
        val viewModel = viewModelOver(
            transactions,
            confirmBeforeDelete = kotlinx.coroutines.flow.flow { throw IllegalStateException("no store") },
        )
        advanceUntilIdle()

        viewModel.onDeleteRequested()
        advanceUntilIdle()

        val content = contentOf(viewModel)
        assertTrue(content.deleteConfirmationVisible)
        assertEquals(false, content.deleted)
        assertEquals(emptyList<Long>(), transactions.deletedIds)
    }

    @Test
    fun withTheConfirmationOffADismissalStillCancelsThePendingDelete() = runTest {
        val transactions = seededDao()
        val viewModel = viewModelOver(
            transactions,
            confirmBeforeDelete = kotlinx.coroutines.flow.flow {
                kotlinx.coroutines.yield()
                emit(false)
            },
        )
        advanceUntilIdle()

        viewModel.onDeleteRequested()
        viewModel.onDeleteDismissed()
        advanceUntilIdle()

        assertEquals(emptyList<Long>(), transactions.deletedIds)
        assertEquals(false, contentOf(viewModel).deleted)
    }

    /**
     * A ledger whose one observable row can be pushed to, standing in for Room's invalidation
     * tracker.
     *
     * `FakeTransactionDao` answers a read from a snapshot taken when the flow was made, which cannot
     * express "the database spoke again" - and "the database spoke again while this screen was
     * deleting something" is precisely the case these tests exist for. `MutableStateFlow` conflates
     * equal values, so a push only wakes the collector when the row really changed.
     */
    private class PushingTransactionDao(initial: TransactionWithCategory?) :
        TransactionDao by FakeTransactionDao() {

        private val rows = MutableStateFlow(initial)

        /** Makes the next delete fail with it, standing in for a database that cannot be written. */
        var deleteFailure: Throwable? = null

        override fun observeByIdWithCategory(id: Long): Flow<TransactionWithCategory?> = rows

        override suspend fun deleteById(id: Long): Int {
            deleteFailure?.let { throw it }

            val existed = rows.value != null
            rows.value = null
            return if (existed) 1 else 0
        }

        fun push(row: TransactionWithCategory?) {
            rows.value = row
        }
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 10, 17)
        val AUDIT_INSTANT: Instant = Instant.parse("2026-10-14T05:00:00Z")
    }
}