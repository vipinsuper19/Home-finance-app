package com.homefinance.app.feature.transactions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasSetTextAction
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
import com.homefinance.app.data.local.inMemoryDatabase
import com.homefinance.app.data.model.TransactionType
import com.homefinance.app.data.repository.CategoryRepository
import com.homefinance.app.data.repository.TransactionRepository
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth
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
        render(TransactionsUiState.Loading)

        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertIsDisplayed()
    }

    @Test
    fun theEmptyLedgerShowsTheEmptyStateRatherThanAnError() {
        render(emptyLedger())

        composeTestRule.onNodeWithText(text(R.string.transactions_empty_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.transactions_empty_message)).assertIsDisplayed()
        // A successful read of nothing is not a failure, so no retry is offered.
        composeTestRule.onNodeWithText(text(R.string.transactions_error_retry)).assertDoesNotExist()
    }

    @Test
    fun aFailedLoadShowsTheErrorStateWithARetryAction() {
        val retries = AtomicInteger()

        render(
            uiState = TransactionsUiState.Error(IOException("the database could not be read")),
            onRetry = { retries.incrementAndGet() },
        )

        composeTestRule.onNodeWithText(text(R.string.transactions_error_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.transactions_error_message)).assertIsDisplayed()

        composeTestRule.onNodeWithText(text(R.string.transactions_error_retry)).performClick()

        assertEquals(1, retries.get())
    }

    @Test
    fun theEmptyLedgerOffersTheAddActionAndItOpensTheForm() {
        val adds = AtomicInteger()

        render(uiState = emptyLedger(), onAddTransaction = { adds.incrementAndGet() })

        // A fresh install has an empty ledger, so the add action must be offered here - not only
        // once there is already something to look at.
        composeTestRule.onNodeWithContentDescription(text(R.string.transactions_add))
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, adds.get())
    }

    @Test
    fun aFailureDoesNotRenderRowsUnderneathIt() {
        render(TransactionsUiState.Error(IOException("the database could not be read")))

        composeTestRule.onNodeWithText(text(R.string.transactions_empty_title)).assertDoesNotExist()
        composeTestRule.onNodeWithText("\u20B92,734.05").assertDoesNotExist()
    }

    // --------------------------------------------------------------------- content

    @Test
    fun tappingARowOpensThatRowAndNoOther() {
        val opened = mutableListOf<Long>()

        render(
            uiState = content(
                listOf(
                    group(
                        date = GROCERIES_DAY,
                        items = arrayOf(
                            item(id = 7L, categoryName = "Groceries", description = "Weekly shop"),
                            item(id = 8L, categoryName = "Salary", type = TransactionType.INCOME),
                        ),
                    ),
                ),
            ),
            onOpenTransaction = { opened += it },
        )

        // Tapped by its own text, which is what the household actually aims at - and the id that
        // comes back is the row's database id, not its position in the list.
        composeTestRule.onNodeWithText("Weekly shop").performClick()

        assertEquals(listOf(7L), opened)
    }

    @Test
    fun aRowIsAnnouncedAsSomethingThatCanBeOpened() {
        render(
            uiState = content(
                listOf(group(date = GROCERIES_DAY, items = arrayOf(item(id = 7L, categoryName = "Groceries")))),
            ),
        )

        // A row that opens something has to say so: without the button role a screen reader would
        // read out the figures and give the household no idea the row is actionable.
        composeTestRule.onNodeWithText("Groceries").assertHasClickAction()
    }

    @Test
    fun eachRowShowsItsCategoryDescriptionAndAmount() {
        render(
            content(
                listOf(
                    group(
                        date = GROCERIES_DAY,
                        items = arrayOf(
                            item(
                                id = 1L,
                                categoryName = "Groceries",
                                description = "Weekly shop",
                                amountPaise = 273_405L,
                                type = TransactionType.EXPENSE,
                                date = GROCERIES_DAY,
                            ),
                        ),
                    ),
                ),
            ),
        )

        composeTestRule.onNodeWithText("Groceries").assertIsDisplayed()
        composeTestRule.onNodeWithText("Weekly shop").assertIsDisplayed()
        // Formatted the same way the screen formats it, so this cannot fail merely because the
        // device's locale renders the month differently.
        composeTestRule.onNodeWithText(DateFormatter.format(GROCERIES_DAY)).assertIsDisplayed()
        // No sign: the direction of the money is the colour, and only the colour.
        composeTestRule.onNodeWithText("\u20B92,734.05").assertIsDisplayed()
        // Loaded rows mean no spinner.
        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertDoesNotExist()
    }

    @Test
    fun aRowWithoutADescriptionDrawsNoDescriptionLine() {
        render(
            content(
                listOf(
                    group(
                        date = SALARY_DAY,
                        items = arrayOf(
                            item(
                                id = 1L,
                                categoryName = "Salary",
                                description = null,
                                amountPaise = 4_850_000L,
                                type = TransactionType.INCOME,
                                date = SALARY_DAY,
                            ),
                        ),
                    ),
                ),
            ),
        )

        composeTestRule.onNodeWithText("Salary").assertIsDisplayed()
        composeTestRule.onNodeWithText("\u20B948,500.00").assertIsDisplayed()
    }

    @Test
    fun theDayHeadingNamesTheDateAndCallsOutToday() {
        render(
            content(
                listOf(
                    group(
                        date = GROCERIES_DAY,
                        relation = DateRelation.TODAY,
                        items = arrayOf(item(id = 1L, categoryName = "Groceries")),
                    ),
                ),
            ),
        )

        // Built the way the screen builds it, so the parenthetical is asserted without pinning the
        // wording of a string resource into this test as well.
        val expected = resources.getString(
            R.string.transactions_date_with_relation,
            DateFormatter.format(GROCERIES_DAY),
            resources.getString(R.string.transactions_date_today),
        )

        composeTestRule.onNodeWithText(expected).assertIsDisplayed()
    }

    @Test
    fun aVeryLongCategoryNameDoesNotPushTheAmountOffTheRow() {
        render(
            content(
                listOf(
                    group(
                        date = GROCERIES_DAY,
                        items = arrayOf(
                            item(
                                id = 1L,
                                categoryName = "A household category name far longer than the row can show",
                                description = "A description that is also far too long to fit on one line",
                            ),
                        ),
                    ),
                ),
            ),
        )

        // The name is ellipsised instead of stealing the amount's space.
        composeTestRule.onNodeWithText("\u20B910.00").assertIsDisplayed()
    }

    // ---------------------------------------------------------------------- filters

    @Test
    fun theSearchBoxIsShownOnceThereIsSomethingToSearch() {
        var query by mutableStateOf("")
        val typed = mutableListOf<String>()

        render(
            uiState = content(
                listOf(group(date = GROCERIES_DAY, items = arrayOf(item(id = 1L, categoryName = "Groceries")))),
                query = query,
            ),
            onQueryChange = { query = it; typed += it },
        )

        composeTestRule.onNodeWithText(text(R.string.transactions_search_placeholder)).assertIsDisplayed()

        composeTestRule.onNode(hasSetTextAction()).performTextInput("groc")

        assertEquals("groc", typed.last())
    }

    @Test
    fun anEmptyLedgerOffersNoSearchBoxAndNoFilters() {
        render(emptyLedger())

        // There is nothing to filter, so furniture that could only ever return nothing is left out.
        composeTestRule.onNodeWithText(text(R.string.transactions_search_placeholder)).assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.transactions_filter_all)).assertDoesNotExist()
    }

    @Test
    fun theDirectionChipsShowWhichOneIsChosenAndReportAClick() {
        val chosen = mutableListOf<TransactionTypeFilter>()

        render(
            uiState = content(
                listOf(group(date = GROCERIES_DAY, items = arrayOf(item(id = 1L, categoryName = "Groceries")))),
                typeFilter = TransactionTypeFilter.ALL,
            ),
            onTypeFilterChange = { chosen += it },
        )

        composeTestRule.onNodeWithText(text(R.string.transactions_filter_all)).assertIsSelected()
        composeTestRule.onNodeWithText(text(R.string.transactions_filter_income)).assertIsDisplayed()

        composeTestRule.onNodeWithText(text(R.string.transactions_filter_expense)).performClick()

        assertEquals(listOf(TransactionTypeFilter.EXPENSE), chosen)
    }

    @Test
    fun theMonthChipShowsTheMonthAndOffersTheOtherMonthsThatHaveEntries() {
        val chosenMonths = mutableListOf<YearMonth>()
        val thisMonth = YearMonth.from(GROCERIES_DAY)
        val lastMonth = thisMonth.minusMonths(1)

        render(
            uiState = content(
                listOf(group(date = GROCERIES_DAY, items = arrayOf(item(id = 1L, categoryName = "Groceries")))),
                month = thisMonth,
                monthOptions = listOf(thisMonth, lastMonth),
            ),
            onMonthChange = { chosenMonths += it },
        )

        // The chip is the only node carrying this month's name until the menu opens, which is why
        // the second month - not this one - is the item that gets clicked.
        composeTestRule.onNodeWithText(DateFormatter.formatMonth(thisMonth)).assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText(DateFormatter.formatMonth(lastMonth)).performClick()

        assertEquals(listOf(lastMonth), chosenMonths)
    }

    @Test
    fun aFilterThatMatchesNothingExplainsItselfAndOffersToClearThem() {
        val clears = AtomicInteger()

        render(
            uiState = content(groups = emptyList(), query = "nothing matches this"),
            onClearFilters = { clears.incrementAndGet() },
        )

        composeTestRule.onNodeWithText(text(R.string.transactions_no_matches_title)).assertIsDisplayed()
        // The ledger is not empty, so this must not claim it is.
        composeTestRule.onNodeWithText(text(R.string.transactions_empty_title)).assertDoesNotExist()

        composeTestRule.onNodeWithText(text(R.string.transactions_clear_filters)).performClick()

        assertEquals(1, clears.get())
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
                    transactionDate = SALARY_DAY,
                )
                transactions.add(
                    type = TransactionType.EXPENSE,
                    amountPaise = 273_405L,
                    categoryId = groceries,
                    transactionDate = GROCERIES_DAY,
                    description = "Weekly shop",
                )
            }

            composeTestRule.setContent {
                HomeFinanceTheme {
                    TransactionsScreen(
                        onAddTransaction = {},
                        onOpenTransaction = {},
                        // The day the screen believes it is is pinned, so this test asserts the list
                        // rather than whichever month the device's clock happens to be in.
                        viewModel = TransactionsViewModel(transactions, today = TODAY),
                    )
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
            composeTestRule.onNodeWithText("\u20B948,500.00").assertIsDisplayed()
            composeTestRule.onNodeWithText("\u20B92,734.05").assertIsDisplayed()
        } finally {
            database.close()
        }
    }

    // -------------------------------------------------------------------- fixtures

    /**
     * Renders the body with every callback inert unless the test under it wants to observe one.
     *
     * The screen takes seven callbacks and a test usually cares about one, so they are defaulted
     * here rather than at the composable: the production call site still has to name all seven,
     * which is what keeps a missing wire-up from compiling.
     */
    private fun render(
        uiState: TransactionsUiState,
        onRetry: () -> Unit = {},
        onQueryChange: (String) -> Unit = {},
        onTypeFilterChange: (TransactionTypeFilter) -> Unit = {},
        onMonthChange: (YearMonth) -> Unit = {},
        onClearFilters: () -> Unit = {},
        onAddTransaction: () -> Unit = {},
        onOpenTransaction: (Long) -> Unit = {},
    ) {
        composeTestRule.setContent {
            HomeFinanceTheme {
                TransactionsContent(
                    uiState = uiState,
                    onRetry = onRetry,
                    onQueryChange = onQueryChange,
                    onTypeFilterChange = onTypeFilterChange,
                    onMonthChange = onMonthChange,
                    onClearFilters = onClearFilters,
                    onAddTransaction = onAddTransaction,
                    onOpenTransaction = onOpenTransaction,
                )
            }
        }
    }

    private fun content(
        groups: List<TransactionDateGroup>,
        query: String = "",
        typeFilter: TransactionTypeFilter = TransactionTypeFilter.ALL,
        month: YearMonth = YearMonth.from(GROCERIES_DAY),
        monthOptions: List<YearMonth> = listOf(month),
        ledgerIsEmpty: Boolean = false,
    ): TransactionsUiState.Content = TransactionsUiState.Content(
        groups = groups,
        filters = TransactionFilters(query = query, type = typeFilter, month = month),
        monthOptions = monthOptions,
        ledgerIsEmpty = ledgerIsEmpty,
    )

    private fun emptyLedger(): TransactionsUiState.Content =
        content(groups = emptyList(), ledgerIsEmpty = true)

    private fun group(
        date: LocalDate,
        relation: DateRelation = DateRelation.EARLIER,
        items: Array<TransactionListItem>,
    ): TransactionDateGroup = TransactionDateGroup(date = date, relation = relation, items = items.toList())

    private fun item(
        id: Long,
        categoryName: String,
        description: String? = null,
        amountPaise: Long = 1_000L,
        type: TransactionType = TransactionType.EXPENSE,
        date: LocalDate = GROCERIES_DAY,
    ): TransactionListItem = TransactionListItem(
        id = id,
        categoryName = categoryName,
        description = description,
        transactionDate = date,
        amountPaise = amountPaise,
        type = type,
    )

    private fun text(id: Int): String = resources.getString(id)

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 10, 17)
        val GROCERIES_DAY: LocalDate = LocalDate.of(2026, 10, 15)
        val SALARY_DAY: LocalDate = LocalDate.of(2026, 10, 1)
    }
}
