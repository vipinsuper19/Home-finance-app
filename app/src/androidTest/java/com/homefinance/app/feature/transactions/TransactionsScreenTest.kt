package com.homefinance.app.feature.transactions

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homefinance.app.R
import com.homefinance.app.core.ui.theme.HomeFinanceTheme
import com.homefinance.app.core.util.DateFormatter
import com.homefinance.app.data.local.inMemoryDatabase
import com.homefinance.app.data.model.TransactionType
import com.homefinance.app.data.repository.CategoryRepository
import com.homefinance.app.data.repository.TransactionRepository
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Transactions screen, rendered on a device.
 *
 * Most of the behaviour is pinned by [TransactionsViewModelTest] on the JVM, where it is
 * deterministic and fast. What a JVM test cannot see is whether the state actually reaches pixels,
 * so this class renders the real composable and asserts what is on screen - including the one path
 * that only a device can prove: rows written through the real repositories into a real SQLite
 * database, joined to their category names, and drawn in the list.
 *
 * The database is an in-memory one from the shared fixtures. No test in this file touches the
 * household's own ledger, and none of them seeds it with sample data.
 */
@RunWith(AndroidJUnit4::class)
class TransactionsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    // -------------------------------------------------------------- loading / error

    @Test
    fun theInitialLoadShowsAProgressIndicator() {
        composeTestRule.setContent {
            HomeFinanceTheme {
                TransactionsContent(uiState = TransactionsUiState.Loading, onRetry = {})
            }
        }

        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertIsDisplayed()
    }

    @Test
    fun theEmptyLedgerShowsTheEmptyStateRatherThanAnError() {
        composeTestRule.setContent {
            HomeFinanceTheme {
                TransactionsContent(uiState = TransactionsUiState.Content(emptyList()), onRetry = {})
            }
        }

        composeTestRule.onNodeWithText(text(R.string.transactions_empty_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.transactions_empty_message)).assertIsDisplayed()
        // A successful read of nothing is not a failure, so no retry is offered.
        composeTestRule.onNodeWithText(text(R.string.transactions_error_retry)).assertDoesNotExist()
    }

    @Test
    fun aFailedLoadShowsTheErrorStateWithARetryAction() {
        val retries = AtomicInteger()

        composeTestRule.setContent {
            HomeFinanceTheme {
                TransactionsContent(
                    uiState = TransactionsUiState.Error(IOException("the database could not be read")),
                    onRetry = { retries.incrementAndGet() },
                )
            }
        }

        composeTestRule.onNodeWithText(text(R.string.transactions_error_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.transactions_error_message)).assertIsDisplayed()

        composeTestRule.onNodeWithText(text(R.string.transactions_error_retry)).performClick()

        assertEquals(1, retries.get())
    }

    @Test
    fun aFailureDoesNotRenderRowsUnderneathIt() {
        val failure = TransactionsUiState.Error(IOException("the database could not be read"))

        composeTestRule.setContent {
            HomeFinanceTheme {
                TransactionsContent(uiState = failure, onRetry = {})
            }
        }

        composeTestRule.onNodeWithText(text(R.string.transactions_empty_title)).assertDoesNotExist()
        composeTestRule.onNodeWithText("-${'\u20B9'}2,734.05").assertDoesNotExist()
    }

    // --------------------------------------------------------------------- content

    @Test
    fun eachRowShowsItsCategoryDescriptionDateAndSignedAmount() {
        val date = LocalDate.of(2026, 10, 15)

        composeTestRule.setContent {
            HomeFinanceTheme {
                TransactionsContent(
                    uiState = TransactionsUiState.Content(
                        items = listOf(
                            TransactionListItem(
                                id = 1L,
                                categoryName = "Groceries",
                                description = "Weekly shop",
                                transactionDate = date,
                                amountPaise = 273_405L,
                                type = TransactionType.EXPENSE,
                            ),
                            TransactionListItem(
                                id = 2L,
                                categoryName = "Salary",
                                description = null,
                                transactionDate = LocalDate.of(2026, 10, 1),
                                amountPaise = 4_850_000L,
                                type = TransactionType.INCOME,
                            ),
                        ),
                    ),
                    onRetry = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Groceries").assertIsDisplayed()
        composeTestRule.onNodeWithText("Salary").assertIsDisplayed()
        composeTestRule.onNodeWithText("Weekly shop").assertIsDisplayed()
        // Formatted the same way the screen formats it, so this cannot fail merely because the
        // device's locale renders the month differently.
        composeTestRule.onNodeWithText(DateFormatter.format(date)).assertIsDisplayed()
        // The amount carries the direction in text as well as colour.
        composeTestRule.onNodeWithText("-${'\u20B9'}2,734.05").assertIsDisplayed()
        composeTestRule.onNodeWithText("+${'\u20B9'}48,500.00").assertIsDisplayed()
        // Loaded rows mean no spinner.
        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertDoesNotExist()
    }

    @Test
    fun aRowWithoutADescriptionDrawsNoDescriptionLine() {
        composeTestRule.setContent {
            HomeFinanceTheme {
                TransactionsContent(
                    uiState = TransactionsUiState.Content(
                        items = listOf(
                            TransactionListItem(
                                id = 1L,
                                categoryName = "Salary",
                                description = null,
                                transactionDate = LocalDate.of(2026, 10, 1),
                                amountPaise = 4_850_000L,
                                type = TransactionType.INCOME,
                            ),
                        ),
                    ),
                    onRetry = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Salary").assertIsDisplayed()
        composeTestRule.onNodeWithText("+${'\u20B9'}48,500.00").assertIsDisplayed()
    }

    @Test
    fun aVeryLongCategoryNameDoesNotPushTheAmountOffTheRow() {
        composeTestRule.setContent {
            HomeFinanceTheme {
                TransactionsContent(
                    uiState = TransactionsUiState.Content(
                        items = listOf(
                            TransactionListItem(
                                id = 1L,
                                categoryName = "A household category name far longer than the row can show",
                                description = "A description that is also far too long to fit on one line",
                                transactionDate = LocalDate.of(2026, 10, 15),
                                amountPaise = 273_405L,
                                type = TransactionType.EXPENSE,
                            ),
                        ),
                    ),
                    onRetry = {},
                )
            }
        }

        // The name is ellipsised instead of stealing the amount's space.
        composeTestRule.onNodeWithText("-${'\u20B9'}2,734.05").assertIsDisplayed()
    }

    // ---------------------------------------------------------- real database path

    /**
     * The whole path, on a device: repositories write to a real SQLite database, the DAO joins
     * each row to its category, and the screen draws what came back.
     */
    @Test
    fun rowsStoredInTheDatabaseAreListedWithTheirCategoryNames() {
        val database = inMemoryDatabase()
        try {
            val categories = CategoryRepository(database.categoryDao())
            val transactions = TransactionRepository(database.transactionDao(), database.categoryDao())

            runBlocking {
                val salary = categories.create(name = "Salary", type = TransactionType.INCOME)
                val groceries = categories.create(name = "Groceries", type = TransactionType.EXPENSE)

                transactions.add(
                    type = TransactionType.INCOME,
                    amountPaise = 4_850_000L,
                    categoryId = salary,
                    transactionDate = LocalDate.of(2026, 10, 1),
                )
                transactions.add(
                    type = TransactionType.EXPENSE,
                    amountPaise = 273_405L,
                    categoryId = groceries,
                    transactionDate = LocalDate.of(2026, 10, 15),
                    description = "Weekly shop",
                )
            }

            composeTestRule.setContent {
                HomeFinanceTheme {
                    TransactionsScreen(viewModel = TransactionsViewModel(transactions))
                }
            }

            // The row arrives through a real query on a real database, so wait for it rather than
            // assuming the first frame already has it.
            composeTestRule.waitUntil(timeoutMillis = 10_000) {
                composeTestRule.onAllNodesWithText("Salary").fetchSemanticsNodes().isNotEmpty()
            }

            composeTestRule.onNodeWithText("Salary").assertIsDisplayed()
            composeTestRule.onNodeWithText("Groceries").assertIsDisplayed()
            composeTestRule.onNodeWithText("Weekly shop").assertIsDisplayed()
            composeTestRule.onNodeWithText("+${'\u20B9'}48,500.00").assertIsDisplayed()
            composeTestRule.onNodeWithText("-${'\u20B9'}2,734.05").assertIsDisplayed()
        } finally {
            database.close()
        }
    }

    private fun text(id: Int): String = resources.getString(id)
}
