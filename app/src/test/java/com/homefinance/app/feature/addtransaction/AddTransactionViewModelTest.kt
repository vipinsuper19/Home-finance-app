package com.homefinance.app.feature.addtransaction

import androidx.lifecycle.ViewModelStore
import com.homefinance.app.core.util.MoneyParseResult
import com.homefinance.app.data.local.dao.CategoryDao
import com.homefinance.app.data.local.dao.TransactionDao
import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.data.local.entity.TransactionEntity
import com.homefinance.app.data.model.TransactionType
import com.homefinance.app.data.repository.CategoryRepository
import com.homefinance.app.data.repository.FakeCategoryDao
import com.homefinance.app.data.repository.FakeTransactionDao
import com.homefinance.app.data.repository.TransactionRepository
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The add-transaction form's state machine, driven with fake DAOs behind the real repositories.
 *
 * The form's whole job is to turn typed text into exactly one validated row, so these tests are
 * mostly about what must *not* happen: a rounded amount, a blank amount stored anyway, a missing
 * category accepted, and - above all - a second write from a second tap.
 *
 * `Dispatchers.Main`, which `viewModelScope` needs and which does not exist off-device, is replaced
 * with a test dispatcher, so nothing here needs a device, a database file, or the passage of time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddTransactionViewModelTest {

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

    // ------------------------------------------------------------- starting state

    @Test
    fun theFormStartsBlankOnTheGivenTypeAndTodaysDate() {
        val viewModel = viewModelWith(initialType = TransactionType.EXPENSE)

        val state = viewModel.uiState.value
        assertEquals(TransactionType.EXPENSE, state.type)
        assertEquals(TODAY, state.date)
        assertEquals("", state.amountInput)
        assertNull(state.categoryId)
        assertNull(state.amountError)
        assertNull(state.savedTransactionId)
    }

    @Test
    fun theFormCanBeOpenedForIncomeInstead() {
        val viewModel = viewModelWith(initialType = TransactionType.INCOME)

        assertEquals(TransactionType.INCOME, viewModel.uiState.value.type)
    }

    @Test
    fun theCategoryListIsLoadingUntilTheDatabaseHasAnswered() {
        val viewModel = viewModelWith()

        // No coroutine has run yet, so the form must be able to show a spinner rather than an
        // empty list it has not actually confirmed.
        assertEquals(CategoriesUiState.Loading, viewModel.uiState.value.categories)
    }

    // -------------------------------------------------------- category filtering

    @Test
    fun onlyActiveCategoriesOfTheSelectedTypeAreOffered() = runTest {
        val viewModel = viewModelWith(
            categoryDao = FakeCategoryDao(
                listOf(
                    category(1L, "Groceries", TransactionType.EXPENSE),
                    category(2L, "Old Rent", TransactionType.EXPENSE, isActive = false),
                    category(3L, "Salary", TransactionType.INCOME),
                ),
            ),
        )
        advanceUntilIdle()

        // Retired categories label history, but a new entry must not be filed under one; and an
        // income category cannot hold an expense.
        assertEquals(listOf("Groceries"), options(viewModel).map { it.name })
    }

    @Test
    fun switchingTypeReloadsTheCategoriesAndDropsTheSelection() = runTest {
        val viewModel = viewModelWith(
            categoryDao = FakeCategoryDao(
                listOf(
                    category(1L, "Groceries", TransactionType.EXPENSE),
                    category(3L, "Salary", TransactionType.INCOME),
                ),
            ),
        )
        advanceUntilIdle()
        viewModel.onCategorySelected(1L)

        viewModel.onTypeChange(TransactionType.INCOME)
        advanceUntilIdle()

        assertEquals(listOf("Salary"), options(viewModel).map { it.name })
        // An expense category can never be silently carried over to an income entry.
        assertNull(viewModel.uiState.value.categoryId)
    }

    @Test
    fun aFailedCategoryReadIsReportedAndCanBeRetried() = runTest {
        val dao = FlakyCategoryDao(FakeCategoryDao(listOf(category(1L, "Groceries", TransactionType.EXPENSE))))
        dao.failure = IOException("the database could not be read")
        val viewModel = viewModelWith(categoryDao = dao)

        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.categories is CategoriesUiState.Failed)

        dao.failure = null
        viewModel.retryCategories()
        advanceUntilIdle()

        assertEquals(listOf("Groceries"), options(viewModel).map { it.name })
    }

    // -------------------------------------------------------- amount validation

    @Test
    fun aSaveWithNoAmountIsRefusedAndNothingIsWritten() = runTest {
        val transactions = FakeTransactionDao()
        val viewModel = viewModelWith(transactionDao = transactions)
        advanceUntilIdle()
        viewModel.onCategorySelected(1L)

        viewModel.save()
        advanceUntilIdle()

        assertEquals(MoneyParseResult.Problem.Blank, viewModel.uiState.value.amountError)
        assertEquals(0, transactions.inserted.size)
        assertNull(viewModel.uiState.value.savedTransactionId)
    }

    @Test
    fun anAmountWithTooMuchPrecisionIsRefusedRatherThanRounded() = runTest {
        val transactions = FakeTransactionDao()
        val viewModel = viewModelWith(transactionDao = transactions)
        advanceUntilIdle()
        viewModel.onCategorySelected(1L)
        viewModel.onAmountChange("100.999")

        viewModel.save()
        advanceUntilIdle()

        assertEquals(MoneyParseResult.Problem.TooManyDecimalPlaces, viewModel.uiState.value.amountError)
        assertEquals(0, transactions.inserted.size)
    }

    @Test
    fun aZeroAmountIsRefused() = runTest {
        val transactions = FakeTransactionDao()
        val viewModel = viewModelWith(transactionDao = transactions)
        advanceUntilIdle()
        viewModel.onCategorySelected(1L)
        viewModel.onAmountChange("0")

        viewModel.save()
        advanceUntilIdle()

        assertEquals(MoneyParseResult.Problem.NotPositive, viewModel.uiState.value.amountError)
        assertEquals(0, transactions.inserted.size)
    }

    @Test
    fun editingTheAmountClearsThePreviousComplaint() = runTest {
        val viewModel = viewModelWith()
        advanceUntilIdle()
        viewModel.onCategorySelected(1L)
        viewModel.save()
        advanceUntilIdle()

        viewModel.onAmountChange("250")

        assertNull(viewModel.uiState.value.amountError)
    }

    // ------------------------------------------------------ category validation

    @Test
    fun aSaveWithNoCategoryIsRefused() = runTest {
        val transactions = FakeTransactionDao()
        val viewModel = viewModelWith(transactionDao = transactions)
        advanceUntilIdle()
        viewModel.onAmountChange("250")

        viewModel.save()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showCategoryError)
        assertEquals(0, transactions.inserted.size)
        assertNull(viewModel.uiState.value.savedTransactionId)
    }

    @Test
    fun choosingACategoryClearsTheMissingCategoryComplaint() = runTest {
        val viewModel = viewModelWith()
        advanceUntilIdle()
        viewModel.onAmountChange("250")
        viewModel.save()
        advanceUntilIdle()

        viewModel.onCategorySelected(1L)

        assertEquals(false, viewModel.uiState.value.showCategoryError)
    }

    // --------------------------------------------------------------- saving

    @Test
    fun theExactPaiseAndTrimmedDescriptionReachTheDatabase() = runTest {
        val transactions = FakeTransactionDao()
        val categoryDao = FakeCategoryDao(listOf(category(1L, "Groceries", TransactionType.EXPENSE)))
        val viewModel = viewModelWith(categoryDao = categoryDao, transactionDao = transactions)
        advanceUntilIdle()

        viewModel.onAmountChange("250.50")
        viewModel.onCategorySelected(1L)
        viewModel.onDateChange(LocalDate.of(2026, 10, 5))
        viewModel.onDescriptionChange("  Weekly shop  ")
        viewModel.save()
        advanceUntilIdle()

        val stored = transactions.inserted.single()
        assertEquals(25_050L, stored.amountPaise)
        assertEquals(TransactionType.EXPENSE, stored.type)
        assertEquals(1L, stored.categoryId)
        assertEquals(LocalDate.of(2026, 10, 5), stored.transactionDate)
        // The repository owns the description policy: blank becomes null, surrounding space goes.
        assertEquals("Weekly shop", stored.description)
        // The id comes from the database, and only now may the screen treat the entry as saved.
        assertEquals(1L, viewModel.uiState.value.savedTransactionId)
    }

    @Test
    fun anEmptyDescriptionIsStoredAsNothingRatherThanAsSpaces() = runTest {
        val transactions = FakeTransactionDao()
        val viewModel = viewModelWith(
            categoryDao = FakeCategoryDao(listOf(category(1L, "Groceries", TransactionType.EXPENSE))),
            transactionDao = transactions,
        )
        advanceUntilIdle()

        viewModel.onAmountChange("250")
        viewModel.onCategorySelected(1L)
        viewModel.onDescriptionChange("   ")
        viewModel.save()
        advanceUntilIdle()

        assertNull(transactions.inserted.single().description)
    }

    @Test
    fun aSecondSaveWhileTheFirstIsInFlightDoesNotWriteTwice() = runTest {
        val transactions = FakeTransactionDao()
        val viewModel = viewModelWith(
            categoryDao = FakeCategoryDao(listOf(category(1L, "Groceries", TransactionType.EXPENSE))),
            transactionDao = transactions,
        )
        advanceUntilIdle()

        viewModel.onAmountChange("250")
        viewModel.onCategorySelected(1L)

        viewModel.save()
        // The write has not completed yet; a second tap must be ignored rather than write again.
        viewModel.save()
        advanceUntilIdle()

        assertEquals(1, transactions.inserted.size)
    }

    @Test
    fun savingAgainAfterASuccessDoesNotWriteAgain() = runTest {
        val transactions = FakeTransactionDao()
        val viewModel = viewModelWith(
            categoryDao = FakeCategoryDao(listOf(category(1L, "Groceries", TransactionType.EXPENSE))),
            transactionDao = transactions,
        )
        advanceUntilIdle()

        viewModel.onAmountChange("250")
        viewModel.onCategorySelected(1L)
        viewModel.save()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.savedTransactionId != null)

        viewModel.save()
        advanceUntilIdle()

        assertEquals(1, transactions.inserted.size)
    }

    @Test
    fun aRepositoryRefusalIsShownAsACategoryProblemAndIsNotAFakeSuccess() = runTest {
        val categoryDao = FakeCategoryDao(listOf(category(1L, "Groceries", TransactionType.EXPENSE)))
        val transactions = FakeTransactionDao()
        val viewModel = viewModelWith(categoryDao = categoryDao, transactionDao = transactions)
        advanceUntilIdle()
        viewModel.onAmountChange("250")
        viewModel.onCategorySelected(1L)

        // The category is retyped between the form loading and the save. The repository refuses
        // the write, and the form must report that rather than claim the entry was recorded.
        categoryDao.update(category(1L, "Groceries", TransactionType.INCOME))

        viewModel.save()
        advanceUntilIdle()

        assertEquals(SaveError.CategoryUnavailable, viewModel.uiState.value.saveError)
        assertNull(viewModel.uiState.value.savedTransactionId)
        assertEquals(0, transactions.inserted.size)
    }

    @Test
    fun aWriteFailureIsReportedWithoutClaimingSuccess() = runTest {
        val transactions = FailingInsertTransactionDao(
            delegate = FakeTransactionDao(),
            failure = IOException("the database could not be written"),
        )
        val viewModel = viewModelWith(
            categoryDao = FakeCategoryDao(listOf(category(1L, "Groceries", TransactionType.EXPENSE))),
            transactionDao = transactions,
        )
        advanceUntilIdle()
        viewModel.onAmountChange("250")
        viewModel.onCategorySelected(1L)

        viewModel.save()
        advanceUntilIdle()

        val error = viewModel.uiState.value.saveError
        assertTrue("expected a write failure, was $error", error is SaveError.Failed)
        assertNull(viewModel.uiState.value.savedTransactionId)
        assertEquals(false, viewModel.uiState.value.isSaving)
    }

    // ------------------------------------------------------- new category dialog

    @Test
    fun creatingACategorySelectsItAndClosesTheDialog() = runTest {
        val categoryDao = FakeCategoryDao()
        val viewModel = viewModelWith(categoryDao = categoryDao)
        advanceUntilIdle()

        viewModel.openCategoryDialog()
        viewModel.onCategoryNameChange("Milk")
        viewModel.createCategory()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.categoryDialog)
        assertEquals("Milk", categoryDao.inserted.single().name)
        assertEquals(TransactionType.EXPENSE, categoryDao.inserted.single().type)
        assertEquals(1L, state.categoryId)
    }

    @Test
    fun anEmptyCategoryNameIsRefusedBeforeTheDatabaseIsAsked() = runTest {
        val categoryDao = FakeCategoryDao()
        val viewModel = viewModelWith(categoryDao = categoryDao)
        advanceUntilIdle()

        viewModel.openCategoryDialog()
        viewModel.onCategoryNameChange("   ")
        viewModel.createCategory()
        advanceUntilIdle()

        assertEquals(CategoryDialogError.BlankName, viewModel.uiState.value.categoryDialog?.error)
        assertEquals(0, categoryDao.inserted.size)
    }

    @Test
    fun aFailedCategoryCreationIsReportedInsideTheDialog() = runTest {
        val categoryDao = FailingInsertCategoryDao(
            delegate = FakeCategoryDao(),
            failure = IOException("the database could not be written"),
        )
        val viewModel = viewModelWith(categoryDao = categoryDao)
        advanceUntilIdle()

        viewModel.openCategoryDialog()
        viewModel.onCategoryNameChange("Milk")
        viewModel.createCategory()
        advanceUntilIdle()

        val error = viewModel.uiState.value.categoryDialog?.error
        assertTrue("expected a creation failure, was $error", error is CategoryDialogError.Failed)
        assertEquals(false, viewModel.uiState.value.categoryDialog?.isSaving)
    }

    @Test
    fun editingTheCategoryNameClearsThePreviousComplaint() = runTest {
        val viewModel = viewModelWith()
        advanceUntilIdle()
        viewModel.openCategoryDialog()
        viewModel.createCategory()
        advanceUntilIdle()
        assertEquals(CategoryDialogError.BlankName, viewModel.uiState.value.categoryDialog?.error)

        viewModel.onCategoryNameChange("Milk")

        assertNull(viewModel.uiState.value.categoryDialog?.error)
    }

    // ---------------------------------------------------------------- fixtures

    /** The categories currently offered, asserting the list has actually loaded. */
    private fun options(viewModel: AddTransactionViewModel): List<CategoryOption> {
        val categories = viewModel.uiState.value.categories
        assertTrue("expected the categories to have loaded, was $categories", categories is CategoriesUiState.Ready)
        return (categories as CategoriesUiState.Ready).options
    }

    private fun viewModelWith(
        initialType: TransactionType = TransactionType.EXPENSE,
        categoryDao: CategoryDao = FakeCategoryDao(listOf(category(1L, "Groceries", TransactionType.EXPENSE))),
        transactionDao: TransactionDao = FakeTransactionDao(),
    ): AddTransactionViewModel = AddTransactionViewModel(
        transactions = TransactionRepository(transactionDao, categoryDao),
        categories = CategoryRepository(categoryDao),
        initialType = initialType,
        today = TODAY,
    ).also { viewModelStore.put("add", it) }

    private fun category(
        id: Long,
        name: String,
        type: TransactionType,
        isActive: Boolean = true,
    ): CategoryEntity = CategoryEntity(
        id = id,
        name = name,
        type = type,
        isActive = isActive,
        createdAt = AUDIT_INSTANT,
    )

    /** A DAO that fails a chosen read until the failure is cleared, standing in for a broken file. */
    private class FlakyCategoryDao(private val delegate: CategoryDao) : CategoryDao by delegate {

        var failure: Throwable? = null

        override fun observeByType(type: TransactionType): Flow<List<CategoryEntity>> {
            val currentFailure = failure
            return if (currentFailure != null) {
                flow { throw currentFailure }
            } else {
                delegate.observeByType(type)
            }
        }
    }

    /** A DAO whose write always fails, standing in for a database that cannot be written. */
    private class FailingInsertTransactionDao(
        private val delegate: TransactionDao,
        private val failure: Throwable,
    ) : TransactionDao by delegate {
        override suspend fun insert(transaction: TransactionEntity): Long = throw failure
    }

    private class FailingInsertCategoryDao(
        private val delegate: CategoryDao,
        private val failure: Throwable,
    ) : CategoryDao by delegate {
        override suspend fun insert(category: CategoryEntity): Long = throw failure
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 10, 17)
        val AUDIT_INSTANT: Instant = Instant.parse("2026-10-17T06:30:00Z")
    }
}
