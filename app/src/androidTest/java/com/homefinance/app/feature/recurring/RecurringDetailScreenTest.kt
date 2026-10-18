package com.homefinance.app.feature.recurring

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homefinance.app.R
import com.homefinance.app.core.ui.theme.HomeFinanceTheme
import com.homefinance.app.core.util.CurrencyFormatter
import com.homefinance.app.core.util.DateFormatter
import com.homefinance.app.core.util.MoneyParseResult
import com.homefinance.app.data.model.RecurrenceFrequency
import com.homefinance.app.data.model.RecurringScheduleStatus
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One schedule's details screen, rendered on a device.
 *
 * The two things this screen has to get right are easy to get subtly wrong, so they are what is asserted
 * here. First: the *one* occurrence waiting on the household is named with its own date and its own
 * expected amount, so that "Mark as paid" is never a button whose effect has to be guessed at. Second:
 * looking at the screen records nothing - the dialogs exist precisely so that a due date cannot turn into
 * an expense without a confirmation.
 *
 * The screen's data assembly is pinned in `RecurringDetailViewModelTest` on the JVM; what is pinned here
 * is that the assembled facts reach the right controls.
 */
@RunWith(AndroidJUnit4::class)
class RecurringDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private val today = LocalDate.of(2026, 10, 20)

    // -------------------------------------------------------- loading and errors

    @Test
    fun theFirstReadShowsASpinnerAndNoFacts() {
        render(RecurringDetailUiState.Loading)

        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_edit)).assertDoesNotExist()
    }

    @Test
    fun aScheduleThatIsGoneSaysSoAndOffersAWayBack() {
        val backs = AtomicLong()

        render(RecurringDetailUiState.NotFound, onBack = { backs.incrementAndGet() })

        // Kept apart from a failed read: "this schedule is gone" is a fact, and telling the household to
        // retry would be asking them to fix something that is not broken.
        composeTestRule.onNodeWithText(text(R.string.recurring_missing_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_error_retry)).assertDoesNotExist()

        composeTestRule.onNodeWithText(text(R.string.recurring_missing_action)).performClick()

        assertEquals(1L, backs.get())
    }

    @Test
    fun aFailedReadOffersARetry() {
        val retries = AtomicLong()

        render(RecurringDetailUiState.Failed(IOException("no")), onRetry = { retries.incrementAndGet() })

        composeTestRule.onNodeWithText(text(R.string.recurring_error_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_error_retry)).performClick()

        assertEquals(1L, retries.get())
    }

    // ------------------------------------------------------------- the schedule

    @Test
    fun theFactsCardStatesTheRuleAndTheBookkeeping() {
        render(content(schedule(intervalCount = 2, status = RecurringScheduleStatus.PAUSED)))

        // The labels are the facts card's own, so they appear exactly once on this screen.
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_category_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_frequency_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_start_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_end_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_status_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_occurrence_count_label))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_added_label)).assertIsDisplayed()

        // The values that appear twice - once in the summary above, once in the facts - are asserted as
        // exactly two, so a fact silently going missing from either card fails this test.
        composeTestRule.onAllNodesWithText("Rent").assertCountEquals(2)
        composeTestRule.onAllNodesWithText("Every 2 months").assertCountEquals(2)
        composeTestRule.onAllNodesWithText(text(R.string.recurring_status_paused)).assertCountEquals(2)

        // And the facts only the card carries, exactly once each.
        composeTestRule.onNodeWithText(DateFormatter.format(LocalDate.of(2026, 10, 5))).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_end_none)).assertIsDisplayed()
        composeTestRule.onNodeWithText("7").assertIsDisplayed()
        composeTestRule.onNodeWithText(DateFormatter.formatTimestamp(addedAt)).assertIsDisplayed()
    }

    @Test
    fun aLastOccurrenceThatWasSetIsShownAsItsDay() {
        val end = LocalDate.of(2027, 3, 5)
        render(content(schedule(endDate = end)))

        composeTestRule.onNodeWithText(DateFormatter.format(end)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_end_none)).assertDoesNotExist()
    }

    // -------------------------------------------------- the outstanding occurrence

    @Test
    fun theOutstandingOccurrenceNamesItsDateAndItsOwnExpectedAmount() {
        // Deliberately not the schedule's first-occurrence day, so the two dates on screen cannot be
        // confused for one another.
        val due = LocalDate.of(2026, 11, 5)
        render(
            content(
                outstanding = occurrence(id = 9L, dueDate = due, amountPaise = 1_600_000L),
            ),
        )

        composeTestRule.onNodeWithText(DateFormatter.format(due)).assertIsDisplayed()
        // The *occurrence's* figure, which is what was committed to on that date - not whatever the
        // schedule's expected amount has since been changed to.
        composeTestRule
            .onNodeWithText(
                text(
                    R.string.recurring_detail_expected_occurrence,
                    CurrencyFormatter.formatInr(1_600_000L),
                ),
            )
            .assertIsDisplayed()
        // The chip states the urgency, and the word "Overdue" is what carries it.
        composeTestRule.onNodeWithText(text(R.string.recurring_occurrence_overdue)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_pay_action)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_skip_action)).assertIsDisplayed()
    }

    @Test
    fun theTwoActionsOnAnOccurrenceCarryThatOccurrenceBack() {
        val requested = AtomicReference<RecurringOccurrenceItem>()

        render(
            content(outstanding = occurrence(id = 9L)),
            onRequestPay = { requested.set(it) },
        )

        composeTestRule.onNodeWithText(text(R.string.recurring_pay_action)).performClick()

        assertEquals(9L, requested.get()?.id)
    }

    @Test
    fun theSkipActionCarriesTheOccurrenceToo() {
        val requested = AtomicReference<RecurringOccurrenceItem>()

        render(
            content(outstanding = occurrence(id = 11L)),
            onRequestSkip = { requested.set(it) },
        )

        composeTestRule.onNodeWithText(text(R.string.recurring_skip_action)).performClick()

        assertEquals(11L, requested.get()?.id)
    }

    @Test
    fun nothingWaitingIsSaidPlainlyAndOffersNoAction() {
        render(content(outstanding = null))

        composeTestRule.onNodeWithText(text(R.string.recurring_detail_nothing_due_title))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_nothing_due_message))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_pay_action)).assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.recurring_skip_action)).assertDoesNotExist()
    }

    @Test
    fun aStoppedScheduleStillLetsItsLastBillBeSettled() {
        render(
            content(
                card = schedule(status = RecurringScheduleStatus.RETIRED),
                outstanding = occurrence(id = 4L),
            ),
        )

        // A household who decided not to renew a subscription may still owe the final bill. Refusing to
        // let them record it would leave the ledger wrong in order to make a point about the schedule
        // being over.
        composeTestRule.onNodeWithText(text(R.string.recurring_pay_action)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_skip_action)).assertIsDisplayed()
    }

    // ---------------------------------------------------------------- the controls

    @Test
    fun anActiveScheduleOffersPauseAndStop() {
        render(content(schedule(status = RecurringScheduleStatus.ACTIVE)))

        composeTestRule.onNodeWithText(text(R.string.recurring_detail_edit)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_pause)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_resume)).assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_stop)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun pauseActsImmediatelyBecauseItCanBeUndone() {
        val pauses = AtomicLong()

        render(content(), onPause = { pauses.incrementAndGet() })

        composeTestRule.onNodeWithText(text(R.string.recurring_detail_pause)).performScrollTo().performClick()

        assertEquals(1L, pauses.get())
    }

    @Test
    fun resumeActsImmediatelyTooAndReplacesPause() {
        val resumes = AtomicLong()

        render(
            content(schedule(status = RecurringScheduleStatus.PAUSED)),
            onResume = { resumes.incrementAndGet() },
        )

        composeTestRule.onNodeWithText(text(R.string.recurring_detail_pause)).assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_resume)).performScrollTo().performClick()

        assertEquals(1L, resumes.get())
    }

    @Test
    fun stopAsksFirstBecauseItCannotBeUndone() {
        val retirements = AtomicLong()

        render(content(), onRetire = { retirements.incrementAndGet() })

        composeTestRule.onNodeWithText(text(R.string.recurring_detail_stop)).performScrollTo().performClick()

        // Nothing yet: the question is on screen and the answer has not been given.
        assertEquals(0L, retirements.get())
        composeTestRule.onNodeWithText(text(R.string.recurring_stop_confirm_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_stop_confirm_message)).assertIsDisplayed()

        composeTestRule.onNodeWithText(text(R.string.recurring_stop_confirm_action)).performClick()

        assertEquals(1L, retirements.get())
    }

    @Test
    fun stoppingCanBeBackedOutOf() {
        val retirements = AtomicLong()

        render(content(), onRetire = { retirements.incrementAndGet() })

        composeTestRule.onNodeWithText(text(R.string.recurring_detail_stop)).performScrollTo().performClick()
        composeTestRule.onNodeWithText(text(R.string.action_cancel)).performClick()

        assertEquals(0L, retirements.get())
        composeTestRule.onNodeWithText(text(R.string.recurring_stop_confirm_title)).assertDoesNotExist()
    }

    @Test
    fun aStoppedScheduleCanOnlyBeEditedAndSaysWhy() {
        render(content(schedule(status = RecurringScheduleStatus.RETIRED)))

        composeTestRule.onNodeWithText(text(R.string.recurring_detail_edit)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_pause)).assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_stop)).assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_retired_note)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun editingTheScheduleIsReported() {
        val edits = AtomicLong()

        render(content(), onEdit = { edits.incrementAndGet() })

        composeTestRule.onNodeWithText(text(R.string.recurring_detail_edit)).performScrollTo().performClick()

        assertEquals(1L, edits.get())
    }

    @Test
    fun aFailedActionIsSaidBesideAStillValidSchedule() {
        render(content(actionError = ActionError.Failed(IOException("no"))))

        composeTestRule.onNodeWithText(text(R.string.recurring_action_failed)).assertIsDisplayed()
        // Still true, so still on screen. Reporting a failed pause by throwing the schedule away would be
        // the wrong trade.
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_edit)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aFailedGenerationIsReportedInItsOwnWords() {
        render(content(actionError = ActionError.GenerationFailed(IOException("no"))))

        composeTestRule.onNodeWithText(text(R.string.recurring_generation_failed)).assertIsDisplayed()
    }

    // ------------------------------------------------------------- the short history

    @Test
    fun theHistoryIsReadOnlyAndSaysWhereTheRestOfItLives() {
        val opened = AtomicLong()

        render(
            content(
                recentOccurrences = listOf(
                    occurrence(id = 8L, state = OccurrenceDisplayState.PAID),
                    // Deliberately still outstanding: the short history must not offer to settle it, because a
                    // household reading back through October should not be one stray tap from paying a date
                    // they were only looking at.
                    occurrence(id = 7L, state = OccurrenceDisplayState.UPCOMING),
                ),
            ),
            onOpenOccurrences = { opened.incrementAndGet() },
        )

        composeTestRule.onNodeWithText(text(R.string.recurring_detail_history_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_occurrence_paid)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_pay_action)).assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.recurring_skip_action)).assertDoesNotExist()

        // The history sits at the foot of a screen taller than a phone, so the button that leaves for the
        // full list has to be scrolled to before it can be tapped - as it would be by a household.
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_history_open))
            .performScrollTo()
            .performClick()

        assertEquals(1L, opened.get())
    }

    @Test
    fun noHistoryMeansNoHistoryCardAtAll() {
        render(content(recentOccurrences = emptyList()))

        composeTestRule.onNodeWithText(text(R.string.recurring_detail_history_title)).assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_history_open)).assertDoesNotExist()
    }

    @Test
    fun aPaidOccurrenceWhoseExpenseWasDeletedSaysSo() {
        render(
            content(
                recentOccurrences = listOf(
                    occurrence(id = 8L, state = OccurrenceDisplayState.PAID, transactionRemoved = true),
                ),
            ),
        )

        // The chip still says "Paid" - the payment was recorded - and the row adds that the entry is gone,
        // rather than quietly re-labelling the date as unpaid.
        composeTestRule.onNodeWithText(text(R.string.recurring_occurrence_paid)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_occurrence_payment_removed))
            .assertIsDisplayed()
    }

    // ----------------------------------------------------------------- the dialogs

    @Test
    fun thePayDialogStatesWhatIsBeingPaidBeforeAnythingIsConfirmed() {
        val confirmed = AtomicLong()

        render(
            content(outstanding = occurrence(id = 9L)),
            pay = payState(),
            onConfirmPay = { confirmed.incrementAndGet() },
        )

        composeTestRule.onNodeWithText(text(R.string.recurring_pay_title)).assertIsDisplayed()
        // The dialog's own labels, none of which the screen behind it uses.
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_due_date_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_pay_expected_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_pay_hint)).assertIsDisplayed()
        // Expected and actual are two different figures and both are on screen: the schedule's expectation
        // in the facts behind, and the editable amount here.
        composeTestRule.onNodeWithContentDescription(text(R.string.recurring_pay_amount_label))
            .assertIsDisplayed()

        // Merely opening it has recorded nothing.
        assertEquals(0L, confirmed.get())

        composeTestRule.onNodeWithText(text(R.string.recurring_pay_confirm)).performClick()

        assertEquals(1L, confirmed.get())
    }

    @Test
    fun cancellingThePayDialogWritesNothing() {
        val dismissed = AtomicLong()
        val confirmed = AtomicLong()

        render(
            content(outstanding = occurrence(id = 9L)),
            pay = payState(),
            onConfirmPay = { confirmed.incrementAndGet() },
            onDismissPay = { dismissed.incrementAndGet() },
        )

        composeTestRule.onNodeWithText(text(R.string.action_cancel)).performClick()

        assertEquals(1L, dismissed.get())
        assertEquals(0L, confirmed.get())
    }

    @Test
    fun aRefusedAmountIsSaidInsideThePayDialog() {
        render(
            content(outstanding = occurrence(id = 9L)),
            pay = payState(amountError = MoneyParseResult.Problem.NotPositive),
        )

        composeTestRule
            .onNodeWithText(text(R.string.add_transaction_amount_error_not_positive))
            .assertIsDisplayed()
    }

    @Test
    fun anAlreadySettledPaymentSaysSoAndRefusesAnotherTap() {
        render(
            content(outstanding = occurrence(id = 9L)),
            pay = payState(failure = SettleFailure.AlreadySettled),
        )

        composeTestRule.onNodeWithText(text(R.string.recurring_settle_failure_settled)).assertIsDisplayed()
        // Retrying the same tap can only fail again, so the button that would do it is dead.
        composeTestRule.onNodeWithText(text(R.string.recurring_pay_confirm)).assertIsNotEnabled()
        composeTestRule.onNodeWithText(text(R.string.recurring_pay_hint)).assertDoesNotExist()
    }

    @Test
    fun theSkipDialogNamesTheScheduleAndTheDateItIsSkipping() {
        val confirmed = AtomicLong()
        val due = LocalDate.of(2026, 10, 5)

        render(
            // No outstanding block behind it, so the dialog's own "Skip" confirm is the only one on screen
            // and there is no ambiguity about which button the test is pressing.
            content(outstanding = null),
            skip = SkipOccurrenceState(
                occurrenceId = 9L,
                scheduleName = "Shop rent",
                dueDate = due,
            ),
            onConfirmSkip = { confirmed.incrementAndGet() },
        )

        composeTestRule.onNodeWithText(text(R.string.recurring_skip_title)).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                text(R.string.recurring_skip_message, "Shop rent", DateFormatter.format(due)),
            )
            .assertIsDisplayed()

        composeTestRule.onNodeWithText(text(R.string.recurring_skip_confirm)).performClick()

        assertEquals(1L, confirmed.get())
    }

    @Test
    fun neitherDialogIsComposedWhileNothingIsOpen() {
        render(content())

        composeTestRule.onNodeWithText(text(R.string.recurring_pay_title)).assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.recurring_skip_title)).assertDoesNotExist()
    }

    // ------------------------------------------------------------------ fixtures

    private fun text(id: Int): String = resources.getString(id)

    private fun text(id: Int, vararg args: Any): String = resources.getString(id, *args)

    private val addedAt: Instant = Instant.parse("2026-10-05T04:30:00Z")

    private fun schedule(
        status: RecurringScheduleStatus = RecurringScheduleStatus.ACTIVE,
        intervalCount: Int = 1,
        endDate: LocalDate? = null,
    ): RecurringScheduleCard = RecurringScheduleCard(
        id = 1L,
        name = "Shop rent",
        description = "Ground floor, paid by UPI",
        categoryName = "Rent",
        amountPaise = 1_500_000L,
        frequency = RecurrenceFrequency.MONTHLY,
        intervalCount = intervalCount,
        status = status,
        startDate = LocalDate.of(2026, 10, 5),
        endDate = endDate,
        addedAt = addedAt,
        nextDueDate = LocalDate.of(2026, 10, 5),
        nextDueState = OccurrenceDisplayState.OVERDUE,
    )

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

    private fun payState(
        amountError: MoneyParseResult.Problem? = null,
        failure: SettleFailure? = null,
    ): PayOccurrenceState = PayOccurrenceState(
        occurrenceId = 9L,
        scheduleName = "Shop rent",
        categoryName = "Rent",
        dueDate = LocalDate.of(2026, 10, 5),
        expectedAmountPaise = 1_500_000L,
        amountInput = "15000",
        amountError = amountError,
        failure = failure,
    )

    private fun content(
        card: RecurringScheduleCard = schedule(),
        outstanding: RecurringOccurrenceItem? = null,
        recentOccurrences: List<RecurringOccurrenceItem> = emptyList(),
        occurrenceCount: Int = 7,
        actionError: ActionError? = null,
    ): RecurringDetailUiState.Content = RecurringDetailUiState.Content(
        schedule = card,
        outstanding = outstanding,
        recentOccurrences = recentOccurrences,
        occurrenceCount = occurrenceCount,
        actionError = actionError,
    )

    private fun render(
        uiState: RecurringDetailUiState,
        pay: PayOccurrenceState? = null,
        skip: SkipOccurrenceState? = null,
        onBack: () -> Unit = {},
        onRetry: () -> Unit = {},
        onEdit: () -> Unit = {},
        onOpenOccurrences: () -> Unit = {},
        onPause: () -> Unit = {},
        onResume: () -> Unit = {},
        onRetire: () -> Unit = {},
        onRequestPay: (RecurringOccurrenceItem) -> Unit = {},
        onRequestSkip: (RecurringOccurrenceItem) -> Unit = {},
        onPayAmountChange: (String) -> Unit = {},
        onConfirmPay: () -> Unit = {},
        onDismissPay: () -> Unit = {},
        onConfirmSkip: () -> Unit = {},
        onDismissSkip: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            HomeFinanceTheme {
                RecurringDetailContent(
                    uiState = uiState,
                    pay = pay,
                    skip = skip,
                    onBack = onBack,
                    onRetry = onRetry,
                    onEdit = onEdit,
                    onOpenOccurrences = onOpenOccurrences,
                    onPause = onPause,
                    onResume = onResume,
                    onRetire = onRetire,
                    onRequestPay = onRequestPay,
                    onRequestSkip = onRequestSkip,
                    onPayAmountChange = onPayAmountChange,
                    onConfirmPay = onConfirmPay,
                    onDismissPay = onDismissPay,
                    onConfirmSkip = onConfirmSkip,
                    onDismissSkip = onDismissSkip,
                )
            }
        }
    }
}
