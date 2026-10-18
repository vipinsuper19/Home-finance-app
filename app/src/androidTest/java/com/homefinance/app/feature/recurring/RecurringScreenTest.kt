package com.homefinance.app.feature.recurring

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homefinance.app.R
import com.homefinance.app.core.ui.theme.HomeFinanceTheme
import com.homefinance.app.core.util.CurrencyFormatter
import com.homefinance.app.core.util.DateFormatter
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
 * The Recurring tab, rendered on a device.
 *
 * The list is where the feature is judged: it has to say what each schedule is, how often it comes
 * round, how much it expects, when it is next due, and - when it is late - that it is late *in words*,
 * because the household this row is meant to catch the eye of cannot be relied on to see red. Those are
 * the assertions here.
 *
 * The view model's own rules are pinned in `RecurringListViewModelTest` on the JVM. What only a device
 * can show is that the state reaches pixels: that the empty list is drawn as an invitation rather than an
 * error, that a failed action does not replace a perfectly good list, and that the overdue marker survives
 * being drawn.
 */
@RunWith(AndroidJUnit4::class)
class RecurringScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private val today = LocalDate.of(2026, 10, 20)

    // ------------------------------------------------------ loading, error, empty

    @Test
    fun theFirstReadShowsAProgressIndicator() {
        render(RecurringListUiState.Loading)

        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertIsDisplayed()
    }

    @Test
    fun aFailedReadShowsTheErrorWithARetryAndSaysNothingWasChanged() {
        val retries = AtomicLong()

        render(
            uiState = RecurringListUiState.Failed(IOException("the schedules could not be read")),
            onRetry = { retries.incrementAndGet() },
        )

        composeTestRule.onNodeWithText(text(R.string.recurring_error_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_error_message)).assertIsDisplayed()

        composeTestRule.onNodeWithText(text(R.string.recurring_error_retry)).performClick()

        assertEquals(1L, retries.get())
    }

    @Test
    fun theEmptyListIsAnInvitationAndNotAFailure() {
        val adds = AtomicLong()

        render(uiState = RecurringListUiState.Ready(emptyList()), onAddSchedule = { adds.incrementAndGet() })

        composeTestRule.onNodeWithText(text(R.string.recurring_empty_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_empty_message)).assertIsDisplayed()

        // A successful read of nothing is not an error, so no retry is offered - the household has
        // nothing to retry.
        composeTestRule.onNodeWithText(text(R.string.recurring_error_retry)).assertDoesNotExist()

        composeTestRule.onNodeWithText(text(R.string.recurring_empty_action)).performClick()

        assertEquals(1L, adds.get())
    }

    @Test
    fun theAddActionIsOfferedInEveryState() {
        val adds = AtomicLong()

        // It lives in the app bar, so it is on screen while the first read is still running and while a
        // read has failed - the two states that have nothing else to offer.
        render(RecurringListUiState.Loading, onAddSchedule = { adds.incrementAndGet() })
        composeTestRule.onNodeWithContentDescription(text(R.string.recurring_add)).performClick()

        assertEquals(1L, adds.get())
    }

    @Test
    fun theAddActionIsOfferedOnAFailedReadToo() {
        val adds = AtomicLong()

        render(
            uiState = RecurringListUiState.Failed(IOException("the schedules could not be read")),
            onAddSchedule = { adds.incrementAndGet() },
        )

        composeTestRule.onNodeWithContentDescription(text(R.string.recurring_add)).performClick()

        assertEquals(1L, adds.get())
    }

    // -------------------------------------------------------------------- a row

    @Test
    fun aRowSaysWhatTheScheduleIsHowOftenAndHowMuch() {
        render(row(schedule(id = 1L, name = "Shop rent", category = "Rent", amountPaise = 1_500_000L)))

        composeTestRule.onNodeWithText("Shop rent").assertIsDisplayed()
        // The rule and the figure on one line, built from the same formatter the ledger uses.
        composeTestRule.onNodeWithText("Monthly • ${CurrencyFormatter.formatInr(1_500_000L)}")
            .assertIsDisplayed()
    }

    @Test
    fun aRowShowsTheUnitPriceWhenTheHouseholdGaveOne() {
        render(
            row(
                schedule(
                    id = 1L,
                    name = "Milk",
                    category = "Milk",
                    amountPaise = 6_000L,
                    frequency = RecurrenceFrequency.DAILY,
                    intervalCount = 1,
                    unit = "litre",
                    ratePaise = 6_000L,
                ),
            ),
        )

        // The rate *instead of* the expected amount, which is the design's own line - see
        // [RecurringScheduleRow] for why the two are not printed together.
        composeTestRule
            .onNodeWithText(
                "Daily • " + text(
                    R.string.recurring_rate_per_unit,
                    CurrencyFormatter.formatInr(6_000L),
                    "litre",
                ),
            )
            .assertIsDisplayed()
    }

    @Test
    fun aRowDoesNotRepeatTheDescriptionOrTheCategoryName() {
        render(row(schedule(id = 1L, name = "Shop rent", category = "Rent")))

        // Both facts belong to the details screen. A row that grew a line for every fact a schedule has
        // would stop being scannable, which is the one thing a list of bills has to be.
        composeTestRule.onNodeWithText("Ground floor, paid by UPI").assertDoesNotExist()
        composeTestRule.onNodeWithText("Rent").assertDoesNotExist()
    }

    @Test
    fun aRowShowsWhenItIsNextDue() {
        val due = LocalDate.of(2026, 11, 5)
        render(
            row(
                schedule(
                    id = 1L,
                    nextDueDate = due,
                    nextDueState = OccurrenceDisplayState.UPCOMING,
                ),
            ),
        )

        composeTestRule.onNodeWithText(text(R.string.recurring_next_due, DateFormatter.format(due)))
            .assertIsDisplayed()
    }

    @Test
    fun aDueTodayRowSaysSoRatherThanShowingATautologicalDate() {
        render(
            row(
                schedule(
                    id = 1L,
                    nextDueDate = today,
                    nextDueState = OccurrenceDisplayState.DUE,
                ),
            ),
        )

        composeTestRule.onNodeWithText(text(R.string.recurring_occurrence_due)).assertIsDisplayed()
    }

    @Test
    fun anOverdueRowSaysOverdueInWordsAndNotOnlyInColour() {
        val due = LocalDate.of(2026, 10, 5)
        render(
            row(
                schedule(
                    id = 1L,
                    nextDueDate = due,
                    nextDueState = OccurrenceDisplayState.OVERDUE,
                ),
            ),
        )

        // The word, with the date it started being late. Colour is added on top of this and never carries
        // the message on its own.
        composeTestRule
            .onNodeWithText(text(R.string.recurring_overdue_since, DateFormatter.format(due)))
            .assertIsDisplayed()
    }

    @Test
    fun aScheduleWithNothingPendingSaysSo() {
        render(row(schedule(id = 1L, nextDueDate = null, nextDueState = null)))

        composeTestRule.onNodeWithText(text(R.string.recurring_nothing_outstanding)).assertIsDisplayed()
    }

    @Test
    fun aStoppedScheduleIsNotReportedAsOverdue() {
        val due = LocalDate.of(2026, 5, 5)
        render(
            row(
                schedule(
                    id = 1L,
                    status = RecurringScheduleStatus.RETIRED,
                    nextDueDate = due,
                    nextDueState = OccurrenceDisplayState.OVERDUE,
                ),
            ),
        )

        // The chip says "Stopped"; shouting about a date the household deliberately abandoned would train
        // them to ignore the marker everywhere else.
        composeTestRule.onNodeWithText(text(R.string.recurring_status_retired)).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(text(R.string.recurring_overdue_since, DateFormatter.format(due)))
            .assertDoesNotExist()
    }

    @Test
    fun aPausedScheduleCarriesItsWordAndItsSwitchIsOff() {
        render(row(schedule(id = 1L, status = RecurringScheduleStatus.PAUSED)))

        composeTestRule.onNodeWithText(text(R.string.recurring_status_paused)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Shop rent").assertIsDisplayed()
    }

    @Test
    fun anIntervalOfMoreThanOneIsCountedRatherThanNamed() {
        render(
            row(
                schedule(
                    id = 1L,
                    frequency = RecurrenceFrequency.MONTHLY,
                    intervalCount = 2,
                ),
            ),
        )

        // "Every 2 months", not "Monthly": the interval is a fact about the rule and has to be said.
        composeTestRule.onNodeWithText("Every 2 months • ${CurrencyFormatter.formatInr(1_500_000L)}")
            .assertIsDisplayed()
    }

    @Test
    fun tappingARowOpensThatSchedulesDetails() {
        val opened = AtomicLong()

        render(
            rows = listOf(
                schedule(id = 4L, name = "Broadband"),
                schedule(id = 9L, name = "Shop rent"),
            ),
            onOpenSchedule = { opened.set(it) },
        )

        composeTestRule.onNodeWithText("Shop rent").performClick()

        // The id of the row that was tapped, not a position in the list: a row that moves when the list
        // re-sorts must not open a different schedule.
        assertEquals(9L, opened.get())
    }

    @Test
    fun theActiveSwitchIsOffForAStoppedScheduleAndCannotBeTurnedOn() {
        render(row(schedule(id = 1L, status = RecurringScheduleStatus.RETIRED)))

        composeTestRule.onNode(isOff()).assertIsOff()
        composeTestRule.onNode(isOff()).assertIsNotEnabled()
    }

    @Test
    fun theActiveSwitchIsOnForARunningScheduleAndTurnsItOff() {
        val changes = mutableListOf<Pair<Long, Boolean>>()

        render(
            rows = listOf(schedule(id = 1L)),
            onActiveChange = { id, active -> changes += id to active },
        )

        composeTestRule.onNode(isOn()).assertIsOn()
        composeTestRule.onNode(isOn()).performClick()

        assertEquals(listOf(1L to false), changes)
    }

    // -------------------------------------------------- a failed action, and the list

    @Test
    fun aFailedActionIsShownBesideAStillValidList() {
        render(
            RecurringListUiState.Ready(
                schedules = listOf(schedule(id = 1L)),
                actionError = ActionError.Failed(IOException("the pause could not be written")),
            ),
        )

        composeTestRule.onNodeWithText(text(R.string.recurring_action_failed)).assertIsDisplayed()
        // The list is still true, so it is still on screen. Throwing the household's own data away in
        // order to report a message would be the wrong trade.
        composeTestRule.onNodeWithText("Shop rent").assertIsDisplayed()
    }

    @Test
    fun aFailedGenerationIsReportedWithoutClaimingTheSchedulesAreWrong() {
        render(
            RecurringListUiState.Ready(
                schedules = listOf(schedule(id = 1L)),
                actionError = ActionError.GenerationFailed(IOException("the engine could not run")),
            ),
        )

        composeTestRule.onNodeWithText(text(R.string.recurring_generation_failed)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Shop rent").assertIsDisplayed()
    }

    // ----------------------------------------------------------------- the filter

    @Test
    fun theFilterBarOffersAllExpenseAndIncome() {
        render(row(schedule(id = 1L)))

        composeTestRule.onNodeWithText(text(R.string.transactions_filter_all)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.transactions_filter_expense)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.transactions_filter_income)).assertIsDisplayed()
    }

    @Test
    fun choosingAFilterReportsIt() {
        val chosen = AtomicReference<ScheduleTypeFilter>()

        render(row(schedule(id = 1L)), onFilterChange = { chosen.set(it) })

        composeTestRule.onNodeWithText(text(R.string.transactions_filter_income)).performClick()

        assertEquals(ScheduleTypeFilter.INCOME, chosen.get())
    }

    @Test
    fun aFilterThatMatchesNothingIsNotDrawnAsNoSchedulesAtAll() {
        render(
            RecurringListUiState.Ready(
                schedules = emptyList(),
                filter = ScheduleTypeFilter.INCOME,
                hasAnySchedule = true,
            ),
        )

        // The bar stays where it is, because the way out of this screen is to choose another chip.
        composeTestRule.onNodeWithText(text(R.string.transactions_filter_all)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.recurring_no_matches)).assertIsDisplayed()
        // And it must not claim the household has nothing, which would be a lie about their own money.
        composeTestRule.onNodeWithText(text(R.string.recurring_empty_title)).assertDoesNotExist()
    }

    @Test
    fun theFilterBarIsNotOfferedWhenThereIsNothingToFilter() {
        render(RecurringListUiState.Ready(emptyList()))

        composeTestRule.onNodeWithText(text(R.string.recurring_empty_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.transactions_filter_all)).assertDoesNotExist()
    }

    @Test
    fun anIncomeSchedulesRowIsDrawnLikeAnyOther() {
        render(
            row(
                schedule(
                    id = 1L,
                    name = "Salary",
                    category = "Salary",
                    categoryType = TransactionType.INCOME,
                    amountPaise = 4_500_000L,
                ),
            ),
        )

        // The mark's colour and glyph come from the category's direction, and nothing about the row's
        // *words* changes: a schedule is a schedule whichever way the money moves.
        composeTestRule.onNodeWithText("Salary").assertIsDisplayed()
        composeTestRule.onNodeWithText("Monthly • ${CurrencyFormatter.formatInr(4_500_000L)}")
            .assertIsDisplayed()
    }

    // ------------------------------------------------------------------ fixtures

    private fun text(id: Int): String = resources.getString(id)

    private fun text(id: Int, vararg args: Any): String = resources.getString(id, *args)

    private fun schedule(
        id: Long,
        name: String = "Shop rent",
        description: String? = "Ground floor, paid by UPI",
        category: String = "Rent",
        amountPaise: Long = 1_500_000L,
        frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
        intervalCount: Int = 1,
        status: RecurringScheduleStatus = RecurringScheduleStatus.ACTIVE,
        nextDueDate: LocalDate? = LocalDate.of(2026, 10, 5),
        nextDueState: OccurrenceDisplayState? = OccurrenceDisplayState.OVERDUE,
        categoryType: TransactionType = TransactionType.EXPENSE,
        unit: String? = null,
        ratePaise: Long? = null,
    ): RecurringScheduleCard = RecurringScheduleCard(
        id = id,
        name = name,
        description = description,
        categoryName = category,
        categoryType = categoryType,
        amountPaise = amountPaise,
        unit = unit,
        ratePaise = ratePaise,
        frequency = frequency,
        intervalCount = intervalCount,
        status = status,
        startDate = LocalDate.of(2026, 10, 5),
        endDate = null,
        addedAt = Instant.parse("2026-10-05T04:30:00Z"),
        nextDueDate = nextDueDate,
        nextDueState = nextDueState,
    )

    private fun row(card: RecurringScheduleCard): RecurringListUiState.Ready =
        RecurringListUiState.Ready(schedules = listOf(card))

    private fun render(
        uiState: RecurringListUiState,
        onRetry: () -> Unit = {},
        onAddSchedule: () -> Unit = {},
        onOpenSchedule: (Long) -> Unit = {},
        onActiveChange: (Long, Boolean) -> Unit = { _, _ -> },
        onFilterChange: (ScheduleTypeFilter) -> Unit = {},
    ) {
        composeTestRule.setContent {
            HomeFinanceTheme {
                RecurringContent(
                    uiState = uiState,
                    onRetry = onRetry,
                    onAddSchedule = onAddSchedule,
                    onOpenSchedule = onOpenSchedule,
                    onActiveChange = onActiveChange,
                    onFilterChange = onFilterChange,
                )
            }
        }
    }

    private fun render(
        rows: List<RecurringScheduleCard>,
        onOpenSchedule: (Long) -> Unit = {},
        onActiveChange: (Long, Boolean) -> Unit = { _, _ -> },
    ) {
        render(
            uiState = RecurringListUiState.Ready(rows),
            onOpenSchedule = onOpenSchedule,
            onActiveChange = onActiveChange,
        )
    }
}
