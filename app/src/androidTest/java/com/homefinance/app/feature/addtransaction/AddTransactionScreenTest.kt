package com.homefinance.app.feature.addtransaction

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homefinance.app.R
import com.homefinance.app.core.ui.theme.HomeFinanceTheme
import com.homefinance.app.core.util.DateFormatter
import com.homefinance.app.data.local.HomeFinanceDatabase
import com.homefinance.app.data.local.category
import com.homefinance.app.data.local.inMemoryDatabase
import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.data.local.entity.TransactionEntity
import com.homefinance.app.data.model.TransactionType
import com.homefinance.app.data.repository.CategoryRepository
import com.homefinance.app.data.repository.TransactionRepository
import com.homefinance.app.navigation.AddTransactionGraph
import com.homefinance.app.navigation.addTransactionFlow
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The add-income / add-expense form and its category picker, rendered on a device.
 *
 * Most of the form's behaviour is pinned by [AddTransactionViewModelTest] on the JVM, where it is
 * deterministic and fast. What a JVM test cannot see is whether the state actually reaches pixels,
 * so this class renders the real screens against a real in-memory SQLite database and asserts what
 * is on screen - and, for the paths only a device can prove, that walking the form the way a person
 * walks it writes exactly the row it should.
 *
 * The two screens are hosted in the **real** graph, via
 * [com.homefinance.app.navigation.addTransactionFlow]. That matters here more than anywhere else:
 * the form and the picker share one view model scoped to that graph, and a category chosen on the
 * picker has to come back to the form with nothing passed through navigation. A test that composed
 * the two screens side by side would never exercise that, and would happily pass while the household
 * saw an empty Category row.
 *
 * No test here touches the household's own ledger, and none of them seeds it with sample data.
 */
@RunWith(AndroidJUnit4::class)
class AddTransactionScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var database: HomeFinanceDatabase

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    @Before
    fun setUp() {
        database = inMemoryDatabase()
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ----------------------------------------------------------- rendering

    @Test
    fun theFormShowsEveryFieldForAnExpense() {
        render()

        composeTestRule.onNodeWithText(text(R.string.add_transaction_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_type_expense)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_type_income)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_amount_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_category_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_description_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_date_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_quantity_rate_switch)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_notes_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_save)).assertIsDisplayed()
        // The date starts at today and is shown the way the rest of the app formats dates.
        composeTestRule.onNodeWithText(DateFormatter.format(LocalDate.now())).assertIsDisplayed()
    }

    @Test
    fun anEmptyFormStartsOnExpenseWithNothingChosen() {
        render()

        composeTestRule.onNodeWithText(text(R.string.add_transaction_type_expense)).assertIsSelected()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_type_income)).assertIsNotSelected()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_category_placeholder))
            .assertIsDisplayed()
        // The optional quantity-and-rate mode is off and its fields are not on screen until asked
        // for.
        composeTestRule.onNode(isToggleable()).assertIsOff()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_quantity_label)).assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_rate_label)).assertDoesNotExist()
    }

    @Test
    fun openingForIncomeStartsOnIncome() {
        render(initialType = TransactionType.INCOME)

        composeTestRule.onNodeWithText(text(R.string.add_transaction_type_income)).assertIsSelected()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_type_expense)).assertIsNotSelected()
    }

    @Test
    fun flippingTheTypeFlipsThePillsAndDropsTheChosenCategory() {
        seedCategory("Groceries", TransactionType.EXPENSE)
        render()
        chooseCategory("Groceries")

        composeTestRule.onNodeWithText(text(R.string.add_transaction_type_income)).performClick()

        composeTestRule.onNodeWithText(text(R.string.add_transaction_type_income)).assertIsSelected()
        // An expense category can never be silently carried over to an income entry.
        composeTestRule.onNodeWithText(text(R.string.add_transaction_category_placeholder))
            .assertIsDisplayed()
    }

    @Test
    fun theDateFieldOpensTheDatePicker() {
        render()

        composeTestRule.onNodeWithContentDescription(text(R.string.add_transaction_date_label))
            .performClick()

        composeTestRule.onNodeWithText(text(R.string.action_ok)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.action_cancel)).assertIsDisplayed()
    }

    // ------------------------------------------------------- category picker

    @Test
    fun theCategoryRowOpensThePickerAndChoosingReturnsToTheForm() {
        seedCategory("Groceries", TransactionType.EXPENSE)
        render()

        openCategoryPicker()
        awaitText("Groceries")
        composeTestRule.onNodeWithText(text(R.string.category_picker_title)).assertIsDisplayed()

        composeTestRule.onNodeWithText("Groceries").performClick()

        // The picker is gone, and the form is showing the category that was chosen on it.
        composeTestRule.onNodeWithText(text(R.string.add_transaction_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Groceries").assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_category_placeholder))
            .assertDoesNotExist()
    }

    @Test
    fun thePickerOffersOnlyTheCategoriesOfTheFormsCurrentType() {
        seedCategory("Groceries", TransactionType.EXPENSE)
        seedCategory("Salary", TransactionType.INCOME)
        render(initialType = TransactionType.EXPENSE)

        openCategoryPicker()
        awaitText("Groceries")

        // An income category cannot hold an expense, so it is not offered for one.
        composeTestRule.onNodeWithText("Salary").assertDoesNotExist()
    }

    // ----------------------------------------------------------- validation

    @Test
    fun savingABlankAmountShowsTheErrorAndStoresNothing() {
        render()

        composeTestRule.onNodeWithText(text(R.string.add_transaction_save)).performClick()

        composeTestRule.onNodeWithText(text(R.string.add_transaction_amount_error_blank)).assertIsDisplayed()
        assertEquals(0, storedTransactions().size)
    }

    @Test
    fun savingWithoutACategoryShowsTheErrorAndStoresNothing() {
        render()

        typeInto(text(R.string.add_transaction_amount_label), "250")
        composeTestRule.onNodeWithText(text(R.string.add_transaction_save)).performClick()

        composeTestRule.onNodeWithText(text(R.string.add_transaction_category_required)).assertIsDisplayed()
        assertEquals(0, storedTransactions().size)
    }

    @Test
    fun anImpreciseAmountIsRefusedRatherThanRounded() {
        seedCategory("Groceries", TransactionType.EXPENSE)
        render()

        typeInto(text(R.string.add_transaction_amount_label), "100.999")
        chooseCategory("Groceries")
        composeTestRule.onNodeWithText(text(R.string.add_transaction_save)).performClick()

        composeTestRule.onNodeWithText(text(R.string.add_transaction_amount_error_too_precise)).assertIsDisplayed()
        assertEquals(0, storedTransactions().size)
    }

    // -------------------------------------------------- the real database path

    /**
     * The whole path, on a device: the form is filled the way a person fills it, Save is tapped,
     * and the row that ends up in SQLite is the one that was typed - to the paise - with the
     * screen leaving only once that write has landed.
     */
    @Test
    fun savingWritesTheTypedEntryAndLeavesTheScreenOnlyAfterwards() {
        seedCategory("Groceries", TransactionType.EXPENSE)
        val saved = AtomicBoolean(false)
        render(onSaved = { saved.set(true) })

        typeInto(text(R.string.add_transaction_amount_label), "250.50")
        chooseCategory("Groceries")
        typeInto(text(R.string.add_transaction_description_label), "Weekly shop")
        typeInto(text(R.string.add_transaction_notes_label), "Vegetables and fruit")
        composeTestRule.onNodeWithText(text(R.string.add_transaction_save)).performClick()

        val stored = awaitStoredTransactions()
        assertEquals(1, stored.size)
        assertEquals(25_050L, stored.single().amountPaise)
        assertEquals(TransactionType.EXPENSE, stored.single().type)
        assertEquals("Weekly shop", stored.single().description)
        // Notes are their own column: the row's one-line summary is not the whole of what was
        // written.
        assertEquals("Vegetables and fruit", stored.single().notes)
        // The screen leaves only once the write has actually landed.
        composeTestRule.waitUntil(timeoutMillis = 10_000) { saved.get() }
    }

    // ------------------------------------------------------ quantity and rate

    @Test
    fun theSwitchRevealsTheQuantityAndRateAndTheAmountFollowsThem() {
        render()

        typeInto(text(R.string.add_transaction_amount_label), "250")
        composeTestRule.onNode(isToggleable()).performClick()

        composeTestRule.onNode(isToggleable()).assertIsOn()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_quantity_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_rate_label)).assertIsDisplayed()

        typeInto(text(R.string.add_transaction_quantity_label), "1.5")
        typeInto(text(R.string.add_transaction_rate_label), "60")

        // The amount field stops being typed into and starts reporting what the two figures make:
        // 1.5 litres at ₹60.00 is ₹90.00.
        composeTestRule.onNodeWithText("₹90.00").assertIsDisplayed()
        composeTestRule.onNodeWithText("250").assertDoesNotExist()
    }

    @Test
    fun aQuantityAndRateEntryIsSavedAsTheProduct() {
        seedCategory("Milk", TransactionType.EXPENSE)
        render()

        composeTestRule.onNode(isToggleable()).performClick()
        typeInto(text(R.string.add_transaction_quantity_label), "1.5")
        typeInto(text(R.string.add_transaction_rate_label), "60")
        chooseCategory("Milk")
        composeTestRule.onNodeWithText(text(R.string.add_transaction_save)).performClick()

        val stored = awaitStoredTransactions().single()
        assertEquals(9_000L, stored.amountPaise)
        assertEquals(1_500L, stored.quantityThousandths)
        assertEquals(6_000L, stored.ratePaise)
    }

    @Test
    fun aProductThatRoundsRoundsTheSameWayThePreviewShowedIt() {
        seedCategory("Milk", TransactionType.EXPENSE)
        render()

        composeTestRule.onNode(isToggleable()).performClick()
        // 0.333 units at ₹59.99 is 1997.667 paise, which the form shows as ₹19.98.
        typeInto(text(R.string.add_transaction_quantity_label), "0.333")
        typeInto(text(R.string.add_transaction_rate_label), "59.99")

        composeTestRule.onNodeWithText("₹19.98").assertIsDisplayed()

        chooseCategory("Milk")
        composeTestRule.onNodeWithText(text(R.string.add_transaction_save)).performClick()

        // The row stores exactly the figure the household was shown.
        assertEquals(1_998L, awaitStoredTransactions().single().amountPaise)
    }

    // ---------------------------------------------------------- fresh install

    @Test
    fun aFreshInstallCanCreateItsFirstCategoryAndSaveAnEntry() {
        // No categories at all, which is exactly what a household sees on the day they install.
        render()

        openCategoryPicker()
        awaitText(text(R.string.category_picker_empty))

        // The picker's empty state is the one place the first category can come from.
        composeTestRule.onNodeWithText(text(R.string.category_picker_empty_action)).performClick()
        composeTestRule.onNodeWithText(text(R.string.add_category_name_label)).performTextInput("Milk")
        composeTestRule.onNodeWithText(text(R.string.add_category_create)).performClick()

        // The dialog closes once the category has been created, and creating it also selects it.
        awaitGone(text(R.string.add_category_dialog_title))
        awaitText("Milk")

        // Back to the form, which is now showing the category that was just made.
        composeTestRule.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        awaitText(text(R.string.add_transaction_title))

        typeInto(text(R.string.add_transaction_amount_label), "45")
        composeTestRule.onNodeWithText(text(R.string.add_transaction_save)).performClick()

        val stored = awaitStoredTransactions()
        assertEquals(4_500L, stored.single().amountPaise)
        assertEquals(TransactionType.EXPENSE, stored.single().type)
    }

    @Test
    fun reusingARetiredCategoryNameIsReportedAsADuplicate() {
        // A retired category is not offered for a new entry, but its name is still taken - the
        // unique index cannot be talked out of that, and the dialog has to say so.
        insertCategory(category("Milk", TransactionType.EXPENSE, isActive = false))
        render()

        openCategoryPicker()
        awaitText(text(R.string.category_picker_empty))

        composeTestRule.onNodeWithText(text(R.string.category_picker_empty_action)).performClick()
        composeTestRule.onNodeWithText(text(R.string.add_category_name_label)).performTextInput("milk")
        composeTestRule.onNodeWithText(text(R.string.add_category_create)).performClick()

        awaitText(text(R.string.add_category_error_duplicate))
    }

    @Test
    fun backingOutOfThePickerChangesNothing() {
        seedCategory("Groceries", TransactionType.EXPENSE)
        render()

        openCategoryPicker()
        awaitText("Groceries")
        composeTestRule.onNodeWithContentDescription(text(R.string.action_back)).performClick()

        awaitText(text(R.string.add_transaction_title))
        // Opening the picker must never write anything, and backing out must leave nothing chosen.
        assertEquals(0, storedTransactions().size)
        composeTestRule.onNodeWithText(text(R.string.add_transaction_category_placeholder))
            .assertIsDisplayed()
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * Renders the real add-transaction graph over an in-memory database.
     *
     * The view model factory is the one production does not use - the overload that takes the
     * repositories directly - so that the form is wired to a throwaway file rather than the
     * household's own ledger. Everything else is the shipped wiring.
     */
    private fun render(
        initialType: TransactionType = TransactionType.EXPENSE,
        onSaved: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        val transactions = TransactionRepository(database.transactionDao(), database.categoryDao())
        val categories = CategoryRepository(database.categoryDao())

        composeTestRule.setContent {
            HomeFinanceTheme {
                val navController = rememberNavController()
                // The flow's graph is the host's start destination here, because this test renders
                // nothing else: in the app the graph hangs off the whole NavHost instead, and the
                // form is reached by navigating to a route inside it. Either way the graph entry is
                // on the back stack, which is what the two screens share their view model through.
                NavHost(navController = navController, startDestination = AddTransactionGraph) {
                    addTransactionFlow(
                        navController = navController,
                        viewModelFactory = AddTransactionViewModel.factory(
                            initialType = initialType,
                            transactions = transactions,
                            categories = categories,
                        ),
                        onBack = onBack,
                        onSaved = onSaved,
                    )
                }
            }
        }
    }

    /** Opens the picker the way the household does: by tapping the Category row. */
    private fun openCategoryPicker() {
        composeTestRule.onNodeWithText(text(R.string.add_transaction_category_placeholder))
            .performClick()
    }

    /** Opens the picker, chooses [name], and waits for the form to show it coming back. */
    private fun chooseCategory(name: String) {
        openCategoryPicker()
        awaitText(name)
        composeTestRule.onNodeWithText(name).performClick()
        awaitText(text(R.string.add_transaction_title))
    }

    /** Types into the field whose label is [label], found by the field's accessible name. */
    private fun typeInto(label: String, value: String) {
        composeTestRule.onNodeWithContentDescription(label).performTextInput(value)
    }

    private fun seedCategory(name: String, type: TransactionType): Long =
        insertCategory(category(name, type))

    private fun insertCategory(category: CategoryEntity): Long =
        runBlocking { database.categoryDao().insert(category) }

    private fun storedTransactions(): List<TransactionEntity> =
        runBlocking { database.transactionDao().observeAll().first() }

    private fun awaitStoredTransactions(): List<TransactionEntity> {
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            runBlocking { database.transactionDao().observeAll().first().isNotEmpty() }
        }
        val stored = storedTransactions()
        assertTrue("expected exactly one stored transaction, was $stored", stored.size == 1)
        return stored
    }

    /** Waits for text to appear, which is how these tests wait for the database to push a change. */
    private fun awaitText(value: String) {
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(value).assertIsDisplayed()
    }

    /** Waits for text to disappear, used to tell that a dialog has closed. */
    private fun awaitGone(value: String) {
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText(value).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun text(id: Int): String = resources.getString(id)
}
