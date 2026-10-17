package com.homefinance.app.feature.addtransaction

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The add-income / add-expense form, rendered on a device.
 *
 * Most of the form's behaviour is pinned by [AddTransactionViewModelTest] on the JVM, where it is
 * deterministic and fast. What a JVM test cannot see is whether the state actually reaches pixels,
 * so this class renders the real composable against a real in-memory SQLite database and asserts
 * what is on screen - and, for the one path only a device can prove, that tapping Save writes
 * exactly the row it should.
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
        seedCategory("Groceries", TransactionType.EXPENSE)
        render()

        awaitText("Groceries")

        composeTestRule.onNodeWithText(text(R.string.add_transaction_title_expense)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_type_income)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_type_expense)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_amount_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_category_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_description_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_save)).assertIsDisplayed()
        // The date starts at today and is shown the way the rest of the app formats dates.
        composeTestRule.onNodeWithText(DateFormatter.format(LocalDate.now())).assertIsDisplayed()
    }

    @Test
    fun openingForIncomeStartsOnIncomeWithIncomeCategories() {
        seedCategory("Salary", TransactionType.INCOME)
        render(initialType = TransactionType.INCOME)

        awaitText("Salary")

        composeTestRule.onNodeWithText(text(R.string.add_transaction_title_income)).assertIsDisplayed()
        // An expense category is not offered for an income entry; there are none seeded here.
        composeTestRule.onNodeWithText("Groceries").assertDoesNotExist()
    }

    @Test
    fun flippingToIncomeOffersOnlyIncomeCategories() {
        seedCategory("Groceries", TransactionType.EXPENSE)
        seedCategory("Salary", TransactionType.INCOME)
        render(initialType = TransactionType.EXPENSE)

        awaitText("Groceries")
        composeTestRule.onNodeWithText("Salary").assertDoesNotExist()

        composeTestRule.onNodeWithText(text(R.string.add_transaction_type_income)).performClick()

        // The chip list reloads from the database for the new type.
        awaitText("Salary")
        composeTestRule.onNodeWithText(text(R.string.add_transaction_title_income)).assertIsDisplayed()
    }

    @Test
    fun theDateFieldOpensTheDatePicker() {
        seedCategory("Groceries", TransactionType.EXPENSE)
        render()

        awaitText("Groceries")

        composeTestRule.onNodeWithContentDescription(text(R.string.add_transaction_date_label))
            .performClick()

        composeTestRule.onNodeWithText(text(R.string.action_ok)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.action_cancel)).assertIsDisplayed()
    }

    // ----------------------------------------------------------- validation

    @Test
    fun savingABlankAmountShowsTheErrorAndStoresNothing() {
        seedCategory("Groceries", TransactionType.EXPENSE)
        render()

        awaitText("Groceries")
        composeTestRule.onNodeWithText(text(R.string.add_transaction_save)).performClick()

        composeTestRule.onNodeWithText(text(R.string.add_transaction_amount_error_blank)).assertIsDisplayed()
        assertEquals(0, storedTransactions().size)
    }

    @Test
    fun savingWithoutACategoryShowsTheErrorAndStoresNothing() {
        seedCategory("Groceries", TransactionType.EXPENSE)
        render()

        awaitText("Groceries")
        composeTestRule.onNodeWithText(text(R.string.add_transaction_amount_label)).performTextInput("250")
        composeTestRule.onNodeWithText(text(R.string.add_transaction_save)).performClick()

        composeTestRule.onNodeWithText(text(R.string.add_transaction_category_required)).assertIsDisplayed()
        assertEquals(0, storedTransactions().size)
    }

    @Test
    fun anImpreciseAmountIsRefusedRatherThanRounded() {
        seedCategory("Groceries", TransactionType.EXPENSE)
        render()

        awaitText("Groceries")
        composeTestRule.onNodeWithText(text(R.string.add_transaction_amount_label)).performTextInput("100.999")
        composeTestRule.onNodeWithText("Groceries").performClick()
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

        awaitText("Groceries")
        composeTestRule.onNodeWithText(text(R.string.add_transaction_amount_label)).performTextInput("250.50")
        composeTestRule.onNodeWithText("Groceries").performClick()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_description_label))
            .performTextInput("Weekly shop")
        composeTestRule.onNodeWithText(text(R.string.add_transaction_save)).performClick()

        val stored = awaitStoredTransactions()
        assertEquals(1, stored.size)
        assertEquals(25_050L, stored.single().amountPaise)
        assertEquals(TransactionType.EXPENSE, stored.single().type)
        assertEquals("Weekly shop", stored.single().description)
        // The screen leaves only once the write has actually landed.
        composeTestRule.waitUntil(timeoutMillis = 10_000) { saved.get() }
    }

    // ---------------------------------------------------------- fresh install

    @Test
    fun aFreshInstallCanCreateItsFirstCategoryAndSaveAnEntry() {
        // No categories at all, which is exactly what a household sees on the day they install.
        render()

        awaitText(text(R.string.add_transaction_category_empty))

        composeTestRule.onNodeWithText(text(R.string.add_category_action)).performClick()
        composeTestRule.onNodeWithText(text(R.string.add_category_name_label)).performTextInput("Milk")
        composeTestRule.onNodeWithText(text(R.string.add_category_create)).performClick()

        // The dialog closes once the category has been created, and creating it also selects it,
        // so the chip appears without another tap.
        awaitGone(text(R.string.add_category_dialog_title))
        awaitText("Milk")

        composeTestRule.onNodeWithText(text(R.string.add_transaction_amount_label)).performTextInput("45")
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

        awaitText(text(R.string.add_transaction_category_empty))

        composeTestRule.onNodeWithText(text(R.string.add_category_action)).performClick()
        composeTestRule.onNodeWithText(text(R.string.add_category_name_label)).performTextInput("milk")
        composeTestRule.onNodeWithText(text(R.string.add_category_create)).performClick()

        awaitText(text(R.string.add_category_error_duplicate))
    }

    // ---------------------------------------------------------------- fixtures

    private fun render(
        initialType: TransactionType = TransactionType.EXPENSE,
        onSaved: () -> Unit = {},
    ) {
        val viewModel = AddTransactionViewModel(
            transactions = TransactionRepository(database.transactionDao(), database.categoryDao()),
            categories = CategoryRepository(database.categoryDao()),
            initialType = initialType,
        )

        composeTestRule.setContent {
            HomeFinanceTheme {
                AddTransactionScreen(
                    initialType = initialType,
                    onBack = {},
                    onSaved = onSaved,
                    viewModel = viewModel,
                )
            }
        }
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
        return storedTransactions()
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
