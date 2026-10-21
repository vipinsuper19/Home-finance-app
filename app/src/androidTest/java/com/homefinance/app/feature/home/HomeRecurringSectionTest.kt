package com.homefinance.app.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homefinance.app.R
import com.homefinance.app.core.ui.theme.HomeFinanceTheme
import com.homefinance.app.core.util.CurrencyFormatter
import com.homefinance.app.data.model.RecurrenceFrequency
import com.homefinance.app.data.model.RecurringScheduleStatus
import com.homefinance.app.data.model.TransactionType
import com.homefinance.app.feature.recurring.OccurrenceDisplayState
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Home's recurring section, rendered on a device.
 *
 * The view model's rules - what is overdue, what the preview may trim, what a failed run does - are
 * pinned on the JVM in `HomeViewModelTest`. What only a device can show is that those readings survive
 * being drawn: that a late date says *in words* how late it is, that a group heading counts every
 * category rather than the five the preview drew, that an empty group is said rather than left blank,
 * and that a section which could not be read does not take the rest of the dashboard down with it.
 */
@RunWith(AndroidJUnit4::class)
class HomeRecurringSectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    // ------------------------------------------------------------------ the three groups

    @Test
    fun theGroupsAreDrawnInTheOrderTheyAreRead() {
        renderSection(
            ready(
                overdue = listOf(item(id = 1L, name = "Electricity bill", dueDate = TODAY.minusDays(3))),
                dueToday = listOf(item(id = 2L, name = "Shop rent", dueDate = TODAY)),
                upcoming = listOf(item(id = 3L, name = "Water bill", dueDate = TODAY.plusDays(5))),
            ),
        )

        composeTestRule.onNodeWithText(text(R.string.home_recurring_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(heading(R.string.home_recurring_group_overdue, 1)).assertIsDisplayed()
        composeTestRule.onNodeWithText(heading(R.string.home_recurring_group_due_today, 1)).assertIsDisplayed()
        composeTestRule.onNodeWithText(heading(R.string.home_recurring_group_upcoming, 1)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Electricity bill").assertIsDisplayed()
        composeTestRule.onNodeWithText("Shop rent").assertIsDisplayed()
        composeTestRule.onNodeWithText("Water bill").assertIsDisplayed()
    }

    @Test
    fun anOverdueRowSaysHowLateItIsInWords() {
        renderSection(
            ready(overdue = listOf(item(id = 1L, name = "Electricity bill", dueDate = TODAY.minusDays(3)))),
        )

        // Red is not the message: the row has to say it, because the reader this is meant to catch
        // cannot be relied on to tell two container colours apart.
        composeTestRule
            .onNodeWithText(quantityText(R.plurals.recurring_overdue_by_days, 3, 3))
            .assertIsDisplayed()
    }

    @Test
    fun aDueTodayRowSaysSoAndAnUpcomingRowSaysHowFarOffItIs() {
        renderSection(
            ready(
                dueToday = listOf(item(id = 1L, name = "Shop rent", dueDate = TODAY)),
                upcoming = listOf(
                    item(id = 2L, name = "Water bill", dueDate = TODAY.plusDays(1)),
                    item(id = 3L, name = "Credit card", dueDate = TODAY.plusDays(7)),
                ),
            ),
        )

        composeTestRule.onNodeWithText(text(R.string.recurring_occurrence_due)).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(quantityText(R.plurals.recurring_due_in_days, 1, 1))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(quantityText(R.plurals.recurring_due_in_days, 7, 7))
            .assertIsDisplayed()
    }

    @Test
    fun aRowShowsTheAmountAndHowTheDateComesRound() {
        renderSection(
            ready(
                dueToday = listOf(
                    item(
                        id = 1L,
                        name = "Shop rent",
                        dueDate = LocalDate.of(2026, 11, 5),
                        amountPaise = 1_500_000L,
                        state = OccurrenceDisplayState.DUE,
                        daysFromToday = 0L,
                    ),
                ),
            ),
        )

        composeTestRule.onNodeWithText(CurrencyFormatter.formatInr(1_500_000L)).assertIsDisplayed()
        // The date's own day of the month, so "on 5th" describes the date the row is next to.
        composeTestRule.onNodeWithText("Monthly (on 5th)").assertIsDisplayed()
    }

    @Test
    fun aDateThatOutlivedItsScheduleSaysWhichStateThatScheduleIsIn() {
        renderSection(
            ready(
                overdue = listOf(
                    item(
                        id = 1L,
                        name = "Broadband",
                        dueDate = TODAY.minusDays(2),
                        scheduleStatus = RecurringScheduleStatus.PAUSED,
                    ),
                ),
                upcoming = listOf(
                    item(
                        id = 2L,
                        name = "Magazine",
                        dueDate = TODAY.plusDays(6),
                        scheduleStatus = RecurringScheduleStatus.RETIRED,
                    ),
                ),
            ),
        )

        // Still drawn - those dates stay payable after a pause or a stop - and the row says why rather
        // than leaving a bill from a schedule that was switched off looking like a fault.
        composeTestRule.onNodeWithText("Broadband").assertIsDisplayed()
        composeTestRule.onNodeWithText("Monthly (on 18th) · Paused").assertIsDisplayed()
        composeTestRule.onNodeWithText("Monthly (on 26th) · Stopped").assertIsDisplayed()
    }

    @Test
    fun theUpcomingHeadingCountsEveryCategoryRatherThanOnlyTheFiveThePreviewDrew() {
        renderSection(
            ready(
                upcoming = (1L..5L).map { index ->
                    item(id = index, name = "Bill $index", dueDate = TODAY.plusDays(index))
                },
                upcomingTotal = 8,
            ),
        )

        // A preview stops at five categories; the heading must not quietly become "there are only
        // five". The view model keeps one date per category before this state is built.
        composeTestRule.onNodeWithText(heading(R.string.home_recurring_group_upcoming, 8)).assertIsDisplayed()
    }

    // ------------------------------------------------------------------ empty states

    @Test
    fun anEmptyGroupIsSaidRatherThanLeftBlank() {
        renderSection(
            ready(
                overdue = listOf(item(id = 1L, name = "Electricity bill", dueDate = TODAY.minusDays(3))),
            ),
        )

        composeTestRule.onNodeWithText(text(R.string.home_recurring_nothing_due_today)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.home_recurring_no_upcoming)).assertIsDisplayed()
    }

    @Test
    fun aHouseholdWithNoSchedulesIsInvitedToAddOneRatherThanShownAnEmptyList() {
        val opens = AtomicLong()

        renderSection(
            state = HomeRecurringUiState.Ready(
                overdue = emptyList(),
                dueToday = emptyList(),
                upcoming = emptyList(),
                hasAnySchedule = false,
            ),
            onAddSchedule = { opens.incrementAndGet() },
        )

        composeTestRule.onNodeWithText(text(R.string.home_recurring_empty_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.home_recurring_empty_message)).assertIsDisplayed()

        // Nothing to look at, so nothing is offered to look at...
        composeTestRule.onNodeWithText(text(R.string.home_recurring_view_all)).assertDoesNotExist()
        // ... and nothing invented to fill the space either.
        composeTestRule.onNodeWithText(text(R.string.home_recurring_no_upcoming)).assertDoesNotExist()

        composeTestRule.onNodeWithText(text(R.string.recurring_empty_action)).performClick()

        assertEquals(1L, opens.get())
    }

    @Test
    fun aSettledHouseholdReadsTwoTrueSentencesRatherThanAFreshInstallInvitation() {
        renderSection(
            state = HomeRecurringUiState.Ready(
                overdue = emptyList(),
                dueToday = emptyList(),
                upcoming = emptyList(),
                hasAnySchedule = true,
            ),
        )

        composeTestRule.onNodeWithText(text(R.string.home_recurring_empty_title)).assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.home_recurring_nothing_due_today)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.home_recurring_no_upcoming)).assertIsDisplayed()
    }

    // ------------------------------------------------------------------ failing

    @Test
    fun aFailedSectionOffersARetryWithoutTakingTheDashboardDownWithIt() {
        val retries = AtomicLong()

        composeTestRule.setContent {
            HomeFinanceTheme {
                HomeContent(
                    uiState = HomeUiState(
                        balanceInPaise = 0L,
                        incomeInPaise = 0L,
                        expenseInPaise = 0L,
                        recurring = HomeRecurringUiState.Failed(IOException("could not be read")),
                    ),
                    onOpenSettings = {},
                    onOpenSchedule = {},
                    onOpenRecurring = {},
                    onAddSchedule = {},
                    onRetry = { retries.incrementAndGet() },
                )
            }
        }

        // The figures above are unaffected: the section failed, not the dashboard.
        composeTestRule.onNodeWithText(text(R.string.home_balance_label)).assertIsDisplayed()

        composeTestRule.onNodeWithText(text(R.string.home_recurring_error)).performScrollTo().assertIsDisplayed()

        // The sentence says it could not be read. It never says nothing is due, because that is a
        // different - and untrue - claim.
        composeTestRule.onNodeWithText(text(R.string.home_recurring_nothing_due_today)).assertDoesNotExist()

        composeTestRule.onNodeWithText(text(R.string.recurring_error_retry)).performClick()

        assertEquals(1L, retries.get())
    }

    @Test
    fun aFailedEngineRunIsReportedBesideTheRowsItCouldNotComplete() {
        renderSection(
            ready(
                dueToday = listOf(item(id = 1L, name = "Shop rent", dueDate = TODAY)),
                generationFailed = true,
            ),
        )

        // The rows are still true, so they stay; the run reports itself alongside them.
        composeTestRule.onNodeWithText(text(R.string.recurring_generation_failed)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Shop rent").assertIsDisplayed()
    }

    // ------------------------------------------------------------------ getting somewhere

    @Test
    fun everyWayInLeadsToTheRecurringTabOrTheScheduleItBelongsTo() {
        val opened = AtomicLong()
        val viewed = AtomicLong()

        renderSection(
            ready(
                overdue = listOf(item(id = 1L, scheduleId = 42L, name = "Electricity bill", dueDate = TODAY.minusDays(3))),
            ),
            onOpenSchedule = { id -> opened.set(id) },
            onOpenRecurring = { viewed.incrementAndGet() },
        )

        composeTestRule.onNodeWithText(text(R.string.home_recurring_view_all)).performClick()
        composeTestRule
            .onNodeWithText(heading(R.string.home_recurring_group_overdue, 1))
            .performClick()
        composeTestRule.onNodeWithText("Electricity bill").performClick()

        // The two headings lead to the tab that lists everything; the row leads to the one schedule it
        // belongs to, which is where the date can actually be settled.
        assertEquals(2L, viewed.get())
        assertEquals(42L, opened.get())
    }

    // ------------------------------------------------------------------ fixtures

    private fun renderSection(
        state: HomeRecurringUiState,
        onOpenSchedule: (Long) -> Unit = {},
        onOpenRecurring: () -> Unit = {},
        onAddSchedule: () -> Unit = {},
        onRetry: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            HomeFinanceTheme {
                Box {
                    RecurringPaymentsCard(
                        state = state,
                        onOpenRecurring = onOpenRecurring,
                        onOpenSchedule = onOpenSchedule,
                        onAddSchedule = onAddSchedule,
                        onRetry = onRetry,
                    )
                }
            }
        }
    }

    private fun text(id: Int): String = resources.getString(id)

    private fun heading(id: Int, count: Int): String = resources.getString(id, count)

    private fun quantityText(id: Int, quantity: Int, count: Int): String =
        resources.getQuantityString(id, quantity, count)

    private companion object {

        val TODAY: LocalDate = LocalDate.of(2026, 10, 20)

        /**
         * One row, described by the facts the screen is handed rather than by the words it draws - the
         * wording is the screen's job, and these tests assert on it.
         */
        fun item(
            id: Long,
            name: String,
            dueDate: LocalDate,
            scheduleId: Long = id,
            amountPaise: Long = 250_000L,
            scheduleStatus: RecurringScheduleStatus = RecurringScheduleStatus.ACTIVE,
            state: OccurrenceDisplayState = when {
                dueDate.isBefore(TODAY) -> OccurrenceDisplayState.OVERDUE
                dueDate == TODAY -> OccurrenceDisplayState.DUE
                else -> OccurrenceDisplayState.UPCOMING
            },
            daysFromToday: Long = java.time.temporal.ChronoUnit.DAYS.between(TODAY, dueDate),
        ): HomeRecurringItem = HomeRecurringItem(
            occurrenceId = id,
            scheduleId = scheduleId,
            categoryId = scheduleId,
            scheduleName = name,
            categoryName = "Utilities",
            categoryType = TransactionType.EXPENSE,
            dueDate = dueDate,
            amountPaise = amountPaise,
            state = state,
            daysFromToday = daysFromToday,
            frequency = RecurrenceFrequency.MONTHLY,
            intervalCount = 1,
            scheduleStatus = scheduleStatus,
        )

        fun ready(
            overdue: List<HomeRecurringItem> = emptyList(),
            dueToday: List<HomeRecurringItem> = emptyList(),
            upcoming: List<HomeRecurringItem> = emptyList(),
            upcomingTotal: Int = upcoming.size,
            generationFailed: Boolean = false,
        ): HomeRecurringUiState.Ready = HomeRecurringUiState.Ready(
            overdue = overdue,
            dueToday = dueToday,
            upcoming = upcoming,
            upcomingTotal = upcomingTotal,
            hasAnySchedule = true,
            generationFailed = generationFailed,
        )
    }
}
