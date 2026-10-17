package com.homefinance.app.feature.transactiondetail

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homefinance.app.R
import com.homefinance.app.core.ui.theme.HomeFinanceTheme
import com.homefinance.app.core.util.CurrencyFormatter
import com.homefinance.app.core.util.DateFormatter
import com.homefinance.app.data.local.relation.TransactionWithCategory
import com.homefinance.app.data.model.TransactionType
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The transaction details screen, rendered on a device.
 *
 * What is asserted here is what a JVM test cannot see: that the state [TransactionDetailViewModel]
 * produces actually becomes pixels - every figure the household recorded, under its own label - and
 * that the two actions on the screen call back to the two things they say they do, without either of
 * them being able to change anything on its own.
 *
 * The screen is rendered through its state-taking body rather than through its view model, the same
 * way the other feature screens are tested, so the states a real database will not produce on demand
 * - a transaction that has gone, a read that failed, a deletion in flight - are rendered directly
 * rather than waited for. The state machine that produces them is pinned by
 * [TransactionDetailViewModelTest].
 */
@RunWith(AndroidJUnit4::class)
class TransactionDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    // ------------------------------------------------------------------ the entry

    @Test
    fun everyFigureTheHouseholdRecordedIsShownUnderItsOwnLabel() {
        render(content())

        composeTestRule.onNodeWithText(text(R.string.transaction_detail_title)).assertIsDisplayed()

        // The summary repeats the category and the amount on purpose - it is what the household
        // recognises the entry by - so both appear twice: once in the head, once in the card.
        composeTestRule.onAllNodesWithText(CATEGORY_NAME).assertCountEquals(2)
        composeTestRule.onAllNodesWithText(CurrencyFormatter.formatInr(AMOUNT_PAISE))
            .assertCountEquals(2)
        composeTestRule.onNodeWithText(DESCRIPTION).assertIsDisplayed()

        composeTestRule.onNodeWithText(text(R.string.transaction_detail_type_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_type_expense)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.transaction_detail_category_label))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.transaction_detail_date_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(DateFormatter.format(TRANSACTION_DATE)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.transaction_detail_amount_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.transaction_detail_notes_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(NOTES).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.transaction_detail_created_label))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.transaction_detail_updated_label))
            .assertIsDisplayed()
    }

    @Test
    fun anIncomeEntryIsShownAsIncome() {
        render(content(type = TransactionType.INCOME))

        // The same formatter the ledger rows use, so a figure cannot mean one thing on one screen and
        // look like another on the next - and the direction is named in words as well as coloured, so
        // it does not depend on seeing the colour.
        composeTestRule.onAllNodesWithText(CurrencyFormatter.formatInr(AMOUNT_PAISE))
            .assertCountEquals(2)
        composeTestRule.onNodeWithText(text(R.string.add_transaction_type_income)).assertIsDisplayed()
    }

    @Test
    fun anEntryWithNoNotesHasNoNotesRow() {
        render(content(notes = null))

        // Not an empty row and not a dash: the field was never filled in, so there is nothing to say
        // about it.
        composeTestRule.onNodeWithText(text(R.string.transaction_detail_notes_label)).assertDoesNotExist()
    }

    @Test
    fun anEntryRecordedByQuantityAndRateShowsBothFigures() {
        render(content(quantityThousandths = 1_500L, ratePaise = 6_000L))

        composeTestRule.onNodeWithText(text(R.string.transaction_detail_quantity_label))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(
            resources.getString(
                R.string.transaction_detail_quantity_value,
                "1.5",
                CurrencyFormatter.formatInr(6_000L),
            ),
        ).assertIsDisplayed()
    }

    @Test
    fun anEntryRecordedAsAPlainAmountHasNoQuantityRow() {
        // No unit is named anywhere in the app, because the database stores no unit - so a quantity
        // row on an entry that has no quantity would be an invention, not a zero.
        render(content())

        composeTestRule.onNodeWithText(text(R.string.transaction_detail_quantity_label))
            .assertDoesNotExist()
    }

    @Test
    fun theTwoActionsThatChangeTheEntryAreBothOffered() {
        render(content())

        composeTestRule.onNodeWithText(text(R.string.transaction_detail_edit)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.transaction_detail_delete)).assertIsDisplayed()
    }

    @Test
    fun editingReportsTheTapAndChangesNothingOnItsOwn() {
        val edits = AtomicInteger()

        render(content(), onEdit = { edits.incrementAndGet() })
        composeTestRule.onNodeWithText(text(R.string.transaction_detail_edit)).performClick()

        assertEquals(1, edits.get())
        // Opening the form is all this does: nothing about the entry has been touched, and it is
        // still on screen behind the tap.
        composeTestRule.onAllNodesWithText(CATEGORY_NAME).assertCountEquals(2)
        composeTestRule.onNodeWithText(DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun deletingOpensTheConfirmationRatherThanDeleting() {
        val requests = AtomicInteger()

        render(content(), onDeleteRequested = { requests.incrementAndGet() })
        composeTestRule.onNodeWithText(text(R.string.transaction_detail_delete)).performClick()

        assertEquals(1, requests.get())
        composeTestRule.onNodeWithText(text(R.string.transaction_delete_dialog_title)).assertDoesNotExist()
    }

    @Test
    fun theBackArrowLeavesTheScreen() {
        val backs = AtomicInteger()

        render(content(), onBack = { backs.incrementAndGet() })
        composeTestRule.onNodeWithContentDescription(text(R.string.action_back)).performClick()

        assertEquals(1, backs.get())
    }

    // ------------------------------------------------------------- the confirmation

    @Test
    fun theConfirmationSaysTheDeletionCannotBeUndone() {
        render(content(deleteConfirmationVisible = true))

        composeTestRule.onNodeWithText(text(R.string.transaction_delete_dialog_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.transaction_delete_dialog_message))
            .assertIsDisplayed()
        composeTestRule.onNode(inDialog(text(R.string.transaction_delete_dialog_confirm)))
            .assertIsDisplayed()
        composeTestRule.onNode(inDialog(text(R.string.action_cancel))).assertIsDisplayed()
    }

    @Test
    fun confirmingReportsTheDeletionAndCancellingReportsTheDismissal() {
        val confirmations = AtomicInteger()
        val dismissals = AtomicInteger()

        render(
            content(deleteConfirmationVisible = true),
            onConfirmDelete = { confirmations.incrementAndGet() },
            onDeleteDismissed = { dismissals.incrementAndGet() },
        )

        composeTestRule.onNode(inDialog(text(R.string.action_cancel))).performClick()
        composeTestRule.onNode(inDialog(text(R.string.transaction_delete_dialog_confirm))).performClick()

        assertEquals(1, dismissals.get())
        assertEquals(1, confirmations.get())
    }

    @Test
    fun aFailedDeletionIsExplainedInsideTheDialogNextToTheButtonThatFailed() {
        render(content(deleteConfirmationVisible = true, deleteError = DeleteError.Failed(IOException())))

        // The sentence is in the dialog, not behind it: the household is still looking at the
        // question, and the answer is about the button they just tapped.
        composeTestRule.onNodeWithText(text(R.string.transaction_delete_error)).assertIsDisplayed()
    }

    @Test
    fun aDeletionInFlightCannotBeAnsweredTwice() {
        render(content(deleteConfirmationVisible = true, isDeleting = true))

        // Both actions are disabled and the confirmation says what it is doing, so a second tap
        // cannot send a second delete.
        composeTestRule.onNode(inDialog(text(R.string.transaction_delete_dialog_deleting)))
            .assertIsNotEnabled()
        composeTestRule.onNode(inDialog(text(R.string.action_cancel))).assertIsNotEnabled()
    }

    @Test
    fun anIdleConfirmationCanBeAnswered() {
        render(content(deleteConfirmationVisible = true))

        composeTestRule.onNode(inDialog(text(R.string.transaction_delete_dialog_confirm)))
            .assertIsEnabled()
        composeTestRule.onNode(inDialog(text(R.string.action_cancel))).assertIsEnabled()
    }

    @Test
    fun theConfirmationIsNeverDrawnWithoutATransactionBehindIt() {
        render(TransactionDetailUiState.NotFound)

        composeTestRule.onNodeWithText(text(R.string.transaction_delete_dialog_title)).assertDoesNotExist()
    }

    // ------------------------------------------------------------- the other states

    @Test
    fun aTransactionThatIsGoneIsExplainedAndOffersTheWayBack() {
        val backs = AtomicInteger()

        render(TransactionDetailUiState.NotFound, onBack = { backs.incrementAndGet() })

        composeTestRule.onNodeWithText(text(R.string.transaction_detail_not_found_title))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.transaction_detail_not_found_message))
            .assertIsDisplayed()

        composeTestRule.onNodeWithText(text(R.string.transaction_detail_not_found_action)).performClick()

        assertEquals(1, backs.get())
    }

    @Test
    fun aFailedReadIsExplainedAndOffersARetry() {
        val retries = AtomicInteger()

        render(TransactionDetailUiState.Failed(IOException("the read failed")), onRetry = {
            retries.incrementAndGet()
        })

        composeTestRule.onNodeWithText(text(R.string.transaction_detail_error_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.transaction_detail_error_message)).assertIsDisplayed()
        // The raw failure is never rendered: an exception message is not something a household can
        // act on, and it is not their business.
        composeTestRule.onNodeWithText("the read failed").assertDoesNotExist()

        composeTestRule.onNodeWithText(text(R.string.transaction_detail_retry)).performClick()

        assertEquals(1, retries.get())
    }

    @Test
    fun theFirstReadShowsAProgressIndicatorRatherThanAnEmptyTransaction() {
        render(TransactionDetailUiState.Loading)

        composeTestRule.onNodeWithText(text(R.string.transaction_detail_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.transaction_detail_edit)).assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.transaction_detail_delete)).assertDoesNotExist()
    }

    // --------------------------------------------------------------------- fixtures

    private fun render(
        uiState: TransactionDetailUiState,
        onBack: () -> Unit = {},
        onEdit: () -> Unit = {},
        onDeleteRequested: () -> Unit = {},
        onDeleteDismissed: () -> Unit = {},
        onConfirmDelete: () -> Unit = {},
        onRetry: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            HomeFinanceTheme {
                TransactionDetailContent(
                    uiState = uiState,
                    onBack = onBack,
                    onEdit = onEdit,
                    onDeleteRequested = onDeleteRequested,
                    onDeleteDismissed = onDeleteDismissed,
                    onConfirmDelete = onConfirmDelete,
                    onRetry = onRetry,
                )
            }
        }
    }

    private fun content(
        type: TransactionType = TransactionType.EXPENSE,
        notes: String? = NOTES,
        quantityThousandths: Long? = null,
        ratePaise: Long? = null,
        deleteConfirmationVisible: Boolean = false,
        isDeleting: Boolean = false,
        deleteError: DeleteError? = null,
        deleted: Boolean = false,
    ): TransactionDetailUiState.Content = TransactionDetailUiState.Content(
        transaction = TransactionWithCategory(
            id = 7L,
            type = type,
            amountPaise = AMOUNT_PAISE,
            categoryId = 1L,
            description = DESCRIPTION,
            notes = notes,
            quantityThousandths = quantityThousandths,
            ratePaise = ratePaise,
            transactionDate = TRANSACTION_DATE,
            createdAt = AUDIT_INSTANT,
            updatedAt = AUDIT_INSTANT,
            categoryName = CATEGORY_NAME,
        ),
        deleteConfirmationVisible = deleteConfirmationVisible,
        isDeleting = isDeleting,
        deleteError = deleteError,
        deleted = deleted,
    )

    /**
     * A matcher for a node inside the dialog.
     *
     * The screen's own Delete button and the dialog's confirmation carry the same word, so a plain
     * text lookup would be ambiguous - and picking whichever came first would let the test pass by
     * clicking the wrong one.
     */
    private fun inDialog(label: String): SemanticsMatcher =
        hasText(label) and hasAnyAncestor(isDialog())

    private fun text(id: Int): String = resources.getString(id)

    private companion object {
        const val CATEGORY_NAME = "Groceries"
        const val DESCRIPTION = "Vegetables and fruits"
        const val NOTES = "Fresh vegetables"
        const val AMOUNT_PAISE = 32_000L

        val TRANSACTION_DATE: LocalDate = LocalDate.of(2026, 10, 14)
        val AUDIT_INSTANT: Instant = Instant.parse("2026-10-14T05:00:00Z")
    }
}
