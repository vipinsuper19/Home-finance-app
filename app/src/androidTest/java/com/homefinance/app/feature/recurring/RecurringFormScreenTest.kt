package com.homefinance.app.feature.recurring

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homefinance.app.R
import com.homefinance.app.core.ui.theme.HomeFinanceTheme
import com.homefinance.app.core.util.DateFormatter
import com.homefinance.app.core.util.MoneyParseResult
import com.homefinance.app.data.model.RecurrenceFrequency
import com.homefinance.app.data.model.TransactionType
import com.homefinance.app.feature.addtransaction.CategoriesUiState
import com.homefinance.app.feature.addtransaction.CategoryOption
import com.homefinance.app.feature.addtransaction.EditLoadState
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The recurring expense form, rendered on a device.
 *
 * The form is where a schedule's *rule* is stated, so the things worth asserting are the ones that decide
 * what the engine will later generate: that every field the repository validates can be reached, that a
 * refusal lands beside the control that caused it, and that the four frequency choices and the interval
 * field read as one sentence - "repeat every 2 months" - rather than as two controls that have to be
 * reconciled by the reader.
 *
 * The view model's validation order and its save guard are pinned in `RecurringFormViewModelTest` on the
 * JVM. What only a device can show is that they reach pixels.
 */
@RunWith(AndroidJUnit4::class)
class RecurringFormScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private val today = LocalDate.of(2026, 10, 20)

    // ------------------------------------------------------------- the fields

    @Test
    fun everyFieldAScheduleIsMadeOfIsOnTheForm() {
        render()

        composeTestRule.onNodeWithText(text(R.string.add_transaction_type_expense)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_type_income)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_form_name_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_form_description_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_category_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_form_amount_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_form_unit_rate_switch))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_form_frequency_label))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_start_label))
            .performScrollTo()
            .assertIsDisplayed()
        // The form is taller than a phone, and the fields scroll under a pinned action button, so the
        // ones at the foot have to be scrolled to on the way to being asserted.
        composeTestRule.onNodeWithText(text(R.string.recurring_end_optional_label))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun theUnitPriceFieldsAreHiddenUntilTheyAreAskedFor() {
        render(RecurringFormUiState(startDate = today))

        // Off is the ordinary case - most schedules are not priced by unit - so the form does not open
        // with two fields most households will never fill in.
        composeTestRule.onNodeWithText(text(R.string.recurring_form_unit_label)).assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.recurring_form_rate_label)).assertDoesNotExist()
    }

    @Test
    fun turningTheUnitPriceOnRevealsBothOfItsFields() {
        render(RecurringFormUiState(startDate = today, useUnitAndRate = true))

        composeTestRule.onNodeWithText(text(R.string.recurring_form_unit_label))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_form_rate_label))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun turningTheUnitPriceOnReportsIt() {
        val toggled = AtomicReference<Boolean>()

        render(onUnitRateToggle = { toggled.set(it) })

        // The switch itself, not its label: the label is a plain `Text` beside it, and the only
        // toggleable control on this form is the unit-price switch.
        composeTestRule.onNode(isToggleable()).performClick()

        assertEquals(true, toggled.get())
    }

    @Test
    fun aRefusedUnitIsSaidBesideTheUnit() {
        render(
            RecurringFormUiState(
                startDate = today,
                useUnitAndRate = true,
                showUnitError = true,
            ),
        )

        composeTestRule.onNodeWithText(text(R.string.recurring_form_unit_error))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun aRefusedRateIsSaidBesideTheRateAndNotAsAnAmount() {
        render(
            RecurringFormUiState(
                startDate = today,
                useUnitAndRate = true,
                unitInput = "litre",
                rateError = MoneyParseResult.Problem.Blank,
            ),
        )

        // The rate's own wording, not the amount's: "enter an amount" would point at the wrong field,
        // because this form has an amount field too and it is already filled in.
        composeTestRule.onNodeWithText(text(R.string.add_transaction_rate_error_blank))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun theChosenDirectionIsTheSelectedPill() {
        render(RecurringFormUiState(startDate = today, type = TransactionType.INCOME))

        composeTestRule.onNodeWithText(text(R.string.add_transaction_type_income)).assertIsSelected()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_type_expense)).assertIsDisplayed()
    }

    @Test
    fun choosingIncomeReportsIt() {
        val chosen = AtomicReference<TransactionType>()

        render(onTypeChange = { chosen.set(it) })

        composeTestRule.onNodeWithText(text(R.string.add_transaction_type_income)).performClick()

        assertEquals(TransactionType.INCOME, chosen.get())
    }

    @Test
    fun theIntervalLabelNamesTheUnitOfTheChosenFrequency() {
        render()

        // "Repeat every (months)" - the unit comes from the frequency chosen above it, so the two controls
        // read as one sentence.
        composeTestRule.onNodeWithText(
            text(R.string.recurring_form_interval_label, text(R.string.recurring_frequency_unit_months)),
        ).assertIsDisplayed()
    }

    @Test
    fun theIntervalLabelFollowsTheFrequency() {
        render(RecurringFormUiState(frequency = RecurrenceFrequency.WEEKLY, startDate = today))

        composeTestRule.onNodeWithText(
            text(R.string.recurring_form_interval_label, text(R.string.recurring_frequency_unit_weeks)),
        ).assertIsDisplayed()
    }

    @Test
    fun theStartDateIsShownTheWayTheRestOfTheAppFormatsDates() {
        val start = LocalDate.of(2026, 11, 5)
        render(RecurringFormUiState(startDate = start))

        composeTestRule.onNodeWithText(DateFormatter.format(start)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theStartDateFieldOpensTheDatePicker() {
        render()

        composeTestRule
            .onNodeWithContentDescription(text(R.string.recurring_start_label))
            .performScrollTo()
            .performClick()

        composeTestRule.onNodeWithText(text(R.string.action_ok)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.action_cancel)).assertIsDisplayed()
    }

    @Test
    fun anUnsetLastOccurrenceSaysThereIsNoEndDateAndOffersNoClear() {
        render(RecurringFormUiState(startDate = today, endDate = null))

        composeTestRule.onNodeWithText(text(R.string.recurring_end_none))
            .performScrollTo()
            .assertIsDisplayed()
        // Nothing to take back, so nothing is offered to take back.
        composeTestRule.onNodeWithText(text(R.string.recurring_end_clear)).assertDoesNotExist()
    }

    @Test
    fun aSetLastOccurrenceShowsItsDayAndCanBeCleared() {
        val end = LocalDate.of(2027, 3, 5)
        val cleared = AtomicLong()

        render(
            state = RecurringFormUiState(startDate = today, endDate = end),
            onEndDateChange = { date -> if (date == null) cleared.incrementAndGet() },
        )

        composeTestRule.onNodeWithText(DateFormatter.format(end)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_end_clear)).performScrollTo().performClick()

        assertEquals(1L, cleared.get())
    }

    // ------------------------------------------------------------- validation

    @Test
    fun aRefusedNameIsSaidBesideTheName() {
        render(RecurringFormUiState(startDate = today, showNameError = true))

        composeTestRule.onNodeWithText(text(R.string.recurring_form_name_error)).assertIsDisplayed()
    }

    @Test
    fun aRefusedAmountIsSaidBesideTheAmountUsingTheLedgersOwnWording() {
        render(
            RecurringFormUiState(
                startDate = today,
                amountError = MoneyParseResult.Problem.NotPositive,
            ),
        )

        // The ledger's own sentence, deliberately: "the amount must be more than zero" is the same fact
        // whether an expense is being recorded or a schedule is being set up.
        composeTestRule.onNodeWithText(text(R.string.add_transaction_amount_error_not_positive))
            .assertIsDisplayed()
    }

    @Test
    fun aRefusedIntervalSaysWhichKindOfRefusalItWas() {
        render(
            RecurringFormUiState(
                startDate = today,
                intervalInput = "1.5",
                intervalError = IntervalProblem.NotANumber,
            ),
        )

        composeTestRule.onNodeWithText(text(R.string.recurring_interval_error_not_a_number))
            .assertIsDisplayed()
    }

    @Test
    fun anIntervalOfZeroSaysTheMinimumRatherThanTheShape() {
        render(
            RecurringFormUiState(
                startDate = today,
                intervalInput = "0",
                intervalError = IntervalProblem.TooSmall,
            ),
        )

        composeTestRule.onNodeWithText(text(R.string.recurring_interval_error_too_small))
            .assertIsDisplayed()
    }

    @Test
    fun aRefusedLastOccurrenceIsSaidBesideItAndNotReplacedByThePlaceholder() {
        render(RecurringFormUiState(startDate = today, endDate = today.minusDays(1), showEndDateError = true))

        composeTestRule.onNodeWithText(text(R.string.recurring_end_error))
            .performScrollTo()
            .assertIsDisplayed()
        // The household can still see which day they chose while being told why it is refused.
        composeTestRule.onNodeWithText(DateFormatter.format(today.minusDays(1)))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun aMissingCategoryIsSaidBesideTheCategoryRow() {
        render(RecurringFormUiState(startDate = today, showCategoryError = true))

        composeTestRule.onNodeWithText(text(R.string.add_transaction_category_required)).assertIsDisplayed()
    }

    // ------------------------------------------------------------ what is chosen

    @Test
    fun theChosenFrequencyIsTheSelectedChip() {
        render(RecurringFormUiState(startDate = today, frequency = RecurrenceFrequency.YEARLY))

        composeTestRule.onNodeWithText(text(R.string.recurring_frequency_yearly)).assertIsSelected()
        composeTestRule.onNodeWithText(text(R.string.recurring_frequency_monthly)).assertIsDisplayed()
    }

    @Test
    fun choosingAFrequencyReportsIt() {
        val chosen = AtomicReference<RecurrenceFrequency>()

        render(onFrequencyChange = { chosen.set(it) })

        composeTestRule.onNodeWithText(text(R.string.recurring_frequency_daily)).performClick()

        assertEquals(RecurrenceFrequency.DAILY, chosen.get())
    }

    @Test
    fun aMissingCategoryCanStillBePickedWhileItsRefusalIsShowing() {
        val pickerOpened = AtomicLong()

        render(RecurringFormUiState(startDate = today, showCategoryError = true), onPickCategory = {
            pickerOpened.incrementAndGet()
        })

        composeTestRule.onNodeWithText(text(R.string.add_transaction_category_placeholder))
            .performClick()

        assertEquals(1L, pickerOpened.get())
    }

    @Test
    fun aChosenCategoryIsShownByItsName() {
        render(
            RecurringFormUiState(
                startDate = today,
                category = CategoryOption(id = 3L, name = "Rent"),
                categories = CategoriesUiState.Ready(listOf(CategoryOption(id = 3L, name = "Rent"))),
            ),
        )

        composeTestRule.onNodeWithText("Rent").assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.add_transaction_category_placeholder))
            .assertDoesNotExist()
    }

    // ------------------------------------------------------------------ saving

    @Test
    fun aNewScheduleOffersToSaveIt() {
        render()

        composeTestRule.onNodeWithText(text(R.string.recurring_form_save)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_form_update)).assertDoesNotExist()
    }

    @Test
    fun aChangedScheduleOffersToUpdateItRatherThanToSaveAnotherOne() {
        render(
            state = RecurringFormUiState(startDate = today),
            title = text(R.string.recurring_form_edit_title),
            actionLabel = text(R.string.recurring_form_update),
        )

        // The wording is an argument rather than a constant precisely so that these two can differ:
        // "Save" on an edit would read as though a second schedule were about to be created.
        composeTestRule.onNodeWithText(text(R.string.recurring_form_update)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_form_save)).assertDoesNotExist()
    }

    @Test
    fun tappingTheActionAsksForTheWrite() {
        val saves = AtomicLong()

        render(onSave = { saves.incrementAndGet() })

        composeTestRule.onNodeWithText(text(R.string.recurring_form_save)).performClick()

        assertEquals(1L, saves.get())
    }

    @Test
    fun aWriteInFlightDisablesTheActionAndSaysSo() {
        render(RecurringFormUiState(startDate = today, isSaving = true))

        // The one state where a second tap would mean a second schedule.
        composeTestRule.onNodeWithText(text(R.string.recurring_form_saving)).assertIsNotEnabled()
        composeTestRule.onNodeWithText(text(R.string.recurring_form_save)).assertDoesNotExist()
    }

    @Test
    fun aSavedFormAlsoRefusesASecondTap() {
        render(RecurringFormUiState(startDate = today, savedScheduleId = 7L))

        composeTestRule.onNodeWithText(text(R.string.recurring_form_save)).assertIsNotEnabled()
    }

    @Test
    fun aRefusedWriteIsSaidBesideTheAction() {
        render(
            RecurringFormUiState(
                startDate = today,
                saveError = RecurringSaveError.ScheduleMissing,
            ),
        )

        composeTestRule.onNodeWithText(text(R.string.recurring_form_save_error_missing)).assertIsDisplayed()
    }

    @Test
    fun aFailedWriteIsSaidBesideTheAction() {
        render(
            RecurringFormUiState(
                startDate = today,
                saveError = RecurringSaveError.Failed(IOException("the write failed")),
            ),
        )

        composeTestRule.onNodeWithText(text(R.string.recurring_form_save_error_generic)).assertIsDisplayed()
    }

    // ------------------------------------------------------------- the edit load

    @Test
    fun anEditDrawsNothingAtAllUntilTheRowHasBeenRead() {
        render(RecurringFormUiState(startDate = today, editLoad = EditLoadState.Loading))

        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertIsDisplayed()
        // No fields and no action: an apparently empty form under an edit title would invite the household
        // to fill in figures that were about to be overwritten.
        composeTestRule.onNodeWithText(text(R.string.recurring_form_name_label)).assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.recurring_form_save)).assertDoesNotExist()
    }

    @Test
    fun aFailedEditLoadOffersARetry() {
        val retries = AtomicLong()

        render(
            state = RecurringFormUiState(startDate = today, editLoad = EditLoadState.Failed(IOException("no"))),
            onRetryEditLoad = { retries.incrementAndGet() },
        )

        composeTestRule.onNodeWithText(text(R.string.recurring_form_load_error_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_error_retry)).performClick()

        assertEquals(1L, retries.get())
    }

    @Test
    fun anEditOfAScheduleThatIsGoneSaysSoInsteadOfDrawingABlankForm() {
        val back = AtomicLong()

        render(
            state = RecurringFormUiState(startDate = today, editLoad = EditLoadState.NotFound),
            onBack = { back.incrementAndGet() },
        )

        composeTestRule.onNodeWithText(text(R.string.recurring_missing_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_form_name_label)).assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.recurring_missing_action)).performClick()

        assertEquals(1L, back.get())
    }

    // --------------------------------------------------------------- typing

    @Test
    fun typingIntoTheNameAndTheAmountReportsEachKeystroke() {
        val name = AtomicReference<String>()
        val amount = AtomicReference<String>()

        render(onNameChange = { name.set(it) }, onAmountChange = { amount.set(it) })

        composeTestRule.onNodeWithContentDescription(text(R.string.recurring_form_name_label))
            .performTextInput("Shop rent")
        composeTestRule.onNodeWithContentDescription(text(R.string.recurring_form_amount_label))
            .performTextInput("15000")

        assertEquals("Shop rent", name.get())
        assertEquals("15000", amount.get())
    }

    @Test
    fun typingIntoTheIntervalReportsTheDigits() {
        val interval = AtomicReference<String>()

        render(onIntervalChange = { interval.set(it) })

        val field = composeTestRule.onNodeWithContentDescription(
            text(R.string.recurring_form_interval_label, text(R.string.recurring_frequency_unit_months)),
        )
        // The field opens on the default "every one month", so a household changing the count clears the
        // figure that is there rather than typing next to it.
        field.performTextClearance()
        field.performTextInput("3")

        assertEquals("3", interval.get())
    }

    @Test
    fun theDescriptionIsOptionalAndSaysSo() {
        val description = AtomicReference<String>()

        render(onDescriptionChange = { description.set(it) })

        composeTestRule.onNodeWithContentDescription(text(R.string.recurring_form_description_label))
            .performTextInput("Ground floor, paid by UPI")

        assertEquals("Ground floor, paid by UPI", description.get())
    }

    // ------------------------------------------------------------------ fixtures

    private fun text(id: Int): String = resources.getString(id)

    private fun text(id: Int, vararg args: Any): String = resources.getString(id, *args)

    /**
     * Draws the form the way the screen does, with the state held live and adopted from every callback.
     *
     * The state has to be live for the same reason the view model's is: a text field whose caller never
     * takes what was typed reverts on the next recomposition, so a form pinned to one frozen snapshot
     * cannot be typed into at all - which is a fact about the harness rather than about the form. The
     * argument lambdas are still called first and unchanged, so a test can both observe what the form
     * reported and see the form respond the way it does in the app.
     */
    private fun render(
        state: RecurringFormUiState = RecurringFormUiState(startDate = today),
        title: String = text(R.string.recurring_form_add_title),
        actionLabel: String = text(R.string.recurring_form_save),
        onBack: () -> Unit = {},
        onNameChange: (String) -> Unit = {},
        onDescriptionChange: (String) -> Unit = {},
        onTypeChange: (TransactionType) -> Unit = {},
        onAmountChange: (String) -> Unit = {},
        onUnitRateToggle: (Boolean) -> Unit = {},
        onUnitChange: (String) -> Unit = {},
        onRateChange: (String) -> Unit = {},
        onPickCategory: () -> Unit = {},
        onFrequencyChange: (RecurrenceFrequency) -> Unit = {},
        onIntervalChange: (String) -> Unit = {},
        onStartDateChange: (LocalDate) -> Unit = {},
        onEndDateChange: (LocalDate?) -> Unit = {},
        onSave: () -> Unit = {},
        onRetryEditLoad: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            var live by remember { mutableStateOf(state) }

            HomeFinanceTheme {
                RecurringFormContent(
                    uiState = live,
                    title = title,
                    actionLabel = actionLabel,
                    progressLabel = text(R.string.recurring_form_saving),
                    onBack = onBack,
                    onNameChange = { onNameChange(it); live = live.copy(name = it) },
                    onDescriptionChange = {
                        onDescriptionChange(it)
                        live = live.copy(description = it)
                    },
                    onTypeChange = { onTypeChange(it); live = live.copy(type = it, category = null) },
                    onAmountChange = { onAmountChange(it); live = live.copy(amountInput = it) },
                    onUnitRateToggle = {
                        onUnitRateToggle(it)
                        live = live.copy(useUnitAndRate = it)
                    },
                    onUnitChange = { onUnitChange(it); live = live.copy(unitInput = it) },
                    onRateChange = { onRateChange(it); live = live.copy(rateInput = it) },
                    onPickCategory = onPickCategory,
                    onFrequencyChange = { onFrequencyChange(it); live = live.copy(frequency = it) },
                    onIntervalChange = { onIntervalChange(it); live = live.copy(intervalInput = it) },
                    onStartDateChange = { onStartDateChange(it); live = live.copy(startDate = it) },
                    onEndDateChange = { onEndDateChange(it); live = live.copy(endDate = it) },
                    onSave = onSave,
                    onRetryEditLoad = onRetryEditLoad,
                )
            }
        }
    }
}
