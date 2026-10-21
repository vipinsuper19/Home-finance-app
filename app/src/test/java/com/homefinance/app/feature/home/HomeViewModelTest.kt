package com.homefinance.app.feature.home

import androidx.lifecycle.ViewModelStore
import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.data.local.entity.RecurringOccurrenceEntity
import com.homefinance.app.data.local.entity.RecurringScheduleEntity
import com.homefinance.app.data.local.relation.TransactionTotals
import com.homefinance.app.data.model.RecurrenceFrequency
import com.homefinance.app.data.model.RecurringOccurrenceStatus
import com.homefinance.app.data.model.RecurringScheduleStatus
import com.homefinance.app.data.model.TransactionType
import com.homefinance.app.data.repository.CategoryRepository
import com.homefinance.app.data.repository.DirectTransactionRunner
import com.homefinance.app.data.repository.FakeCategoryDao
import com.homefinance.app.data.repository.FakeRecurringDao
import com.homefinance.app.data.repository.FakeTransactionDao
import com.homefinance.app.data.repository.RecurrenceGenerator
import com.homefinance.app.data.repository.RecurringRepository
import com.homefinance.app.data.repository.TransactionRepository
import com.homefinance.app.feature.recurring.OccurrenceDisplayState
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Home screen's state, driven over the in-memory fakes behind the real repository.
 *
 * Five things are pinned here, and all five are things no other layer can check as cheaply:
 *
 *  - **The summary.** The figures are the month's ledger totals, with the balance as their net, and
 *    they start at zero rather than inventing sample data before the ledger answers.
 *  - **The classification.** A date is overdue, due today or upcoming depending on where it falls
 *    against one known today. The two boundaries either side of today are the ones worth asserting,
 *    because that is where an off-by-one turns a late bill into a comfortable one.
 *  - **What the section refuses to be.** A preview is not a filter: limiting the drawn upcoming
 *    categories must not drop a single record, one date per category must not duplicate a category,
 *    and a reading that failed must not look like a month with nothing in it. Likewise, a settled or
 *    paused schedule's dates never appear as work to do.
 *  - **The engine run.** Opening Home is one of the two places the recurrence engine runs, and it is
 *    the place a household actually opens. These tests prove the run happens, that it is safe to run
 *    twice, and that it never writes a ledger entry - a bill falling due is not a payment.
 *  - **That expectations stay out of the totals.** Overdue, due and upcoming dates are money that has
 *    *not* moved. They are asserted never to touch the figures the household reads above them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    /**
     * Owns the view models so `onCleared` runs, which is what stops the `WhileSubscribed` sharing
     * coroutine outliving the test.
     */
    private val viewModelStore = ViewModelStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------ the summary figures

    @Test
    fun theSummaryStartsAtZeroRatherThanInventingFigures() {
        val state = Fixture().viewModel().uiState.value

        // Before the ledger has answered there is nothing to claim, and no sample badge dressing a
        // made-up figure as the household's own.
        assertEquals(0L, state.balanceInPaise)
        assertEquals(0L, state.incomeInPaise)
        assertEquals(0L, state.expenseInPaise)
    }

    @Test
    fun theSummaryFiguresComeFromTheMonthsLedgerTotals() = runTest {
        val totals = TransactionTotals(
            incomePaise = 4_850_000L,
            expensePaise = 5_734_050L,
            incomeCount = 3L,
            expenseCount = 9L,
        )
        val viewModel = Fixture().viewModel(monthlyTotals = flowOf(totals))

        collectUiState(viewModel)
        advanceUntilIdle()

        val state = viewModel.uiState.value

        assertEquals(totals.incomePaise, state.incomeInPaise)
        assertEquals(totals.expensePaise, state.expenseInPaise)
        // Balance is the ledger's own net, so a month that spent more than it earned reads negative.
        assertEquals(totals.netPaise, state.balanceInPaise)
    }

    @Test
    fun theSectionStartsOnASpinnerRatherThanClaimingNothingIsDue() {
        val viewModel = Fixture().viewModel()

        assertEquals(HomeRecurringUiState.Loading, viewModel.uiState.value.recurring)
    }

    // ------------------------------------------------------------------ the three readings

    @Test
    fun theOutstandingDatesAreSplitIntoOverdueDueTodayAndUpcoming() = runTest {
        val viewModel = Fixture(
            occurrences = listOf(
                occurrence(id = 1L, dueDate = TODAY.minusDays(3)),
                occurrence(id = 2L, dueDate = TODAY),
                occurrence(id = 3L, dueDate = TODAY.plusDays(5)),
            ),
        ).viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        val section = viewModel.ready()

        assertEquals(listOf(1L), section.overdue.map { it.occurrenceId })
        assertEquals(listOf(2L), section.dueToday.map { it.occurrenceId })
        assertEquals(listOf(3L), section.upcoming.map { it.occurrenceId })
        assertEquals(OccurrenceDisplayState.OVERDUE, section.overdue.single().state)
        assertEquals(OccurrenceDisplayState.DUE, section.dueToday.single().state)
        assertEquals(OccurrenceDisplayState.UPCOMING, section.upcoming.single().state)
    }

    @Test
    fun theDayBeforeTodayIsOverdueAndTodayItselfIsNot() = runTest {
        val viewModel = Fixture(
            occurrences = listOf(
                occurrence(id = 1L, dueDate = TODAY.minusDays(1)),
                occurrence(id = 2L, dueDate = TODAY),
                occurrence(id = 3L, dueDate = TODAY.plusDays(1)),
            ),
        ).viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        val section = viewModel.ready()

        assertEquals(listOf(1L), section.overdue.map { it.occurrenceId })
        assertEquals(listOf(2L), section.dueToday.map { it.occurrenceId })
        assertEquals(listOf(3L), section.upcoming.map { it.occurrenceId })
    }

    @Test
    fun eachRowSaysHowFarFromTodayTheDateIs() = runTest {
        val viewModel = Fixture(
            occurrences = listOf(
                occurrence(id = 1L, dueDate = TODAY.minusDays(3)),
                occurrence(id = 2L, dueDate = TODAY),
                occurrence(id = 3L, dueDate = TODAY.plusDays(5)),
            ),
        ).viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        val section = viewModel.ready()

        assertEquals(-3L, section.overdue.single().daysFromToday)
        assertEquals(0L, section.dueToday.single().daysFromToday)
        assertEquals(5L, section.upcoming.single().daysFromToday)
    }

    @Test
    fun aRowCarriesTheDatesOwnAmountRatherThanTheSchedulesCurrentOne() = runTest {
        val viewModel = Fixture(
            schedules = listOf(schedule(amountPaise = 1_500_000L)),
            occurrences = listOf(occurrence(id = 1L, dueDate = TODAY, amountPaise = 1_450_000L)),
        ).viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        // An edit to the schedule states what happens next time; it must not rewrite what the date
        // already on screen was expected to be.
        assertEquals(1_450_000L, viewModel.ready().dueToday.single().amountPaise)
    }

    @Test
    fun aScheduleWithSeveralWaitingDatesShowsOnlyItsSoonestDate() = runTest {
        val viewModel = Fixture(
            occurrences = listOf(
                occurrence(id = 1L, dueDate = TODAY.plusDays(5)),
                occurrence(id = 2L, dueDate = TODAY.plusDays(35)),
                occurrence(id = 3L, dueDate = TODAY.plusDays(65)),
            ),
        ).viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        val section = viewModel.ready()

        // One category, one row: the soonest date is the one worth showing, and the later dates are
        // still on the recurring tab rather than crowding the dashboard with the same bill.
        assertEquals(listOf(1L), section.upcoming.map { it.occurrenceId })
        assertEquals(1, section.upcomingTotal)
    }

    @Test
    fun oneUpcomingDateIsShownForEachCategoryAndTheSoonestDateWins() = runTest {
        val viewModel = Fixture(
            schedules = listOf(
                schedule(id = 1L, name = "Milk", categoryId = FOOD_ID),
                schedule(id = 2L, name = "Curd", categoryId = FOOD_ID),
                schedule(id = 3L, name = "Salary", categoryId = SALARY_ID),
            ),
            occurrences = listOf(
                occurrence(id = 1L, scheduleId = 1L, dueDate = TODAY.plusDays(5)),
                occurrence(id = 2L, scheduleId = 2L, dueDate = TODAY.plusDays(2)),
                occurrence(id = 3L, scheduleId = 1L, dueDate = TODAY.plusDays(10)),
                occurrence(id = 4L, scheduleId = 3L, dueDate = TODAY.plusDays(7)),
            ),
        ).viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        val section = viewModel.ready()

        // Two categories, so two rows; the sooner of the two food dates represents food.
        assertEquals(listOf(2L, 4L), section.upcoming.map { it.occurrenceId })
        assertEquals(setOf(FOOD_ID, SALARY_ID), section.upcoming.map { it.categoryId }.toSet())
        assertEquals(2, section.upcomingTotal)
    }

    @Test
    fun theSectionDrawsTheDatesInTheOrderItReadThem() = runTest {
        val viewModel = Fixture(
            schedules = listOf(
                schedule(id = 1L, name = "Rent", categoryId = RENT_ID),
                schedule(id = 2L, name = "Salary", categoryId = SALARY_ID),
                schedule(id = 3L, name = "Utilities", categoryId = UTILITIES_ID),
            ),
            occurrences = listOf(
                occurrence(id = 3L, scheduleId = 3L, dueDate = TODAY.plusDays(30)),
                occurrence(id = 1L, scheduleId = 1L, dueDate = TODAY.plusDays(2)),
                occurrence(id = 2L, scheduleId = 2L, dueDate = TODAY.plusDays(10)),
            ),
        ).viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        // Soonest first, whoever wrote the rows in whatever order: the date that wants attention
        // soonest is drawn first. The statement's own `ORDER BY` is asserted against Room in
        // `RecurringDaoTest`.
        assertEquals(listOf(1L, 2L, 3L), viewModel.ready().upcoming.map { it.occurrenceId })
    }

    @Test
    fun anIncomeSchedulesRowKnowsItIsIncome() = runTest {
        val viewModel = Fixture(
            schedules = listOf(schedule(categoryId = SALARY_ID, name = "Salary")),
            occurrences = listOf(occurrence(id = 1L, dueDate = TODAY.plusDays(3))),
        ).viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        val row = viewModel.ready().upcoming.single()

        // The direction is the category's, never a property of the row: the same value the payment
        // would be filed under.
        assertEquals(TransactionType.INCOME, row.categoryType)
        assertEquals("Salary", row.categoryName)
    }

    // ------------------------------------------------------------------ the preview is not a filter

    @Test
    fun theUpcomingPreviewShowsAtMostFiveCategoriesWhileTheHeadingCountsThemAll() = runTest {
        val categories = (1L..8L).map { index ->
            CategoryEntity.create("Category $index", TransactionType.EXPENSE)
                .copy(id = 100L + index)
        }
        val schedules = (1L..8L).map { index ->
            schedule(id = index, name = "Bill $index", categoryId = 100L + index)
        }
        val occurrences = (1L..8L).map { index ->
            occurrence(id = index, scheduleId = index, dueDate = TODAY.plusDays(index))
        }

        val viewModel = Fixture(
            schedules = schedules,
            occurrences = occurrences,
            categories = categories,
        ).viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        val section = viewModel.ready()

        assertEquals((1L..5L).toList(), section.upcoming.map { it.occurrenceId })
        assertEquals(8, section.upcomingTotal)
    }

    @Test
    fun overdueAndDueTodayDatesAreNeverTrimmed() = runTest {
        val viewModel = Fixture(
            occurrences = (1L..7L).map { index ->
                occurrence(id = index, dueDate = TODAY.minusDays(index))
            } + (8L..12L).map { index ->
                occurrence(id = index, dueDate = TODAY)
            },
        ).viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        // These are the dates that want doing something about, so a preview must not decide for the
        // household which of their own bills matters.
        val section = viewModel.ready()

        assertEquals(7, section.overdue.size)
        assertEquals(5, section.dueToday.size)
    }

    // ------------------------------------------------------------------ what is never offered as work

    @Test
    fun aPaidDateIsNotOfferedAsWaiting() = runTest {
        val fixture = Fixture(
            occurrences = listOf(
                occurrence(id = 1L, dueDate = TODAY.minusDays(2)),
                occurrence(
                    id = 2L,
                    dueDate = TODAY.minusDays(1),
                    status = RecurringOccurrenceStatus.PAID,
                ),
            ),
        )
        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        val section = viewModel.ready()

        assertEquals(listOf(1L), section.overdue.map { it.occurrenceId })
        // The row is still there, it is simply finished with - and it is not counted a second time.
        assertEquals(2, fixture.recurringDao.storedOccurrences.size)
    }

    @Test
    fun aSkippedDateIsNotOfferedAsWaiting() = runTest {
        val viewModel = Fixture(
            occurrences = listOf(
                occurrence(
                    id = 1L,
                    dueDate = TODAY.plusDays(4),
                    status = RecurringOccurrenceStatus.SKIPPED,
                ),
            ),
        ).viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        val section = viewModel.ready()

        assertTrue(section.overdue.isEmpty())
        assertTrue(section.dueToday.isEmpty())
        assertTrue(section.upcoming.isEmpty())
    }

    @Test
    fun aPausedSchedulesWaitingDateIsStillShownBecausePausingDidNotCancelIt() = runTest {
        val viewModel = Fixture(
            schedules = listOf(schedule(status = RecurringScheduleStatus.PAUSED)),
            occurrences = listOf(occurrence(id = 1L, dueDate = TODAY.minusDays(4))),
        ).viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        val section = viewModel.ready()

        // Pausing stops the engine making *more* dates; it does not delete the one that was already
        // asked for, and the app goes on offering to record it. A dashboard that went quiet about a bill
        // the household still owes would be the one place the two screens disagreed.
        assertEquals(listOf(1L), section.overdue.map { it.occurrenceId })
        // The row can say why it is there, because the schedule's own state travels with it...
        assertEquals(RecurringScheduleStatus.PAUSED, section.overdue.single().scheduleStatus)
        // ... and the household is certainly not told they never set anything up.
        assertFalse(section.isFreshHousehold)
    }

    @Test
    fun aStoppedSchedulesLastBillIsStillShown() = runTest {
        val viewModel = Fixture(
            schedules = listOf(schedule(status = RecurringScheduleStatus.RETIRED)),
            occurrences = listOf(occurrence(id = 1L, dueDate = TODAY.plusDays(2))),
        ).viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        val section = viewModel.ready()

        // A household who stopped a subscription may still owe the final bill - the details screen says
        // so in as many words - so the last date it generated stays visible until it is paid or skipped.
        assertEquals(listOf(1L), section.upcoming.map { it.occurrenceId })
        assertEquals(RecurringScheduleStatus.RETIRED, section.upcoming.single().scheduleStatus)
        assertFalse(section.isFreshHousehold)
    }

    @Test
    fun anActiveSchedulesRowSaysNothingAboutItsState() = runTest {
        val viewModel = Fixture(occurrences = listOf(occurrence(id = 1L, dueDate = TODAY))).viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        // The ordinary case carries the ordinary state, so the row has nothing to explain.
        assertEquals(
            RecurringScheduleStatus.ACTIVE,
            viewModel.ready().dueToday.single().scheduleStatus,
        )
    }

    // ------------------------------------------------------------------ empty states

    @Test
    fun aHouseholdWithNoSchedulesAtAllIsInvitedToAddOne() = runTest {
        val viewModel = Fixture(schedules = emptyList()).viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        val section = viewModel.ready()

        assertTrue(section.isFreshHousehold)
        assertTrue(section.overdue.isEmpty())
        assertTrue(section.dueToday.isEmpty())
        assertTrue(section.upcoming.isEmpty())
        assertEquals(0, section.upcomingTotal)
    }

    @Test
    fun aHouseholdWhoseSchedulesAreAllSettledReadsNothingWaitingRatherThanAFreshInstall() = runTest {
        val viewModel = Fixture(
            schedules = listOf(schedule()),
            occurrences = listOf(
                occurrence(id = 1L, dueDate = TODAY.minusDays(9), status = RecurringOccurrenceStatus.PAID),
            ),
        ).viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        val section = viewModel.ready()

        // They have schedules; telling them they have none would be a lie about their own records.
        assertFalse(section.isFreshHousehold)
        assertTrue(section.overdue.isEmpty())
        assertTrue(section.dueToday.isEmpty())
        assertTrue(section.upcoming.isEmpty())
    }

    // ------------------------------------------------------------------ expectations are not totals

    @Test
    fun expectedMoneyIsNeverAddedToTheFiguresTheHouseholdReads() = runTest {
        val totals = TransactionTotals(
            incomePaise = 4_850_000L,
            expensePaise = 2_734_050L,
            incomeCount = 2L,
            expenseCount = 6L,
        )
        val viewModel = Fixture(
            occurrences = listOf(
                occurrence(id = 1L, dueDate = TODAY.minusDays(30), amountPaise = 900_000L),
                occurrence(id = 2L, dueDate = TODAY, amountPaise = 1_500_000L),
                occurrence(id = 3L, dueDate = TODAY.plusDays(10), amountPaise = 800_000L),
            ),
        ).viewModel(monthlyTotals = flowOf(totals))

        collectUiState(viewModel)
        advanceUntilIdle()

        // Three dates, ₹32,000 of expectation, and not one paisa of it has moved.
        val state = viewModel.uiState.value

        assertEquals(totals.netPaise, state.balanceInPaise)
        assertEquals(totals.incomePaise, state.incomeInPaise)
        assertEquals(totals.expensePaise, state.expenseInPaise)
    }

    // ------------------------------------------------------------------ the engine run

    @Test
    fun openingHomeBringsTheDueDatesUpToDate() = runTest {
        val fixture = Fixture(
            // A daily schedule generated only up to yesterday, so the day that arrived overnight has
            // no row until this screen is opened.
            schedules = listOf(
                schedule(
                    startDate = TODAY.minusDays(30),
                    frequency = RecurrenceFrequency.DAILY,
                    generatedThrough = TODAY.minusDays(1),
                ),
            ),
        )
        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        val section = viewModel.ready()

        // The run happened, so today's date - which nobody was looking when it arrived - is on screen,
        // and the dates behind it are drawn beside it.
        assertEquals(listOf(TODAY), section.dueToday.map { it.dueDate })
        assertTrue(section.upcoming.isNotEmpty())
        assertTrue(fixture.recurringDao.insertedOccurrenceBatches.isNotEmpty())
    }

    @Test
    fun aRunThatOnlyFillsInDatesWritesNoLedgerEntry() = runTest {
        val fixture = Fixture(
            schedules = listOf(schedule(generatedThrough = TODAY.minusDays(1))),
        )
        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        assertTrue(fixture.recurringDao.insertedOccurrenceBatches.isNotEmpty())
        // A bill falling due is not a payment. Nothing was spent, so nothing was recorded.
        assertTrue(fixture.transactionDao.inserted.isEmpty())
    }

    @Test
    fun aSecondRunWritesNothingAtAll() = runTest {
        val fixture = Fixture(
            schedules = listOf(
                schedule(
                    startDate = TODAY.minusDays(30),
                    frequency = RecurrenceFrequency.DAILY,
                    generatedThrough = TODAY.minusDays(1),
                ),
            ),
        )
        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        val afterFirst = fixture.recurringDao.storedOccurrences.size

        viewModel.retry()
        advanceUntilIdle()

        // Idempotent: the dates already exist, and re-running is how the household gets from a
        // failure back to a correct screen, so it must be free of consequences.
        assertEquals(afterFirst, fixture.recurringDao.storedOccurrences.size)
    }

    @Test
    fun aPausedOrStoppedScheduleIsNotGeneratedForByOpeningHome() = runTest {
        val fixture = Fixture(
            schedules = listOf(
                schedule(
                    id = 1L,
                    name = "Paused",
                    generatedThrough = TODAY.minusDays(1),
                    status = RecurringScheduleStatus.PAUSED,
                ),
                schedule(
                    id = 2L,
                    name = "Stopped",
                    generatedThrough = TODAY.minusDays(1),
                    status = RecurringScheduleStatus.RETIRED,
                ),
            ),
        )
        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        // The one thing a switch that was turned off *does* do: the engine makes nothing new for either
        // of these. Their already-generated dates are a different matter - they stay payable, and the
        // tests above hold the section to showing them.
        assertTrue(fixture.recurringDao.storedOccurrences.isEmpty())
    }

    // ------------------------------------------------------------------ failing

    @Test
    fun aFailedReadBecomesASentenceAndNotAnEmptySection() = runTest {
        val fixture = Fixture(occurrences = listOf(occurrence(id = 1L, dueDate = TODAY)))
        fixture.recurringDao.readFailure = IOException("the database could not be read")

        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        val state = viewModel.uiState.value.recurring

        assertTrue("expected a failure, was $state", state is HomeRecurringUiState.Failed)
        assertEquals(
            "the database could not be read",
            (state as HomeRecurringUiState.Failed).cause.message,
        )
    }

    @Test
    fun aFailedRunIsReportedBesideTheRowsRatherThanInsteadOfThem() = runTest {
        val fixture = Fixture(
            // Something for the run to try to write, so the failure is a *run* failing rather than a
            // run with nothing to do.
            schedules = listOf(schedule(generatedThrough = TODAY.minusDays(1))),
            occurrences = listOf(occurrence(id = 1L, dueDate = TODAY)),
        )
        fixture.recurringDao.writeFailure = IOException("the write failed")

        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        val section = viewModel.ready()

        // What is on screen is still true - it may simply be missing dates the run would have
        // written - so the rows stay and the run reports itself alongside them.
        assertTrue(section.generationFailed)
        assertEquals(listOf(1L), section.dueToday.map { it.occurrenceId })
    }

    @Test
    fun aSuccessfulRunClearsAnEarlierFailure() = runTest {
        val fixture = Fixture(
            schedules = listOf(schedule(generatedThrough = TODAY.minusDays(1))),
            occurrences = listOf(occurrence(id = 1L, dueDate = TODAY)),
        )
        fixture.recurringDao.writeFailure = IOException("the write failed")

        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        assertTrue(viewModel.ready().generationFailed)

        fixture.recurringDao.writeFailure = null
        viewModel.retry()
        advanceUntilIdle()

        assertFalse(viewModel.ready().generationFailed)
    }

    @Test
    fun retryReadsAgainAfterAFailure() = runTest {
        val fixture = Fixture(occurrences = listOf(occurrence(id = 1L, dueDate = TODAY)))
        fixture.recurringDao.readFailure = IOException("the database could not be read")

        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.recurring is HomeRecurringUiState.Failed)

        fixture.recurringDao.readFailure = null
        viewModel.retry()
        advanceUntilIdle()

        assertEquals(listOf(1L), viewModel.ready().dueToday.map { it.occurrenceId })
    }

    // ------------------------------------------------------------------ fixtures

    /** Starts collecting the state the way the screen does, so the shared flow actually runs. */
    private fun TestScope.collectUiState(viewModel: HomeViewModel) {
        backgroundScope.launch { viewModel.uiState.collect { } }
    }    /** Asserts the section is [HomeRecurringUiState.Ready] and returns it. */
    private fun HomeViewModel.ready(): HomeRecurringUiState.Ready {
        val state = uiState.value.recurring
        assertTrue("expected the section to have loaded, was $state", state is HomeRecurringUiState.Ready)
        return state as HomeRecurringUiState.Ready
    }

    private fun Fixture.viewModel(
        showBalance: Flow<Boolean> = flowOf(true),
        monthlyTotals: Flow<TransactionTotals> = flowOf(TransactionTotals.None),
    ): HomeViewModel =
        HomeViewModel(
            recurring = repository,
            today = TODAY,
            showBalance = showBalance,
            monthlyTotals = monthlyTotals,
        ).also { viewModelStore.put("home", it) }

    // ------------------------------------------------------------------ the balance preference

    @Test
    fun theBalanceIsShownUnlessTheHouseholdTurnsItOff() = runTest {
        val viewModel = Fixture().viewModel()
        collectUiState(viewModel)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showBalance)
    }

    @Test
    fun turningTheBalanceOffHidesTheAmountAndKeepsEveryOtherFigure() = runTest {
        val totals = TransactionTotals(
            incomePaise = 4_850_000L,
            expensePaise = 2_734_050L,
            incomeCount = 2L,
            expenseCount = 6L,
        )
        val viewModel = Fixture().viewModel(
            showBalance = flowOf(false),
            monthlyTotals = flowOf(totals),
        )
        collectUiState(viewModel)
        advanceUntilIdle()

        val state = viewModel.uiState.value

        assertFalse(state.showBalance)
        // The figure is still read and kept: the preference is about drawing it, not about losing it.
        assertEquals(totals.netPaise, state.balanceInPaise)
        assertEquals(totals.incomePaise, state.incomeInPaise)
        assertEquals(totals.expensePaise, state.expenseInPaise)
        assertTrue(state.recurring is HomeRecurringUiState.Ready)
    }

    @Test
    fun aPreferenceReadThatFailsShowsTheBalanceRatherThanHidingIt() = runTest {
        val viewModel = Fixture().viewModel(
            showBalance = kotlinx.coroutines.flow.flow { throw IllegalStateException("no store") },
        )
        collectUiState(viewModel)
        advanceUntilIdle()

        // A broken settings row is not a privacy mode: the dashboard still works, with its balance.
        assertTrue(viewModel.uiState.value.showBalance)
        assertTrue(viewModel.uiState.value.recurring is HomeRecurringUiState.Ready)
    }

    /**
     * The whole data layer over the in-memory fakes, wired the way `HomeFinanceApp` wires the real one,
     * so a test exercises the same graph production does.
     */
    private class Fixture(
        schedules: List<RecurringScheduleEntity> = listOf(DEFAULT_SCHEDULE),
        occurrences: List<RecurringOccurrenceEntity> = emptyList(),
        categories: List<CategoryEntity> = DEFAULT_CATEGORIES,
    ) {
        private val names: Map<Long, String> = categories.associate { it.id to it.name }

        private val types: Map<Long, TransactionType> = categories.associate { it.id to it.type }

        private val iconKeys: Map<Long, String> = categories
            .mapNotNull { category -> category.iconKey?.let { category.id to it } }
            .toMap()

        private val categoryDao = FakeCategoryDao(categories)

        val transactionDao = FakeTransactionDao(categoryNames = names, categoryIconKeys = iconKeys)

        val recurringDao = FakeRecurringDao(
            categoryNames = names,
            initialSchedules = schedules,
            initialOccurrences = occurrences,
            categoryIconKeys = iconKeys,
            categoryTypes = types,
        )

        val repository = RecurringRepository(
            recurringDao = recurringDao,
            transactions = TransactionRepository(transactionDao, categoryDao),
            categories = CategoryRepository(categoryDao),
            generator = RecurrenceGenerator(recurringDao, DirectTransactionRunner),
            transactionRunner = DirectTransactionRunner,
        )
    }

    private companion object {

        const val SCHEDULE_ID = 1L
        const val RENT_ID = 10L
        const val SALARY_ID = 11L
        const val UTILITIES_ID = 12L
        const val FOOD_ID = 13L

        val TODAY: LocalDate = LocalDate.of(2026, 10, 20)

        val DEFAULT_CATEGORIES: List<CategoryEntity> = listOf(
            CategoryEntity.create("Rent", TransactionType.EXPENSE).copy(id = RENT_ID),
            CategoryEntity.create("Salary", TransactionType.INCOME).copy(id = SALARY_ID),
            CategoryEntity.create("Utilities", TransactionType.EXPENSE).copy(id = UTILITIES_ID),
            CategoryEntity.create("Food", TransactionType.EXPENSE).copy(id = FOOD_ID),
        )

        /**
         * A monthly schedule that has already been generated well past the planning horizon, so a test
         * which is not about the engine run is not disturbed by one.
         *
         * The direction of a row is not a field here: it is whatever the category it is filed under
         * says, which is the whole reason an income schedule needs no column of its own.
         */
        fun schedule(
            id: Long = SCHEDULE_ID,
            name: String = "Shop rent",
            status: RecurringScheduleStatus = RecurringScheduleStatus.ACTIVE,
            startDate: LocalDate = LocalDate.of(2026, 10, 5),
            frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
            generatedThrough: LocalDate? = TODAY.plusDays(RecurrenceGenerator.PLANNING_HORIZON_DAYS),
            categoryId: Long = RENT_ID,
            amountPaise: Long = 1_500_000L,
        ): RecurringScheduleEntity = RecurringScheduleEntity(
            id = id,
            name = name,
            description = null,
            categoryId = categoryId,
            amountPaise = amountPaise,
            unit = null,
            ratePaise = null,
            frequency = frequency,
            intervalCount = 1,
            startDate = startDate,
            endDate = null,
            status = status,
            generatedThrough = generatedThrough,
            createdAt = Instant.parse("2026-10-01T04:30:00Z"),
            updatedAt = Instant.parse("2026-10-01T04:30:00Z"),
        )

        /**
         * The schedule the fixture holds unless a test says otherwise.
         *
         * A schedule is present by default because the outstanding read joins on one: an occurrence
         * whose schedule is missing is dropped by that join, exactly as the real `INNER JOIN` drops it,
         * and a test that quietly had no schedule would be asserting on an empty section for the wrong
         * reason.
         */
        val DEFAULT_SCHEDULE: RecurringScheduleEntity = schedule()

        fun occurrence(
            id: Long,
            dueDate: LocalDate,
            scheduleId: Long = SCHEDULE_ID,
            amountPaise: Long = 1_500_000L,
            status: RecurringOccurrenceStatus = RecurringOccurrenceStatus.PENDING,
        ): RecurringOccurrenceEntity = RecurringOccurrenceEntity(
            id = id,
            scheduleId = scheduleId,
            dueDate = dueDate,
            amountPaise = amountPaise,
            status = status,
            transactionId = null,
            createdAt = Instant.parse("2026-10-01T04:30:00Z"),
            updatedAt = Instant.parse("2026-10-01T04:30:00Z"),
        )
    }
}
