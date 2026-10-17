package com.homefinance.app.feature.addtransaction

import androidx.lifecycle.ViewModelStore
import com.homefinance.app.core.util.MoneyInput
import com.homefinance.app.core.util.MoneyParseResult
import com.homefinance.app.core.util.QuantityParseResult
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
        assertNull(state.category)
        assertNull(state.amountError)
        assertNull(state.savedTransactionId)
        // The optional parts of the entry start empty and off, not with a placeholder value.
        assertEquals("", state.description)
        assertEquals("", state.notes)
        assertEquals(false, state.useQuantityAndRate)
        assertEquals("", state.quantityInput)
        assertEquals("", state.rateInput)
        assertNull(state.derivedAmountPaise)
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
        assertNull(viewModel.uiState.value.category)
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
        // The selection carries the name with it, so the form can show what it chose without
        // waiting for the category list to come back round from the database.
        assertEquals(CategoryOption(id = 1L, name = "Milk"), state.category)
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

    @Test
    fun aCategoryThatIsNotOfferedCannotBeSelected() = runTest {
        val viewModel = viewModelWith()
        advanceUntilIdle()

        // Id 99 is not in the list the picker showed. Storing it would put "nothing chosen" on
        // screen while the save filed the money under something the household never saw.
        viewModel.onCategorySelected(99L)

        assertNull(viewModel.uiState.value.category)
    }

    // ------------------------------------------------------------------- notes

    @Test
    fun notesAreStoredSeparatelyFromTheDescription() = runTest {
        val transactions = FakeTransactionDao()
        val viewModel = viewModelWith(
            categoryDao = FakeCategoryDao(listOf(category(1L, "Groceries", TransactionType.EXPENSE))),
            transactionDao = transactions,
        )
        advanceUntilIdle()

        viewModel.onAmountChange("250")
        viewModel.onCategorySelected(1L)
        viewModel.onDescriptionChange("Weekly shop")
        viewModel.onNotesChange("  Bought at the Sunday market  ")
        viewModel.save()
        advanceUntilIdle()

        val stored = transactions.inserted.single()
        // Description stays the one-line summary the ledger row shows; notes are their own field.
        assertEquals("Weekly shop", stored.description)
        assertEquals("Bought at the Sunday market", stored.notes)
    }

    @Test
    fun anEmptyNotesFieldIsStoredAsNothingRatherThanAsSpaces() = runTest {
        val transactions = FakeTransactionDao()
        val viewModel = viewModelWith(
            categoryDao = FakeCategoryDao(listOf(category(1L, "Groceries", TransactionType.EXPENSE))),
            transactionDao = transactions,
        )
        advanceUntilIdle()

        viewModel.onAmountChange("250")
        viewModel.onCategorySelected(1L)
        viewModel.onNotesChange("   ")
        viewModel.save()
        advanceUntilIdle()

        assertNull(transactions.inserted.single().notes)
    }

    // ------------------------------------------------------ quantity and rate

    @Test
    fun theAmountIsDerivedFromTheQuantityAndRateWhileTheSwitchIsOn() = runTest {
        val viewModel = viewModelWith()
        advanceUntilIdle()

        viewModel.onQuantityAndRateToggled(true)
        viewModel.onQuantityChange("1.5")
        viewModel.onRateChange("60")

        // 1.5 litres at ₹60.00 a litre is ₹90.00, and it is shown before anything is saved.
        assertEquals(9_000L, viewModel.uiState.value.derivedAmountPaise)
    }

    @Test
    fun noAmountIsDerivedWhileTheSwitchIsOffOrAFieldIsIncomplete() = runTest {
        val viewModel = viewModelWith()
        advanceUntilIdle()

        viewModel.onQuantityChange("1.5")
        viewModel.onRateChange("60")
        // The switch is off, so the typed amount - not the product - is what would be saved.
        assertNull(viewModel.uiState.value.derivedAmountPaise)

        viewModel.onQuantityAndRateToggled(true)
        viewModel.onRateChange("")
        assertNull(viewModel.uiState.value.derivedAmountPaise)

        viewModel.onRateChange("60")
        viewModel.onQuantityChange("not a number")
        assertNull(viewModel.uiState.value.derivedAmountPaise)
    }

    @Test
    fun aQuantityAndRateEntryIsSavedWithTheDerivedAmountAndBothFigures() = runTest {
        val transactions = FakeTransactionDao()
        val viewModel = viewModelWith(
            categoryDao = FakeCategoryDao(listOf(category(1L, "Milk", TransactionType.EXPENSE))),
            transactionDao = transactions,
        )
        advanceUntilIdle()

        viewModel.onQuantityAndRateToggled(true)
        viewModel.onQuantityChange("1.5")
        viewModel.onRateChange("60")
        viewModel.onCategorySelected(1L)
        viewModel.save()
        advanceUntilIdle()

        val stored = transactions.inserted.single()
        assertEquals(9_000L, stored.amountPaise)
        assertEquals(1_500L, stored.quantityThousandths)
        assertEquals(6_000L, stored.ratePaise)
    }

    @Test
    fun aProductThatDoesNotLandOnAPaiseIsRoundedHalfUpWhenSaved() = runTest {
        val transactions = FakeTransactionDao()
        val viewModel = viewModelWith(
            categoryDao = FakeCategoryDao(listOf(category(1L, "Milk", TransactionType.EXPENSE))),
            transactionDao = transactions,
        )
        advanceUntilIdle()

        viewModel.onQuantityAndRateToggled(true)
        viewModel.onQuantityChange("0.333")
        viewModel.onRateChange("59.99")
        viewModel.onCategorySelected(1L)
        viewModel.save()
        advanceUntilIdle()

        // 0.333 x ₹59.99 is 1997.667 paise: the row stores 1998, and the repository accepts it
        // because it derives the same figure from the same quantities.
        assertEquals(1_998L, transactions.inserted.single().amountPaise)
    }

    @Test
    fun aQuantityWithTooMuchPrecisionIsRefusedRatherThanRounded() = runTest {
        val transactions = FakeTransactionDao()
        val viewModel = viewModelWith(
            categoryDao = FakeCategoryDao(listOf(category(1L, "Milk", TransactionType.EXPENSE))),
            transactionDao = transactions,
        )
        advanceUntilIdle()

        viewModel.onQuantityAndRateToggled(true)
        viewModel.onQuantityChange("1.2345")
        viewModel.onRateChange("60")
        viewModel.onCategorySelected(1L)
        viewModel.save()
        advanceUntilIdle()

        assertEquals(
            QuantityParseResult.Problem.TooManyDecimalPlaces,
            viewModel.uiState.value.quantityError,
        )
        assertEquals(0, transactions.inserted.size)
        assertNull(viewModel.uiState.value.savedTransactionId)
    }

    @Test
    fun aMissingQuantityIsRefusedBeforeTheRateIsEvenLookedAt() = runTest {
        val transactions = FakeTransactionDao()
        val viewModel = viewModelWith(
            categoryDao = FakeCategoryDao(listOf(category(1L, "Milk", TransactionType.EXPENSE))),
            transactionDao = transactions,
        )
        advanceUntilIdle()

        viewModel.onQuantityAndRateToggled(true)
        viewModel.onQuantityChange("")
        viewModel.onRateChange("60")
        viewModel.onCategorySelected(1L)
        viewModel.save()
        advanceUntilIdle()

        assertEquals(QuantityParseResult.Problem.Blank, viewModel.uiState.value.quantityError)
        // The rate was fine, so it does not get a complaint it did not earn.
        assertNull(viewModel.uiState.value.rateError)
        assertEquals(0, transactions.inserted.size)
    }

    @Test
    fun aMissingRateIsRefused() = runTest {
        val transactions = FakeTransactionDao()
        val viewModel = viewModelWith(
            categoryDao = FakeCategoryDao(listOf(category(1L, "Milk", TransactionType.EXPENSE))),
            transactionDao = transactions,
        )
        advanceUntilIdle()

        viewModel.onQuantityAndRateToggled(true)
        viewModel.onQuantityChange("1.5")
        viewModel.onRateChange("")
        viewModel.onCategorySelected(1L)
        viewModel.save()
        advanceUntilIdle()

        assertEquals(MoneyParseResult.Problem.Blank, viewModel.uiState.value.rateError)
        assertEquals(0, transactions.inserted.size)
    }

    @Test
    fun aZeroRateIsRefused() = runTest {
        val transactions = FakeTransactionDao()
        val viewModel = viewModelWith(
            categoryDao = FakeCategoryDao(listOf(category(1L, "Milk", TransactionType.EXPENSE))),
            transactionDao = transactions,
        )
        advanceUntilIdle()

        viewModel.onQuantityAndRateToggled(true)
        viewModel.onQuantityChange("1.5")
        viewModel.onRateChange("0")
        viewModel.onCategorySelected(1L)
        viewModel.save()
        advanceUntilIdle()

        assertEquals(MoneyParseResult.Problem.NotPositive, viewModel.uiState.value.rateError)
        assertEquals(0, transactions.inserted.size)
    }

    @Test
    fun aProductTooLargeToBeMoneyIsRefusedOnTheAmountRatherThanWrapped() = runTest {
        val transactions = FakeTransactionDao()
        val viewModel = viewModelWith(
            categoryDao = FakeCategoryDao(listOf(category(1L, "Milk", TransactionType.EXPENSE))),
            transactionDao = transactions,
        )
        advanceUntilIdle()

        // Both figures are individually valid - each fits the column it is stored in - but the two
        // multiplied together are far larger than any amount a Long can hold. The complaint belongs
        // on the amount, because neither field on its own is wrong.
        viewModel.onQuantityAndRateToggled(true)
        viewModel.onQuantityChange("9223372036854775")
        viewModel.onRateChange("92233720368547758")
        viewModel.onCategorySelected(1L)
        viewModel.save()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.quantityError)
        assertNull(viewModel.uiState.value.rateError)
        assertEquals(MoneyParseResult.Problem.TooLarge, viewModel.uiState.value.amountError)
        assertEquals(0, transactions.inserted.size)
        assertNull(viewModel.uiState.value.savedTransactionId)
    }

    @Test
    fun aProductTooSmallToBeMoneyIsRefusedOnTheAmount() = runTest {
        val transactions = FakeTransactionDao()
        val viewModel = viewModelWith(
            categoryDao = FakeCategoryDao(listOf(category(1L, "Milk", TransactionType.EXPENSE))),
            transactionDao = transactions,
        )
        advanceUntilIdle()

        // 0.001 units at ₹1.00 is 0.1 paise, which is not a recordable amount at all. Both figures
        // are individually valid, so the complaint has to land on the amount.
        viewModel.onQuantityAndRateToggled(true)
        viewModel.onQuantityChange("0.001")
        viewModel.onRateChange("1")
        viewModel.onCategorySelected(1L)
        viewModel.save()
        advanceUntilIdle()

        assertEquals(MoneyParseResult.Problem.NotPositive, viewModel.uiState.value.amountError)
        assertEquals(0, transactions.inserted.size)
    }

    @Test
    fun switchingTheModeClearsTheComplaintAboutTheFieldThatStopsDecidingTheAmount() = runTest {
        val viewModel = viewModelWith()
        advanceUntilIdle()
        viewModel.onAmountChange("0")
        viewModel.save()
        advanceUntilIdle()
        assertEquals(MoneyParseResult.Problem.NotPositive, viewModel.uiState.value.amountError)

        viewModel.onQuantityAndRateToggled(true)

        assertNull(viewModel.uiState.value.amountError)
        assertEquals(true, viewModel.uiState.value.useQuantityAndRate)
    }

    @Test
    fun switchingTheModeKeepsWhatWasTypedInBothModes() = runTest {
        val viewModel = viewModelWith()
        advanceUntilIdle()
        viewModel.onAmountChange("250")
        viewModel.onQuantityAndRateToggled(true)
        viewModel.onQuantityChange("1.5")
        viewModel.onRateChange("60")

        viewModel.onQuantityAndRateToggled(false)
        assertEquals("250", viewModel.uiState.value.amountInput)

        viewModel.onQuantityAndRateToggled(true)
        assertEquals("1.5", viewModel.uiState.value.quantityInput)
        assertEquals("60", viewModel.uiState.value.rateInput)
    }

    @Test
    fun editingAQuantityClearsThePreviousComplaint() = runTest {
        val viewModel = viewModelWith()
        advanceUntilIdle()
        viewModel.onQuantityAndRateToggled(true)
        viewModel.save()
        advanceUntilIdle()
        assertEquals(QuantityParseResult.Problem.Blank, viewModel.uiState.value.quantityError)

        viewModel.onQuantityChange("1.5")

        assertNull(viewModel.uiState.value.quantityError)
    }

    @Test
    fun editingARateClearsThePreviousComplaint() = runTest {
        val viewModel = viewModelWith()
        advanceUntilIdle()
        viewModel.onQuantityAndRateToggled(true)
        viewModel.save()
        advanceUntilIdle()

        viewModel.onRateChange("60")

        assertNull(viewModel.uiState.value.rateError)
    }

    @Test
    fun aProductTooLargeToBeMoneyIsRefusedRatherThanWrapped() = runTest {
        val transactions = FakeTransactionDao()
        val viewModel = viewModelWith(
            categoryDao = FakeCategoryDao(listOf(category(1L, "Milk", TransactionType.EXPENSE))),
            transactionDao = transactions,
        )
        advanceUntilIdle()

        viewModel.onQuantityAndRateToggled(true)
        viewModel.onQuantityChange("9999999999999999")
        viewModel.onRateChange("999999999999")
        viewModel.onCategorySelected(1L)
        viewModel.save()
        advanceUntilIdle()

        // The quantity itself does not fit the thousandths column, so it is that field that is
        // refused - and nothing was written either way.
        assertEquals(QuantityParseResult.Problem.TooLarge, viewModel.uiState.value.quantityError)
        assertEquals(0, transactions.inserted.size)
    }

    @Test
    fun aQuantityEntryWithNoCategoryIsStillRefused() = runTest {
        val transactions = FakeTransactionDao()
        val viewModel = viewModelWith(transactionDao = transactions)
        advanceUntilIdle()

        viewModel.onQuantityAndRateToggled(true)
        viewModel.onQuantityChange("1.5")
        viewModel.onRateChange("60")
        viewModel.save()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showCategoryError)
        assertEquals(0, transactions.inserted.size)
    }

    // ---------------------------------------------------------------- editing

    @Test
    fun anEditFormIsNotDrawnUntilItHasReadTheRowItIsChanging() {
        val viewModel = editViewModel()

        // Before any coroutine has run the form has nothing to show. Drawing the empty add form here
        // would be a form the household could fill in and save over a transaction nobody had read.
        assertEquals(EditLoadState.Loading, viewModel.uiState.value.editLoad)
    }

    @Test
    fun anEditFormFillsItselfInFromTheStoredRow() = runTest {
        val viewModel = editViewModel(transactionDao = seededDao(storedTransaction()))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(EditLoadState.Ready, state.editLoad)
        assertEquals(TransactionType.EXPENSE, state.type)
        assertEquals(TODAY.minusDays(2), state.date)
        assertEquals("90", state.amountInput)
        assertEquals(CategoryOption(id = 1L, name = "Groceries"), state.category)
        assertEquals("Milk", state.description)
        assertEquals("Two bottles", state.notes)
        assertEquals(false, state.useQuantityAndRate)
        assertNull(state.amountError)
        assertNull(state.savedTransactionId)
    }

    @Test
    fun theAmountIsPrefilledAsTextTheParserWouldAccept() = runTest {
        val viewModel = editViewModel(
            transactionDao = seededDao(storedTransaction(amountPaise = 10_050L)),
        )
        advanceUntilIdle()

        // Not "₹100.50": a symbol or a grouping comma would come back as NotANumber and the
        // household would be told their own saved amount is invalid.
        assertEquals("100.5", viewModel.uiState.value.amountInput)
        assertEquals(
            MoneyParseResult.Success(10_050L),
            MoneyInput.parseRupeesToPaise(viewModel.uiState.value.amountInput),
        )
    }

    @Test
    fun anEntryRecordedByQuantityAndRateIsPrefilledWithTheSwitchOn() = runTest {
        val viewModel = editViewModel(
            transactionDao = seededDao(
                storedTransaction(
                    amountPaise = 9_000L,
                    quantityThousandths = 1_500L,
                    ratePaise = 6_000L,
                ),
            ),
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(true, state.useQuantityAndRate)
        assertEquals("1.5", state.quantityInput)
        assertEquals("60", state.rateInput)
        assertEquals(9_000L, state.derivedAmountPaise)
    }

    @Test
    fun anEntryFiledUnderARetiredCategoryStillShowsThatCategory() = runTest {
        val viewModel = editViewModel(
            categoryDao = FakeCategoryDao(
                listOf(category(1L, "Old Rent", TransactionType.EXPENSE, isActive = false)),
            ),
            transactionDao = FakeTransactionDao(
                categoryNames = mapOf(1L to "Old Rent"),
                initial = listOf(storedTransaction(categoryId = 1L)),
            ),
        )
        advanceUntilIdle()

        // The name is the one it was filed under. The picker will not offer it again - that is what
        // retiring a category means - but the entry keeps its own.
        assertEquals(CategoryOption(id = 1L, name = "Old Rent"), viewModel.uiState.value.category)
        assertEquals(emptyList<String>(), options(viewModel).map { it.name })
    }

    @Test
    fun anEditFormForATransactionThatIsGoneSaysSoAndWritesNothing() = runTest {
        val transactions = seededDao(storedTransaction(id = 1L))
        val viewModel = editViewModel(transactionDao = transactions, editingId = 42L)
        advanceUntilIdle()

        assertEquals(EditLoadState.NotFound, viewModel.uiState.value.editLoad)
        viewModel.save()
        advanceUntilIdle()

        assertEquals(0, transactions.inserted.size)
        assertEquals(0, transactions.updated.size)
        assertNull(viewModel.uiState.value.savedTransactionId)
    }

    @Test
    fun aFailedReadOfTheRowIsReportedAndCanBeRetried() = runTest {
        val transactions = seededDao(storedTransaction())
        transactions.failure = IOException("the database could not be read")
        val viewModel = editViewModel(transactionDao = transactions)

        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.editLoad is EditLoadState.Failed)

        transactions.failure = null
        viewModel.retryEditLoad()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(EditLoadState.Ready, state.editLoad)
        assertEquals("90", state.amountInput)
    }

    @Test
    fun retryingAFailedReadLeavesAnAddFormAlone() = runTest {
        val viewModel = viewModelWith()
        advanceUntilIdle()

        viewModel.retryEditLoad()
        advanceUntilIdle()

        assertEquals(EditLoadState.Ready, viewModel.uiState.value.editLoad)
    }

    @Test
    fun savingAnEditRewritesTheRowInsteadOfRecordingANewOne() = runTest {
        val transactions = seededDao(storedTransaction())
        val viewModel = editViewModel(transactionDao = transactions)
        advanceUntilIdle()

        viewModel.onAmountChange("125")
        viewModel.save()
        advanceUntilIdle()

        // The one thing an edit must never do: a second entry, and the "changed" one not the one the
        // household changed.
        assertEquals(0, transactions.inserted.size)
        assertEquals(1, transactions.updated.size)

        val rewritten = transactions.updated.single()
        assertEquals(1L, rewritten.id)
        assertEquals(12_500L, rewritten.amountPaise)
        assertEquals(AUDIT_INSTANT, rewritten.createdAt)
        assertEquals(1L, viewModel.uiState.value.savedTransactionId)
    }

    @Test
    fun aSavedEditPreventsASecondWriteFromASecondTap() = runTest {
        val transactions = seededDao(storedTransaction())
        val viewModel = editViewModel(transactionDao = transactions)
        advanceUntilIdle()
        viewModel.onAmountChange("125")

        viewModel.save()
        advanceUntilIdle()
        viewModel.save()
        viewModel.save()
        advanceUntilIdle()

        assertEquals(1, transactions.updated.size)
        assertEquals(0, transactions.inserted.size)
    }

    @Test
    fun aDoubleTapWhileAnEditIsInFlightWritesOnce() = runTest {
        val transactions = seededDao(storedTransaction())
        val viewModel = editViewModel(transactionDao = transactions)
        advanceUntilIdle()
        viewModel.onAmountChange("125")

        // Both taps land before the write has returned, which is the window the guard exists for.
        viewModel.save()
        viewModel.save()
        advanceUntilIdle()

        assertEquals(1, transactions.updated.size)
    }

    @Test
    fun anEditWhoseRowWasDeletedMeanwhileReportsItRatherThanInsertingItBack() = runTest {
        val transactions = seededDao(storedTransaction())
        val viewModel = editViewModel(transactionDao = transactions)
        advanceUntilIdle()
        viewModel.onAmountChange("125")

        // Another screen - or another finger - removed the entry while this form was open.
        transactions.deleteById(1L)
        viewModel.save()
        advanceUntilIdle()

        assertEquals(SaveError.TransactionMissing, viewModel.uiState.value.saveError)
        assertNull(viewModel.uiState.value.savedTransactionId)
        assertEquals(0, transactions.inserted.size)
        assertEquals(0, transactions.updated.size)
    }

    @Test
    fun anEditStillRefusesAnAmountItCannotRead() = runTest {
        val transactions = seededDao(storedTransaction())
        val viewModel = editViewModel(transactionDao = transactions)
        advanceUntilIdle()

        viewModel.onAmountChange("12.345")
        viewModel.save()
        advanceUntilIdle()

        assertEquals(
            MoneyParseResult.Problem.TooManyDecimalPlaces,
            viewModel.uiState.value.amountError,
        )
        assertEquals(0, transactions.updated.size)
    }

    @Test
    fun anEditStillRefusesAChangeWithNoCategory() = runTest {
        val transactions = seededDao(storedTransaction())
        val viewModel = editViewModel(
            categoryDao = FakeCategoryDao(listOf(category(1L, "Groceries", TransactionType.EXPENSE))),
            transactionDao = transactions,
        )
        advanceUntilIdle()
        viewModel.onTypeChange(TransactionType.INCOME)
        advanceUntilIdle()

        viewModel.save()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showCategoryError)
        assertEquals(0, transactions.updated.size)
    }

    @Test
    fun anEditSwitchingTheQuantityAndRateOffSavesTheAmountAsPlainMoney() = runTest {
        val transactions = seededDao(
            storedTransaction(
                amountPaise = 9_000L,
                quantityThousandths = 1_500L,
                ratePaise = 6_000L,
            ),
        )
        val viewModel = editViewModel(transactionDao = transactions)
        advanceUntilIdle()

        viewModel.onQuantityAndRateToggled(false)
        viewModel.onAmountChange("95")
        viewModel.save()
        advanceUntilIdle()

        val rewritten = transactions.updated.single()
        assertEquals(9_500L, rewritten.amountPaise)
        assertNull(rewritten.quantityThousandths)
        assertNull(rewritten.ratePaise)
    }

    @Test
    fun anEditCarriesTheTypeSwitchThroughToTheStoredRow() = runTest {
        val categoryDao = FakeCategoryDao(
            listOf(
                category(1L, "Groceries", TransactionType.EXPENSE),
                category(2L, "Salary", TransactionType.INCOME),
            ),
        )
        val transactions = FakeTransactionDao(
            categoryNames = mapOf(1L to "Groceries", 2L to "Salary"),
            initial = listOf(storedTransaction()),
        )
        val viewModel = editViewModel(categoryDao = categoryDao, transactionDao = transactions)
        advanceUntilIdle()

        viewModel.onTypeChange(TransactionType.INCOME)
        advanceUntilIdle()
        viewModel.onCategorySelected(2L)
        viewModel.save()
        advanceUntilIdle()

        val rewritten = transactions.updated.single()
        assertEquals(TransactionType.INCOME, rewritten.type)
        assertEquals(2L, rewritten.categoryId)
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

    private fun editViewModel(
        transactionDao: TransactionDao = FakeTransactionDao(),
        categoryDao: CategoryDao = FakeCategoryDao(
            listOf(category(1L, "Groceries", TransactionType.EXPENSE)),
        ),
        editingId: Long = 1L,
    ): AddTransactionViewModel = AddTransactionViewModel(
        transactions = TransactionRepository(transactionDao, categoryDao),
        categories = CategoryRepository(categoryDao),
        initialType = TransactionType.EXPENSE,
        editingId = editingId,
        today = TODAY,
    ).also { viewModelStore.put("edit", it) }

    /** A fake ledger holding [row], with the category name its id resolves to. */
    private fun seededDao(row: TransactionEntity): FakeTransactionDao =
        FakeTransactionDao(categoryNames = mapOf(row.categoryId to "Groceries"), initial = listOf(row))

    private fun storedTransaction(
        id: Long = 1L,
        type: TransactionType = TransactionType.EXPENSE,
        amountPaise: Long = 9_000L,
        categoryId: Long = 1L,
        description: String? = "Milk",
        notes: String? = "Two bottles",
        quantityThousandths: Long? = null,
        ratePaise: Long? = null,
        transactionDate: LocalDate = TODAY.minusDays(2),
    ): TransactionEntity = TransactionEntity(
        id = id,
        type = type,
        amountPaise = amountPaise,
        categoryId = categoryId,
        description = description,
        notes = notes,
        quantityThousandths = quantityThousandths,
        ratePaise = ratePaise,
        transactionDate = transactionDate,
        createdAt = AUDIT_INSTANT,
        updatedAt = AUDIT_INSTANT,
    )

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
