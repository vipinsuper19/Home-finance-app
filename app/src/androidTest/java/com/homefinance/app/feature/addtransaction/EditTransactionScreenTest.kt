package com.homefinance.app.feature.addtransaction

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.material3.Text
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homefinance.app.R
import com.homefinance.app.core.ui.theme.HomeFinanceTheme
import com.homefinance.app.core.util.DateFormatter
import com.homefinance.app.data.local.HomeFinanceDatabase
import com.homefinance.app.data.local.TEST_DATE
import com.homefinance.app.data.local.TEST_INSTANT
import com.homefinance.app.data.local.category
import com.homefinance.app.data.local.inMemoryDatabase
import com.homefinance.app.data.local.transaction
import com.homefinance.app.data.local.entity.TransactionEntity
import com.homefinance.app.data.model.TransactionType
import com.homefinance.app.data.repository.CategoryRepository
import com.homefinance.app.data.repository.TransactionRepository
import com.homefinance.app.navigation.EditTransactionRoute
import com.homefinance.app.navigation.TransactionsRoute
import com.homefinance.app.navigation.transactionFormFlow
import java.util.concurrent.atomic.AtomicInteger
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
 * The edit form, rendered on a device, inside the real transaction form graph.
 *
 * The two claims that matter for an edit are both about the database, and neither can be proved
 * without one: that the form is filled in from the row it says it is changing, and that saving
 * rewrites *that* row - same id, same `createdAt`, one row - rather than recording a second entry
 * beside it. Everything else about the state machine is pinned on the JVM by
 * [AddTransactionViewModelTest]; what a JVM test cannot see is whether the figures reach the fields.
 *
 * Like [AddTransactionScreenTest], this renders the **real** graph - the real routes, and the one
 * view model the form and the picker share - over a throwaway in-memory database, and never touches
 * the household's own ledger.
 */
@RunWith(AndroidJUnit4::class)
class EditTransactionScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var database: HomeFinanceDatabase

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private val finishes = AtomicInteger()

    private val backs = AtomicInteger()

    @Before
    fun setUp() {
        database = inMemoryDatabase()
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ---------------------------------------------------------------- reading

    @Test
    fun theFormIsFilledInFromTheEntryItIsChanging() {
        val categoryId = seedCategory("Groceries", TransactionType.EXPENSE)
        val id = insertTransaction(
            transaction(
                categoryId,
                amountPaise = 9_000L,
                transactionDate = TEST_DATE,
                description = "Milk",
                notes = "Two bottles",
            ),
        )

        render(id)
        awaitText(text(R.string.edit_transaction_save))

        // The title and the button say what this screen does, and they are not the add form's words.
        composeTestRule.onNodeWithText(text(R.string.edit_transaction_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.edit_transaction_save)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_save)).assertDoesNotExist()

        // Every figure, exactly as it was recorded - the amount as a plain figure the parser would
        // accept, not as the formatted string the ledger draws.
        composeTestRule.onNodeWithText("90").assertIsDisplayed()
        composeTestRule.onNodeWithText("Milk").assertIsDisplayed()
        composeTestRule.onNodeWithText("Two bottles").assertIsDisplayed()
        composeTestRule.onNodeWithText("Groceries").assertIsDisplayed()
        composeTestRule.onNodeWithText(DateFormatter.format(TEST_DATE)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_type_expense)).assertIsSelected()
    }

    @Test
    fun anEntryRecordedByQuantityAndRateIsPrefilledWithTheSwitchOn() {
        val categoryId = seedCategory("Milk", TransactionType.EXPENSE)
        val id = insertTransaction(
            transaction(
                categoryId,
                amountPaise = 9_000L,
                quantityThousandths = 1_500L,
                ratePaise = 6_000L,
            ),
        )

        render(id)
        awaitText(text(R.string.edit_transaction_save))

        // The optional mode is on because this entry was recorded with it, and both figures are back
        // - so saving without touching anything must not quietly drop them.
        composeTestRule.onNode(isToggleable()).assertIsOn()
        composeTestRule.onNodeWithText("1.5").assertIsDisplayed()
        composeTestRule.onNodeWithText("60").assertIsDisplayed()
    }

    @Test
    fun anEntryThatIsNotThereSaysSoRatherThanOfferingAnEmptyForm() {
        render(transactionId = 42L)

        awaitText(text(R.string.edit_transaction_not_found_title))

        // The dangerous alternative: an empty form over a transaction that was never read, which the
        // household could save and thereby create an entry they did not mean to make.
        composeTestRule.onNodeWithText(text(R.string.edit_transaction_save)).assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_amount_label)).assertDoesNotExist()
    }

    // ---------------------------------------------------------------- writing

    @Test
    fun changingTheFigureAndSavingRewritesTheSameEntry() {
        val categoryId = seedCategory("Groceries", TransactionType.EXPENSE)
        val id = insertTransaction(
            transaction(categoryId, amountPaise = 9_000L, description = "Milk"),
        )

        render(id)
        awaitText(text(R.string.edit_transaction_save))

        replaceIn(text(R.string.add_transaction_amount_label), "125")
        composeTestRule.onNodeWithText(text(R.string.edit_transaction_save)).performClick()

        // The form leaves on the write's own answer, so wait for the form to leave rather than for the
        // row to change: the row is written first, and asserting on it could catch the screen still
        // holding the form it should have left.
        composeTestRule.waitUntil(timeoutMillis = 10_000) { finishes.get() == 1 }
        awaitStored(id) { it.amountPaise == 12_500L }

        val stored = storedTransactions()
        assertEquals("expected one rewritten entry, was $stored", 1, stored.size)
        assertEquals(id, stored.single().id)
        assertEquals(12_500L, stored.single().amountPaise)
        assertEquals("Milk", stored.single().description)
        // The day it was first recorded survives; the day it was last changed does not pretend to be
        // that day. The exact instant is the device's own clock, which is not this test's business.
        assertEquals(TEST_INSTANT, stored.single().createdAt)
        assertTrue(stored.single().updatedAt != TEST_INSTANT)
    }

    @Test
    fun theTypeAndCategoryCanBothBeChanged() {
        val groceries = seedCategory("Groceries", TransactionType.EXPENSE)
        val salary = seedCategory("Salary", TransactionType.INCOME)
        val id = insertTransaction(transaction(groceries, amountPaise = 9_000L))

        render(id)
        awaitText(text(R.string.edit_transaction_save))

        composeTestRule.onNodeWithText(text(R.string.add_transaction_type_income)).performClick()
        awaitText(text(R.string.add_transaction_category_placeholder))
        composeTestRule.onNodeWithText(text(R.string.add_transaction_category_placeholder)).performClick()
        awaitText("Salary")
        composeTestRule.onNodeWithText("Salary").performClick()
        awaitText(text(R.string.edit_transaction_save))

        composeTestRule.onNodeWithText(text(R.string.edit_transaction_save)).performClick()

        awaitStored(id) { it.type == TransactionType.INCOME }

        val stored = storedTransactions().single()
        assertEquals(TransactionType.INCOME, stored.type)
        assertEquals(salary, stored.categoryId)
        assertEquals(TEST_INSTANT, stored.createdAt)
    }

    @Test
    fun theCategoryPickerOpensFromTheEditFormAndItsChoiceComesBack() {
        val groceries = seedCategory("Groceries", TransactionType.EXPENSE)
        val travel = seedCategory("Travel", TransactionType.EXPENSE)
        val id = insertTransaction(transaction(groceries, amountPaise = 9_000L))

        render(id)
        awaitText(text(R.string.edit_transaction_save))

        // The category the entry already has is the way into the picker - there is no separate
        // "change" control, because the row that shows the category is the row that chooses it.
        composeTestRule.onNodeWithText("Groceries").performClick()
        awaitText("Travel")
        composeTestRule.onNodeWithText("Travel").performClick()

        // The picker goes and the form is left holding its choice, with nothing passed back through
        // navigation - the two screens share one view model, which is the point of the graph.
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("Travel").fetchSemanticsNodes().size == 1
        }
        composeTestRule.onNodeWithText(text(R.string.edit_transaction_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Travel").assertIsDisplayed()

        composeTestRule.onNodeWithText(text(R.string.edit_transaction_save)).performClick()

        awaitStored(id) { it.categoryId == travel }
        assertEquals(travel, storedTransactions().single().categoryId)
    }

    @Test
    fun backingOutWritesNothing() {
        val categoryId = seedCategory("Groceries", TransactionType.EXPENSE)
        val id = insertTransaction(transaction(categoryId, amountPaise = 9_000L, description = "Milk"))

        render(id)
        awaitText(text(R.string.edit_transaction_save))

        replaceIn(text(R.string.add_transaction_amount_label), "125")
        replaceIn(text(R.string.add_transaction_description_label), "Bread")

        composeTestRule.onNodeWithContentDescription(text(R.string.action_back)).performClick()

        assertEquals(1, backs.get())
        assertEquals(0, finishes.get())
        // Nothing was written, because nothing is written until Save is tapped: there is no draft
        // sitting in the database waiting to be discarded.
        val stored = storedTransactions().single()
        assertEquals(9_000L, stored.amountPaise)
        assertEquals("Milk", stored.description)
        assertEquals(TEST_INSTANT, stored.updatedAt)
    }

    @Test
    fun anAmountTheParserCannotReadIsRefusedOnTheFormAndNothingIsWritten() {
        val categoryId = seedCategory("Groceries", TransactionType.EXPENSE)
        val id = insertTransaction(transaction(categoryId, amountPaise = 9_000L))

        render(id)
        awaitText(text(R.string.edit_transaction_save))

        replaceIn(text(R.string.add_transaction_amount_label), "12.345")
        composeTestRule.onNodeWithText(text(R.string.edit_transaction_save)).performClick()

        awaitText(text(R.string.add_transaction_amount_error_too_precise))
        assertEquals(0, finishes.get())
        assertEquals(9_000L, storedTransactions().single().amountPaise)
    }

    @Test
    fun anEntryDeletedWhileTheFormWasOpenIsReportedRatherThanRecordedAgain() {
        val categoryId = seedCategory("Groceries", TransactionType.EXPENSE)
        val id = insertTransaction(transaction(categoryId, amountPaise = 9_000L))

        render(id)
        awaitText(text(R.string.edit_transaction_save))

        // Something else - or somebody else's finger - removed the entry while the form was open.
        runBlocking { database.transactionDao().deleteById(id) }

        replaceIn(text(R.string.add_transaction_amount_label), "125")
        composeTestRule.onNodeWithText(text(R.string.edit_transaction_save)).performClick()

        awaitText(text(R.string.add_transaction_save_error_missing))

        // The refusal must not have been resolved by inserting: the household would be left with a
        // transaction they never recorded, under a date they never chose.
        assertEquals(0, storedTransactions().size)
        assertEquals(0, finishes.get())
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * Renders the real transaction form graph over an in-memory database, and enters it at the edit
     * form.
     *
     * A form is entered from *outside* the graph - from the ledger, or from one transaction's details
     * - and that is what gives it a fresh view model: the graph's own back stack entry is pushed as
     * part of the navigation. This test pieces the same thing together, a placeholder ledger then a
     * navigation into the edit route, because starting the host *on* a route inside the graph would
     * both be refused by the navigator and skip the step that makes the form fresh.
     */
    private fun render(
        transactionId: Long,
        onBack: () -> Unit = { backs.incrementAndGet() },
        onFinished: () -> Unit = { finishes.incrementAndGet() },
    ) {
        val transactions = TransactionRepository(database.transactionDao(), database.categoryDao())
        val categories = CategoryRepository(database.categoryDao())
        lateinit var navController: NavHostController

        composeTestRule.setContent {
            HomeFinanceTheme {
                navController = rememberNavController()
                NavHost(navController = navController, startDestination = TransactionsRoute) {
                    composable<TransactionsRoute> { Text(text = "ledger") }
                    transactionFormFlow(
                        navController = navController,
                        addViewModelFactory = AddTransactionViewModel.factory(
                            initialType = TransactionType.EXPENSE,
                            transactions = transactions,
                            categories = categories,
                        ),
                        editViewModelFactory = { id ->
                            AddTransactionViewModel.factory(
                                initialType = TransactionType.EXPENSE,
                                transactions = transactions,
                                categories = categories,
                                editingId = id,
                            )
                        },
                        onBack = onBack,
                        onSaved = {},
                        onEdited = onFinished,
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread {
            navController.navigate(EditTransactionRoute(transactionId))
        }
        composeTestRule.waitForIdle()
    }

    /** Replaces the whole contents of the field accessible as [label]. */
    private fun replaceIn(label: String, value: String) {
        composeTestRule.onNodeWithContentDescription(label).performTextClearance()
        composeTestRule.onNodeWithContentDescription(label).performTextInput(value)
    }

    private fun seedCategory(name: String, type: TransactionType): Long =
        runBlocking { database.categoryDao().insert(category(name, type)) }

    private fun insertTransaction(entity: TransactionEntity): Long =
        runBlocking { database.transactionDao().insert(entity) }

    private fun storedTransactions(): List<TransactionEntity> =
        runBlocking { database.transactionDao().observeAll().first() }

    /** The entry is written; waits for that to land, and to be the value asked for. */
    private fun awaitStored(id: Long, expected: (TransactionEntity) -> Boolean) {
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            runBlocking { database.transactionDao().getById(id)?.let(expected) == true }
        }
    }

    /** Waits for text to appear, which is how these tests wait for the database to push a change. */
    private fun awaitText(value: String) {
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun text(id: Int): String = resources.getString(id)
}
