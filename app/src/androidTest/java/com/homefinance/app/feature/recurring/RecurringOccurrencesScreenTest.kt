package com.homefinance.app.feature.recurring

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
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
import com.homefinance.app.core.util.CurrencyFormatter
import com.homefinance.app.core.util.DateFormatter
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One schedule's occurrence history, rendered on a device.
 *
 * This is the screen a household catches up on, so what matters is which rows offer to be settled. A row
 * whose date has passed and has not been dealt with must offer both actions; a row that was paid or
 * skipped must offer neither, because there is nothing left to do to it and a stray tap must not be able
 * to re-open a settled date.
 *
 * The view model's own folding is pinned in `RecurringOccurrencesViewModelTest` on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class RecurringOccurrencesScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    // --------------------------------------------------------- loading and errors

    @Test
    fun theFirstReadShowsASpinner() {
        render(RecurringOccurrencesUiState.Loading)

        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertIsDisplayed()
    }

    @Test
    fun aScheduleThatIsGoneSaysSoAndOffersAWayBack() {
        val backs = AtomicLong()

        render(RecurringOccurrencesUiState.NotFound, onBack = { backs.incrementAndGet() })

        composeTestRule.onNodeWithText(text(R.string.recurring_missing_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_missing_action)).performClick()

        assertEquals(1L, backs.get())
    }

    @Test
    fun aFailedReadOffersARetry() {
        val retries = AtomicLong()

        render(RecurringOccurrencesUiState.Failed(IOException("no")), onRetry = { retries.incrementAndGet() })

        composeTestRule.onNodeWithText(text(R.string.recurring_error_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_error_retry)).performClick()

        assertEquals(1L, retries.get())
    }

    @Test
    fun aScheduleWithNothingGeneratedYetIsAnEmptyListAndNotAFailure() {
        render(RecurringOccurrencesUiState.Content(scheduleName = "Shop rent", occurrences = emptyList()))

        // A schedule whose first date is beyond the generation horizon genuinely has no occurrences, and
        // that is a fact about the schedule rather than something to retry.
        composeTestRule.onNodeWithText(text(R.string.recurring_occurrences_empty_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_occurrences_empty_message)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_error_retry)).assertDoesNotExist()
    }

    @Test
    fun theScreenIsTitledOccurrencesAndCarriesTheSchedulesNameInItsRows() {
        render(rows(occurrence(id = 1L, dueDate = LocalDate.of(2026, 10, 5))))

        composeTestRule.onNodeWithText(text(R.string.recurring_occurrences_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(DateFormatter.format(LocalDate.of(2026, 10, 5))).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(CurrencyFormatter.formatInr(1_500_000L))
            .assertIsDisplayed()
    }

    // ------------------------------------------------------------- which rows act

    @Test
    fun anOverdueRowOffersBothActions() {
        val paid = AtomicReference<RecurringOccurrenceItem>()

        render(
            rows(occurrence(id = 5L, state = OccurrenceDisplayState.OVERDUE)),
            onRequestPay = { paid.set(it) },
        )

        composeTestRule.onNodeWithText(text(R.string.recurring_occurrence_overdue)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_pay_action)).performClick()

        assertEquals(5L, paid.get()?.id)
    }

    @Test
    fun aDueTodayRowOffersBothActionsToo() {
        val skipped = AtomicReference<RecurringOccurrenceItem>()

        render(
            rows(occurrence(id = 6L, state = OccurrenceDisplayState.DUE)),
            onRequestSkip = { skipped.set(it) },
        )

        // Being due is not being late, but it is still the household's to settle.
        composeTestRule.onNodeWithText(text(R.string.recurring_occurrence_due)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_skip_action)).performClick()

        assertEquals(6L, skipped.get()?.id)
    }

    @Test
    fun anUpcomingRowOffersBothActionsAsWell() {
        render(rows(occurrence(id = 7L, state = OccurrenceDisplayState.UPCOMING)))

        // Paying a bill early is normal - the milk is delivered and paid for on the same morning the app
        // expects it the next day - so an upcoming date is not locked out.
        composeTestRule.onNodeWithText(text(R.string.recurring_occurrence_upcoming)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_pay_action)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_skip_action)).assertIsDisplayed()
    }

    @Test
    fun aPaidRowOffersNothing() {
        render(rows(occurrence(id = 8L, state = OccurrenceDisplayState.PAID)))

        composeTestRule.onNodeWithText(text(R.string.recurring_occurrence_paid)).assertIsDisplayed()
        // Settled means settled: the payment is a fact that happened, and the row is for reading back.
        composeTestRule.onNodeWithText(text(R.string.recurring_pay_action)).assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.recurring_skip_action)).assertDoesNotExist()
    }

    @Test
    fun aSkippedRowOffersNothingEither() {
        render(rows(occurrence(id = 9L, state = OccurrenceDisplayState.SKIPPED)))

        composeTestRule.onNodeWithText(text(R.string.recurring_occurrence_skipped)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_pay_action)).assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.recurring_skip_action)).assertDoesNotExist()
    }

    @Test
    fun onlyTheSettledRowsAreMissingTheirActionsInAMixedList() {
        render(
            rows(
                occurrence(id = 3L, state = OccurrenceDisplayState.OVERDUE),
                occurrence(id = 2L, state = OccurrenceDisplayState.PAID),
                occurrence(id = 1L, state = OccurrenceDisplayState.SKIPPED),
            ),
        )

        // One actionable row out of three, and the two chips that say why the other two are not.
        composeTestRule.onAllNodesWithText(text(R.string.recurring_pay_action)).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(text(R.string.recurring_skip_action)).assertCountEquals(1)
        composeTestRule.onNodeWithText(text(R.string.recurring_occurrence_paid)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_occurrence_skipped)).assertIsDisplayed()
    }

    @Test
    fun aPaidRowWhoseExpenseWasDeletedSaysSo() {
        render(
            rows(occurrence(id = 8L, state = OccurrenceDisplayState.PAID, transactionRemoved = true)),
        )

        // The chip keeps saying "Paid" - the payment was recorded - and the row adds that the entry is gone,
        // rather than pretending the date was never settled.
        composeTestRule.onNodeWithText(text(R.string.recurring_occurrence_paid)).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(text(R.string.recurring_occurrence_payment_removed))
            .assertIsDisplayed()
    }

    // -------------------------------------------------------------------- dialogs
    //
    // The dialogs themselves are asserted on the details screen, which composes the same two. What is
    // checked here is that this screen opens them at all: the history is the place a household catches up,
    // so a row whose button did nothing would be the worst failure this screen could have.

    @Test
    fun tappingPayOnARowOpensTheDialogForThatOccurrence() {
        render(
            rows(occurrence(id = 5L, state = OccurrenceDisplayState.OVERDUE)),
            pay = PayOccurrenceState(
                occurrenceId = 5L,
                scheduleName = "Shop rent",
                categoryName = "Rent",
                dueDate = LocalDate.of(2026, 10, 5),
                expectedAmountPaise = 1_500_000L,
                amountInput = "15000",
            ),
        )

        composeTestRule.onNodeWithText(text(R.string.recurring_pay_title)).assertIsDisplayed()
    }

    // ------------------------------------------------------------------ fixtures

    private fun text(id: Int): String = resources.getString(id)

    private fun occurrence(
        id: Long,
        dueDate: LocalDate = LocalDate.of(2026, 10, 5),
        amountPaise: Long = 1_500_000L,
        state: OccurrenceDisplayState = OccurrenceDisplayState.OVERDUE,
        transactionRemoved: Boolean = false,
    ): RecurringOccurrenceItem = RecurringOccurrenceItem(
        id = id,
        scheduleName = "Shop rent",
        categoryName = "Rent",
        dueDate = dueDate,
        amountPaise = amountPaise,
        state = state,
        transactionRemoved = transactionRemoved,
    )

    private fun rows(vararg occurrences: RecurringOccurrenceItem): RecurringOccurrencesUiState.Content =
        RecurringOccurrencesUiState.Content(
            scheduleName = "Shop rent",
            occurrences = occurrences.toList(),
        )

    private fun render(
        uiState: RecurringOccurrencesUiState,
        pay: PayOccurrenceState? = null,
        skip: SkipOccurrenceState? = null,
        onBack: () -> Unit = {},
        onRetry: () -> Unit = {},
        onRequestPay: (RecurringOccurrenceItem) -> Unit = {},
        onRequestSkip: (RecurringOccurrenceItem) -> Unit = {},
    ) {
        composeTestRule.setContent {
            HomeFinanceTheme {
                RecurringOccurrencesContent(
                    uiState = uiState,
                    pay = pay,
                    skip = skip,
                    onBack = onBack,
                    onRetry = onRetry,
                    onRequestPay = onRequestPay,
                    onRequestSkip = onRequestSkip,
                    onPayAmountChange = {},
                    onConfirmPay = {},
                    onDismissPay = {},
                    onConfirmSkip = {},
                    onDismissSkip = {},
                )
            }
        }
    }
}
