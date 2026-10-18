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
import com.homefinance.app.data.model.TransactionType
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
 * figure, so that "Mark as received" is never a button whose effect has to be guessed at. Second:
 * looking at the screen records nothing - the sheet exists precisely so that a due date cannot turn into
 * a ledger entry without a confirmation.
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

    private val scheduleAmount = 1_500_000L

    // -------------------------------------------------------- loading and errors

    @Test
    fun theFirstReadShowsASpinnerAndNoFacts() {
        render(RecurringDetailUiState.Loading)

        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_edit_short)).assertDoesNotExist()
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
    fun theHeadingPairsTheRuleWithTheDirectionOfTheMoney() {
        render(content(card = schedule(categoryType = TransactionType.INCOME)))

        // "Monthly Income": how often, and which way - the two things that place the figure below it.
        composeTestRule.onNodeWithText(
            text(
                R.string.recurring_detail_direction,
                text(R.string.recurring_frequency_monthly),
                text(R.string.add_transaction_type_income),
            ),
        ).assertIsDisplayed()

        // The figure itself, in the heading, exactly once.
        composeTestRule.onNodeWithText(CurrencyFormatter.formatInr(scheduleAmount)).assertIsDisplayed()
    }

    @Test
    fun theScheduleInformationCardStatesTheRuleAndTheHouseholdsOwnNotes() {
        render(content(schedule(intervalCount = 2, status = RecurringScheduleStatus.PAUSED)))

        composeTestRule.onNodeWithText(text(R.string.recurring_detail_schedule_information))
            .assertIsDisplayed()

        // The labels are the information card's own, so they appear exactly once on this screen.
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_category_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_frequency_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_start_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_end_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_description_label))
            .assertIsDisplayed()

        // The category's name lives here now, not in the heading, so exactly one row carries it...
        composeTestRule.onAllNodesWithText("Rent").assertCountEquals(1)
        // ...and the state is the heading's chip rather than a row.
        composeTestRule.onAllNodesWithText(text(R.string.recurring_status_paused)).assertCountEquals(1)
        composeTestRule.onNodeWithText("Every 2 months").assertIsDisplayed()

        composeTestRule.onNodeWithText(DateFormatter.format(LocalDate.of(2026, 10, 5))).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_end_none)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Ground floor, paid by UPI").assertIsDisplayed()
    }

    @Test
    fun aLastOccurrenceThatWasSetIsShownAsItsDay() {
        val end = LocalDate.of(2027, 3, 5)
        render(content(schedule(endDate = end)))

        composeTestRule.onNodeWithText(DateFormatter.format(end)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_end_none)).assertDoesNotExist()
    }

    @Test
    fun theSummaryCountsPaidUpcomingAndOverdue() {
        render(content(paidCount = 12, upcomingCount = 3, overdueCount = 2))

        composeTestRule.onNodeWithText(text(R.string.recurring_detail_payment_summary))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("12").assertIsDisplayed()
        composeTestRule.onNodeWithText("3").assertIsDisplayed()
        composeTestRule.onNodeWithText("2").assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_stat_paid)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_stat_upcoming))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_stat_overdue))
            .assertIsDisplayed()
    }

    // -------------------------------------------------- the outstanding occurrence

    @Test
    fun theOutstandingOccurrenceNamesItsDateAndItsOwnFigure() {
        // Ahead of today by five days, so the chip has to count rather than just say "overdue".
        val due = today.plusDays(5)
        render(
            content(
                outstanding = occurrence(
                    id = 9L,
                    dueDate = due,
                    amountPaise = 1_600_000L,
                    state = OccurrenceDisplayState.UPCOMING,
                ),
            ),
        )

        composeTestRule.onNodeWithText(text(R.string.recurring_detail_next_payment))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(DateFormatter.format(due)).assertIsDisplayed()
        composeTestRule.onNodeWithText(DateFormatter.formatWeekday(due)).assertIsDisplayed()
        // The *occurrence's* figure, which is what was committed to on that date - not whatever the
        // schedule's expected amount has since been changed to.
        composeTestRule.onNodeWithText(CurrencyFormatter.formatInr(1_600_000L)).assertIsDisplayed()
        composeTestRule.onNodeWithText(quantity(R.plurals.recurring_due_in_days, 5, 5))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_skip_action)).assertIsDisplayed()
    }

    @Test
    fun anExpenseScheduleOffersToMarkThePaymentAsPaid() {
        render(content(outstanding = occurrence(id = 9L, dueDate = today)))

        composeTestRule.onNodeWithText(
            text(R.string.recurring_detail_mark_paid, CurrencyFormatter.formatInr(scheduleAmount)),
        ).assertIsDisplayed()
    }

    @Test
    fun anIncomeScheduleOffersToMarkThePaymentAsReceived() {
        render(
            content(
                card = schedule(categoryType = TransactionType.INCOME),
                outstanding = occurrence(id = 9L, dueDate = today),
            ),
        )

        composeTestRule.onNodeWithText(
            text(R.string.recurring_detail_mark_received, CurrencyFormatter.formatInr(scheduleAmount)),
        ).assertIsDisplayed()
    }

    @Test
    fun anOverdueOccurrenceSaysHowLateItIs() {
        render(
            content(
                outstanding = occurrence(
                    id = 9L,
                    dueDate = today.minusDays(6),
                    state = OccurrenceDisplayState.OVERDUE,
                ),
            ),
        )

        composeTestRule.onNodeWithText(quantity(R.plurals.recurring_overdue_by_days, 6, 6))
            .assertIsDisplayed()
    }

    @Test
    fun theTwoActionsOnAnOccurrenceCarryThatOccurrenceBack() {
        val requested = AtomicReference<RecurringOccurrenceItem>()

        render(
            content(outstanding = occurrence(id = 9L, dueDate = today)),
            onRequestPay = { requested.set(it) },
        )

        composeTestRule.onNodeWithText(
            text(R.string.recurring_detail_mark_paid, CurrencyFormatter.formatInr(scheduleAmount)),
        ).performClick()

        assertEquals(9L, requested.get()?.id)
    }

    @Test
    fun theSkipActionCarriesTheOccurrenceToo() {
        val requested = AtomicReference<RecurringOccurrenceItem>()

        render(
            content(outstanding = occurrence(id = 11L, dueDate = today)),
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
        composeTestRule.onNodeWithText(text(R.string.recurring_skip_action)).assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_next_payment))
            .assertDoesNotExist()
    }

    @Test
    fun aStoppedScheduleStillLetsItsLastBillBeSettled() {
        render(
            content(
                card = schedule(status = RecurringScheduleStatus.RETIRED),
                outstanding = occurrence(id = 4L, dueDate = today),
            ),
        )

        // A household who decided not to renew a subscription may still owe the final bill. Refusing to
        // let them record it would leave the ledger wrong in order to make a point about the schedule
        // being over.
        composeTestRule.onNodeWithText(
            text(R.string.recurring_detail_mark_paid, CurrencyFormatter.formatInr(scheduleAmount)),
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_skip_action)).assertIsDisplayed()
    }

    // ---------------------------------------------------------------- the controls

    @Test
    fun anActiveScheduleOffersPauseAndStopBehindTheOverflowMenu() {
        render(content(schedule(status = RecurringScheduleStatus.ACTIVE)))

        openActionsMenu()

        composeTestRule.onNodeWithText(text(R.string.recurring_detail_pause)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_resume)).assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_stop)).assertIsDisplayed()
    }

    @Test
    fun pauseActsImmediatelyBecauseItCanBeUndone() {
        val pauses = AtomicLong()

        render(content(), onPause = { pauses.incrementAndGet() })

        openActionsMenu()
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_pause)).performClick()

        assertEquals(1L, pauses.get())
    }

    @Test
    fun resumeActsImmediatelyTooAndReplacesPause() {
        val resumes = AtomicLong()

        render(
            content(schedule(status = RecurringScheduleStatus.PAUSED)),
            onResume = { resumes.incrementAndGet() },
        )

        openActionsMenu()
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_pause)).assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_resume)).performClick()

        assertEquals(1L, resumes.get())
    }

    @Test
    fun stopAsksFirstBecauseItCannotBeUndone() {
        val retirements = AtomicLong()

        render(content(), onRetire = { retirements.incrementAndGet() })

        openActionsMenu()
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_stop)).performClick()

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

        openActionsMenu()
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_stop)).performClick()
        composeTestRule.onNodeWithText(text(R.string.action_cancel)).performClick()

        assertEquals(0L, retirements.get())
        composeTestRule.onNodeWithText(text(R.string.recurring_stop_confirm_title)).assertDoesNotExist()
    }

    @Test
    fun aStoppedScheduleCanOnlyBeEditedAndSaysWhy() {
        render(content(schedule(status = RecurringScheduleStatus.RETIRED)))

        composeTestRule.onNodeWithText(text(R.string.recurring_detail_edit_short)).assertIsDisplayed()
        // Nothing left to do to it, so no menu that would open onto nothing.
        composeTestRule.onNodeWithContentDescription(text(R.string.recurring_detail_more_actions))
            .assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_retired_note))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun editingTheScheduleIsReported() {
        val edits = AtomicLong()

        render(content(), onEdit = { edits.incrementAndGet() })

        composeTestRule.onNodeWithText(text(R.string.recurring_detail_edit_short)).performClick()

        assertEquals(1L, edits.get())
    }

    @Test
    fun aFailedActionIsSaidBesideAStillValidSchedule() {
        render(content(actionError = ActionError.Failed(IOException("no"))))

        composeTestRule.onNodeWithText(text(R.string.recurring_action_failed)).assertIsDisplayed()
        // Still true, so still on screen. Reporting a failed pause by throwing the schedule away would be
        // the wrong trade.
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_edit_short)).assertIsDisplayed()
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
        val paidDate = today.minusDays(45)

        render(
            content(
                recentOccurrences = listOf(
                    occurrence(
                        id = 8L,
                        dueDate = paidDate,
                        state = OccurrenceDisplayState.PAID,
                    ),
                    // Deliberately still outstanding: the short history must not offer to settle it,
                    // because a household reading back through October should not be one stray tap from
                    // paying a date they were only looking at.
                    occurrence(
                        id = 7L,
                        dueDate = today.plusDays(5),
                        state = OccurrenceDisplayState.UPCOMING,
                    ),
                ),
            ),
            onOpenOccurrences = { opened.incrementAndGet() },
        )

        composeTestRule.onNodeWithText(text(R.string.recurring_detail_recent_payments))
            .assertIsDisplayed()
        // The row's own line, rather than its "Paid" chip: the payment summary behind it is labelled
        // "Paid" as well, and a count of two would not say which was which.
        composeTestRule.onNodeWithText(text(R.string.recurring_paid_on, DateFormatter.format(paidDate)))
            .assertIsDisplayed()
        // Read-only here: no settle button on a row that is being read back.
        composeTestRule.onNodeWithText(text(R.string.recurring_skip_action)).assertDoesNotExist()
        composeTestRule.onNodeWithText(
            text(
                R.string.recurring_detail_mark_paid,
                CurrencyFormatter.formatInr(scheduleAmount),
            ),
        ).assertDoesNotExist()

        // The history sits at the foot of a screen taller than a phone, so the button that leaves for the
        // full list has to be scrolled to before it can be tapped - as it would be by a household.
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_view_all))
            .performScrollTo()
            .performClick()

        assertEquals(1L, opened.get())
    }

    @Test
    fun aRecentRowSaysHowFarOffItsDateIs() {
        render(
            content(
                recentOccurrences = listOf(
                    occurrence(
                        id = 7L,
                        dueDate = today.plusDays(5),
                        state = OccurrenceDisplayState.UPCOMING,
                    ),
                ),
            ),
        )

        composeTestRule.onNodeWithText(quantity(R.plurals.recurring_due_in_days, 5, 5))
            .assertIsDisplayed()
    }

    @Test
    fun noHistoryMeansNoHistoryCardAtAll() {
        render(content(recentOccurrences = emptyList()))

        composeTestRule.onNodeWithText(text(R.string.recurring_detail_recent_payments))
            .assertDoesNotExist()
    }

    @Test
    fun aPaidOccurrenceWhoseExpenseWasDeletedSaysSo() {
        render(
            content(
                recentOccurrences = listOf(
                    occurrence(
                        id = 8L,
                        dueDate = today.minusDays(45),
                        state = OccurrenceDisplayState.PAID,
                        transactionRemoved = true,
                    ),
                ),
            ),
        )

        // The chip still says "Paid" - the payment was recorded - and the row adds that the entry is
        // gone, rather than quietly re-labelling the date as unpaid. Two nodes carry the word: the
        // row's chip and the payment summary's label.
        composeTestRule.onAllNodesWithText(text(R.string.recurring_occurrence_paid))
            .assertCountEquals(2)
        composeTestRule.onNodeWithText(text(R.string.recurring_occurrence_payment_removed))
            .performScrollTo()
            .assertIsDisplayed()
    }

    // ------------------------------------------------------------ the payment sheet

    @Test
    fun thePaymentSheetStatesWhatWillBeWrittenBeforeAnythingIsConfirmed() {
        val confirmed = AtomicLong()

        render(
            content(outstanding = occurrence(id = 9L, dueDate = today)),
            pay = payState(dueDate = today),
            onConfirmPay = { confirmed.incrementAndGet() },
        )

        composeTestRule.onNodeWithText(text(R.string.recurring_pay_sheet_title_expense))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_pay_sheet_intro)).assertIsDisplayed()
        // The facts the entry will be written with, named plainly. "Category" and "Description" also
        // label the information card behind the sheet, so each is asserted as a pair rather than as one
        // node: the sheet restating them is the point.
        composeTestRule.onAllNodesWithText(text(R.string.recurring_detail_category_label))
            .assertCountEquals(2)
        composeTestRule.onAllNodesWithText(text(R.string.recurring_pay_sheet_description_label))
            .assertCountEquals(2)
        // The facts card scrolls when the screen is shorter than it, so its rows have to be scrolled to
        // first. The field and the buttons below it are pinned and need no scrolling.
        composeTestRule.onNodeWithText(text(R.string.recurring_detail_due_date_label))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_pay_expected_label))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(
            text(
                R.string.recurring_pay_sheet_due_date_value,
                DateFormatter.format(today),
                DateFormatter.formatWeekday(today),
            ),
        ).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_pay_sheet_note_expense)).assertIsDisplayed()

        // The ledger form's own amount field, holding the figure the schedule expected.
        composeTestRule.onNodeWithContentDescription(text(R.string.add_transaction_amount_label))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("15000").assertIsDisplayed()

        // Merely opening it has recorded nothing.
        assertEquals(0L, confirmed.get())

        composeTestRule.onNodeWithText(text(R.string.recurring_pay_sheet_confirm)).performClick()

        assertEquals(1L, confirmed.get())
    }

    @Test
    fun anIncomeOccurrenceSheetSaysItWillWriteIncome() {
        render(
            content(
                card = schedule(categoryType = TransactionType.INCOME),
                outstanding = occurrence(id = 9L, dueDate = today),
            ),
            pay = payState(dueDate = today, type = TransactionType.INCOME),
        )

        composeTestRule.onNodeWithText(text(R.string.recurring_pay_sheet_note_income)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_pay_sheet_title_income))
            .performScrollTo()
            .assertIsDisplayed()
        // An income schedule must not be told its payment was "paid" any more than an expense one may be
        // told it was "received": the sheet's words follow the direction of the entry it will write.
        composeTestRule.onNodeWithText(text(R.string.recurring_pay_sheet_title_expense))
            .assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.recurring_pay_sheet_note_expense))
            .assertDoesNotExist()
    }

    @Test
    fun cancellingThePaymentSheetWritesNothing() {
        val dismissed = AtomicLong()
        val confirmed = AtomicLong()

        render(
            content(outstanding = occurrence(id = 9L, dueDate = today)),
            pay = payState(dueDate = today),
            onConfirmPay = { confirmed.incrementAndGet() },
            onDismissPay = { dismissed.incrementAndGet() },
        )

        composeTestRule.onNodeWithText(text(R.string.action_cancel)).performClick()

        assertEquals(1L, dismissed.get())
        assertEquals(0L, confirmed.get())
    }

    @Test
    fun aRefusedAmountIsSaidInsideThePaymentSheet() {
        render(
            content(outstanding = occurrence(id = 9L, dueDate = today)),
            pay = payState(dueDate = today, amountError = MoneyParseResult.Problem.NotPositive),
        )

        composeTestRule
            .onNodeWithText(text(R.string.add_transaction_amount_error_not_positive))
            .assertIsDisplayed()
    }

    @Test
    fun anAlreadySettledPaymentSaysSoAndRefusesAnotherTap() {
        render(
            content(outstanding = occurrence(id = 9L, dueDate = today)),
            pay = payState(dueDate = today, failure = SettleFailure.AlreadySettled),
        )

        composeTestRule.onNodeWithText(text(R.string.recurring_settle_failure_settled))
            .assertIsDisplayed()
        // Retrying the same tap can only fail again, so the button that would do it is dead.
        composeTestRule.onNodeWithText(text(R.string.recurring_pay_sheet_confirm))
            .assertIsNotEnabled()
        composeTestRule.onNodeWithText(text(R.string.recurring_pay_sheet_note_expense))
            .assertDoesNotExist()
    }

    @Test
    fun theSkipDialogNamesTheScheduleAndTheDateItIsSkipping() {
        val confirmed = AtomicLong()
        val due = LocalDate.of(2026, 10, 5)

        render(
            // No outstanding block behind it, so the dialog's own "Skip" confirm is the only one on
            // screen and there is no ambiguity about which button the test is pressing.
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
    fun neitherSheetNorDialogIsComposedWhileNothingIsOpen() {
        render(content())

        composeTestRule.onNodeWithText(text(R.string.recurring_pay_sheet_title_expense))
            .assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.recurring_pay_sheet_title_income))
            .assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.recurring_skip_title)).assertDoesNotExist()
    }

    // ------------------------------------------------------------------ fixtures

    /** Opens the top bar's overflow menu, where the schedule's own controls live. */
    private fun openActionsMenu() {
        composeTestRule
            .onNodeWithContentDescription(text(R.string.recurring_detail_more_actions))
            .performClick()
    }

    private fun text(id: Int): String = resources.getString(id)

    private fun text(id: Int, vararg args: Any): String = resources.getString(id, *args)

    private fun quantity(id: Int, count: Int, vararg args: Any): String =
        resources.getQuantityString(id, count, *args)

    private val addedAt: Instant = Instant.parse("2026-10-05T04:30:00Z")

    private fun schedule(
        status: RecurringScheduleStatus = RecurringScheduleStatus.ACTIVE,
        intervalCount: Int = 1,
        endDate: LocalDate? = null,
        categoryType: TransactionType = TransactionType.EXPENSE,
        unit: String? = null,
        ratePaise: Long? = null,
    ): RecurringScheduleCard = RecurringScheduleCard(
        id = 1L,
        name = "Shop rent",
        description = "Ground floor, paid by UPI",
        categoryName = "Rent",
        categoryType = categoryType,
        amountPaise = scheduleAmount,
        unit = unit,
        ratePaise = ratePaise,
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
        amountPaise: Long = scheduleAmount,
        state: OccurrenceDisplayState = OccurrenceDisplayState.OVERDUE,
        transactionRemoved: Boolean = false,
    ): RecurringOccurrenceItem = RecurringOccurrenceItem(
        id = id,
        scheduleName = "Shop rent",
        categoryName = "Rent",
        categoryType = TransactionType.EXPENSE,
        dueDate = dueDate,
        amountPaise = amountPaise,
        state = state,
        transactionRemoved = transactionRemoved,
    )

    private fun payState(
        dueDate: LocalDate = LocalDate.of(2026, 10, 5),
        type: TransactionType = TransactionType.EXPENSE,
        amountError: MoneyParseResult.Problem? = null,
        failure: SettleFailure? = null,
    ): PayOccurrenceState = PayOccurrenceState(
        occurrenceId = 9L,
        scheduleName = "Shop rent",
        categoryName = "Rent",
        type = type,
        dueDate = dueDate,
        expectedAmountPaise = scheduleAmount,
        amountInput = "15000",
        amountError = amountError,
        failure = failure,
    )

    private fun content(
        card: RecurringScheduleCard = schedule(),
        outstanding: RecurringOccurrenceItem? = null,
        recentOccurrences: List<RecurringOccurrenceItem> = emptyList(),
        occurrenceCount: Int = 7,
        paidCount: Int = 0,
        upcomingCount: Int = 0,
        overdueCount: Int = 0,
        actionError: ActionError? = null,
    ): RecurringDetailUiState.Content = RecurringDetailUiState.Content(
        schedule = card,
        outstanding = outstanding,
        recentOccurrences = recentOccurrences,
        occurrenceCount = occurrenceCount,
        today = today,
        paidCount = paidCount,
        upcomingCount = upcomingCount,
        overdueCount = overdueCount,
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
