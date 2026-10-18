package com.homefinance.app.feature.addtransaction

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homefinance.app.R
import com.homefinance.app.core.ui.theme.HomeFinanceTheme
import com.homefinance.app.data.model.TransactionType
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The category picker's four states, rendered on a device.
 *
 * This screen never touches a database itself - it renders one immutable state - so the states that
 * matter here are the ones a real database will not produce on demand: an empty list, a read that
 * failed, and a read that has not answered yet. Rendering them directly is the only way to pin what
 * each one says, and pinning them matters because "no categories yet" and "could not read your
 * categories" are very different sentences to a household.
 */
@RunWith(AndroidJUnit4::class)
class CategoryPickerScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    // ------------------------------------------------------------- rendering

    @Test
    fun everyCategoryOfTheTypeIsListedWithItsName() {
        render(
            state(
                CategoriesUiState.Ready(
                    listOf(
                        CategoryOption(id = 1L, name = "Electricity"),
                        CategoryOption(id = 2L, name = "Groceries"),
                        CategoryOption(id = 3L, name = "Milk"),
                    ),
                ),
            ),
        )

        composeTestRule.onNodeWithText(text(R.string.category_picker_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Electricity").assertIsDisplayed()
        composeTestRule.onNodeWithText("Groceries").assertIsDisplayed()
        composeTestRule.onNodeWithText("Milk").assertIsDisplayed()
    }

    @Test
    fun theChosenCategoryIsMarkedAsSelectedAndTheOthersAreNot() {
        render(
            state(
                CategoriesUiState.Ready(
                    listOf(
                        CategoryOption(id = 1L, name = "Groceries"),
                        CategoryOption(id = 2L, name = "Milk"),
                    ),
                ),
                category = CategoryOption(id = 2L, name = "Milk"),
            ),
        )

        composeTestRule.onNodeWithText("Milk").assertIsSelected()
        composeTestRule.onNodeWithText("Groceries").assertIsNotSelected()
    }

    @Test
    fun choosingACategoryReportsItsId() {
        val chosen = AtomicLong(-1L)
        render(
            state(
                CategoriesUiState.Ready(
                    listOf(
                        CategoryOption(id = 1L, name = "Groceries"),
                        CategoryOption(id = 7L, name = "Milk"),
                    ),
                ),
            ),
            onCategorySelected = { chosen.set(it) },
        )

        composeTestRule.onNodeWithText("Milk").performClick()

        assertEquals(7L, chosen.get())
    }

    @Test
    fun theTopBarOffersAddCategory() {
        val opened = AtomicLong(0L)
        render(
            state(CategoriesUiState.Ready(listOf(CategoryOption(id = 1L, name = "Groceries")))),
            onAddCategory = { opened.incrementAndGet() },
        )

        composeTestRule.onNodeWithContentDescription(text(R.string.add_category_action))
            .performClick()

        assertEquals(1L, opened.get())
    }

    // ---------------------------------------------------------------- states

    @Test
    fun anEmptyListExplainsHowToAddTheFirstCategory() {
        render(state(CategoriesUiState.Ready(emptyList())))

        composeTestRule.onNodeWithText(text(R.string.category_picker_empty)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.category_picker_empty_action)).assertIsDisplayed()
    }

    @Test
    fun theEmptyStateCanAddTheFirstCategory() {
        val opened = AtomicLong(0L)
        render(
            state(CategoriesUiState.Ready(emptyList())),
            onAddCategory = { opened.incrementAndGet() },
        )

        composeTestRule.onNodeWithText(text(R.string.category_picker_empty_action)).performClick()

        assertEquals(1L, opened.get())
    }

    @Test
    fun aFailedReadSaysSoAndOffersARetry() {
        val retried = AtomicLong(0L)
        render(
            state(CategoriesUiState.Failed(IOException("the database could not be read"))),
            onRetry = { retried.incrementAndGet() },
        )

        composeTestRule.onNodeWithText(text(R.string.category_picker_failed)).assertIsDisplayed()
        // A failed read must never be dressed up as "you have no categories", which would invite the
        // household to recreate the ones they already have.
        composeTestRule.onNodeWithText(text(R.string.category_picker_empty)).assertDoesNotExist()

        composeTestRule.onNodeWithText(text(R.string.category_picker_retry)).performClick()

        assertEquals(1L, retried.get())
    }

    @Test
    fun aReadThatHasNotAnsweredYetSaysItIsLoadingRatherThanShowingNothing() {
        render(state(CategoriesUiState.Loading))

        composeTestRule.onNodeWithText(text(R.string.category_picker_loading)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.category_picker_empty)).assertDoesNotExist()
    }

    // ----------------------------------------------------------------- dialog

    @Test
    fun theNewCategoryDialogIsRenderedWhenItIsOpen() {
        render(
            state(
                CategoriesUiState.Ready(emptyList()),
                categoryDialog = CategoryDialogState(nameInput = "Milk"),
            ),
        )

        composeTestRule.onNodeWithText(text(R.string.add_category_dialog_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Milk").assertIsDisplayed()
    }

    @Test
    fun aDuplicateNameIsExplainedInsideTheDialog() {
        render(
            state(
                CategoriesUiState.Ready(emptyList()),
                categoryDialog = CategoryDialogState(
                    nameInput = "Milk",
                    error = CategoryDialogError.Duplicate,
                ),
            ),
        )

        composeTestRule.onNodeWithText(text(R.string.add_category_error_duplicate)).assertIsDisplayed()
    }

    // --------------------------------------------------------------- fixtures

    private fun state(
        categories: CategoriesUiState,
        category: CategoryOption? = null,
        categoryDialog: CategoryDialogState? = null,
    ): AddTransactionUiState = AddTransactionUiState(
        type = TransactionType.EXPENSE,
        date = LocalDate.of(2026, 10, 15),
        category = category,
        categories = categories,
        categoryDialog = categoryDialog,
    )

    /**
     * Renders the shared picker body.
     *
     * The picker takes the four facts it draws - the categories, which one is chosen, the entry's type and
     * the "new category" dialog if one is open - rather than a whole transaction-form state. It is rendered
     * by two features now, and reading a transaction form's state here would have made the picker's own
     * tests depend on a screen it has nothing to do with.
     */
    private fun render(
        uiState: AddTransactionUiState,
        onCategorySelected: (Long) -> Unit = {},
        onAddCategory: () -> Unit = {},
        onRetry: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            HomeFinanceTheme {
                CategoryPickerContent(
                    categories = uiState.categories,
                    selectedId = uiState.category?.id,
                    type = uiState.type,
                    dialog = uiState.categoryDialog,
                    onCategorySelected = onCategorySelected,
                    onAddCategory = onAddCategory,
                    onCategoryNameChange = {},
                    onCreateCategory = {},
                    onDismissDialog = {},
                    onRetry = onRetry,
                    onBack = {},
                )
            }
        }
    }

    private fun text(id: Int): String = resources.getString(id)
}
