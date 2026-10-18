package com.homefinance.app.feature.recurring

import androidx.lifecycle.ViewModelStore
import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.data.local.entity.RecurringOccurrenceEntity
import com.homefinance.app.data.local.entity.RecurringScheduleEntity
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
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * One schedule's details screen, driven with fake DAOs behind the real repository.
 *
 * What is pinned here is the **folding**: which of a schedule's occurrences is offered as the one to
 * act on, how much of the history the short list shows, and - the distinction the screen is built
 * around - that a schedule that cannot be read is never drawn as a schedule that has gone. The settle
 * flows themselves live in `OccurrenceActionsControllerTest`, and the rules live in the repository and
 * are asserted against a real database; this file proves the screen reaches them and reacts to what
 * they say.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecurringDetailViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    /**
     * Holds the view model so [ViewModelStore.clear] runs its `onCleared`, which is what stops the
     * `WhileSubscribed` sharing coroutine outliving the test.
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

    @Test
    fun theScreenStartsOnASpinnerRatherThanClaimingTheScheduleHasGone() = runTest {
        val viewModel = Fixture(schedules = listOf(schedule())).viewModel()

        assertEquals(RecurringDetailUiState.Loading, viewModel.uiState.value)
    }

    @Test
    fun theSchedulesOwnFiguresReachTheScreen() = runTest {
        val viewModel = Fixture(schedules = listOf(schedule())).viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        val card = viewModel.content().schedule

        assertEquals("Shop rent", card.name)
        assertEquals("Ground floor, paid by UPI", card.description)
        assertEquals("Rent", card.categoryName)
        assertEquals(1_500_000L, card.amountPaise)
        assertEquals(RecurrenceFrequency.MONTHLY, card.frequency)
        assertEquals(1, card.intervalCount)
        assertEquals(RecurringScheduleStatus.ACTIVE, card.status)
        assertEquals(LocalDate.of(2026, 10, 5), card.startDate)
        assertEquals(LocalDate.of(2027, 3, 5), card.endDate)
        assertEquals(SEEDED_AT, card.addedAt)
    }

    @Test
    fun aScheduleThatHasGoneIsNotFoundRatherThanAnEmptyFailure() = runTest {
        val viewModel = Fixture().viewModel(scheduleId = 4_242L)

        collectUiState(viewModel)
        advanceUntilIdle()

        assertEquals(RecurringDetailUiState.NotFound, viewModel.uiState.value)
    }

    @Test
    fun aFailedReadIsAFailureRatherThanAHouseholdWithNoSchedule() = runTest {
        val fixture = Fixture(schedules = listOf(schedule()))
        fixture.recurringDao.readFailure = IOException("the database could not be read")

        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("expected a failed read, was $state", state is RecurringDetailUiState.Failed)
        assertEquals(
            "the database could not be read",
            (state as RecurringDetailUiState.Failed).cause.message,
        )
    }

    @Test
    fun retryReReadsTheScheduleAfterAFailure() = runTest {
        val fixture = Fixture(schedules = listOf(schedule()))
        fixture.recurringDao.readFailure = IOException("the database could not be read")

        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is RecurringDetailUiState.Failed)

        fixture.recurringDao.readFailure = null
        viewModel.retry()
        advanceUntilIdle()

        assertEquals("Shop rent", viewModel.content().schedule.name)
    }

    // -------------------------------------------------------------- the outstanding one

    @Test
    fun theOldestStillPendingDateIsTheOneOfferedToActOn() = runTest {
        val viewModel = Fixture(
            schedules = listOf(schedule()),
            occurrences = listOf(
                occurrence(id = 1L, dueDate = LocalDate.of(2026, 8, 5), status = RecurringOccurrenceStatus.PAID),
                occurrence(id = 2L, dueDate = LocalDate.of(2026, 9, 5)),
                occurrence(id = 3L, dueDate = LocalDate.of(2026, 10, 5)),
                occurrence(id = 4L, dueDate = LocalDate.of(2026, 11, 5)),
            ),
        ).viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        val outstanding = viewModel.content().outstanding

        // The earliest one still pending, not the earliest of all: a household opening a schedule wants
        // the next thing they can actually do something about.
        assertEquals(2L, outstanding?.id)
        assertEquals(LocalDate.of(2026, 9, 5), outstanding?.dueDate)
        // And against today, that is overdue rather than a date in the future.
        assertEquals(OccurrenceDisplayState.OVERDUE, outstanding?.state)
    }

    @Test
    fun aDueTodayDateIsOfferedAsDueRatherThanOverdue() = runTest {
        val viewModel = Fixture(
            schedules = listOf(schedule()),
            occurrences = listOf(occurrence(id = 3L, dueDate = TODAY)),
        ).viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        assertEquals(OccurrenceDisplayState.DUE, viewModel.content().outstanding?.state)
    }

    @Test
    fun aScheduleWithNothingLeftToSettleOffersNothing() = runTest {
        val viewModel = Fixture(
            schedules = listOf(schedule()),
            occurrences = listOf(
                occurrence(id = 1L, dueDate = LocalDate.of(2026, 8, 5), status = RecurringOccurrenceStatus.PAID),
                occurrence(id = 2L, dueDate = LocalDate.of(2026, 9, 5), status = RecurringOccurrenceStatus.SKIPPED),
            ),
        ).viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        // Paid and skipped are both final. Offering the most recent settled date as "the one waiting"
        // would invite a second payment for a bill already dealt with.
        assertNull(viewModel.content().outstanding)
    }

    @Test
    fun theShortHistoryShowsTheNewestFewAndSaysHowManyThereAreAltogether() = runTest {
        val viewModel = Fixture(
            schedules = listOf(schedule()),
            occurrences = (1L..8L).map { id ->
                occurrence(
                    id = id,
                    dueDate = LocalDate.of(2026, 1, 5).plusMonths(id - 1),
                    status = RecurringOccurrenceStatus.PAID,
                )
            },
        ).viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        val content = viewModel.content()

        // The DAO answers newest first, so the short list is the first five of that.
        assertEquals(5, content.recentOccurrences.size)
        assertEquals(
            listOf(8L, 7L, 6L, 5L, 4L),
            content.recentOccurrences.map { it.id },
        )
        // The count is what lets the button at the foot say whether the full list is worth opening.
        assertEquals(8, content.occurrenceCount)
    }

    @Test
    fun anExpenseDeletedFromTheLedgerIsStillDrawnAsPaidAndSaysTheEntryIsGone() = runTest {
        val viewModel = Fixture(
            schedules = listOf(schedule()),
            occurrences = listOf(
                occurrence(
                    id = 1L,
                    dueDate = LocalDate.of(2026, 9, 5),
                    status = RecurringOccurrenceStatus.PAID,
                    // `facts.transaction_id` came back null: the linked expense is no longer recorded.
                    transactionId = null,
                ),
            ),
        ).viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        val item = viewModel.content().recentOccurrences.single()

        // The occurrence really was paid, so it still says so; rewriting it to skipped would be editing
        // the past to match the present.
        assertEquals(OccurrenceDisplayState.PAID, item.state)
        assertTrue(item.transactionRemoved)
    }

    // ------------------------------------------------------------------ the actions

    @Test
    fun pausingStopsTheRuleAndKeepsEverythingAlreadyGenerated() = runTest {
        val fixture = Fixture(
            schedules = listOf(schedule()),
            occurrences = listOf(occurrence(id = 1L, dueDate = LocalDate.of(2026, 10, 5))),
        )
        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        viewModel.pause()
        advanceUntilIdle()

        assertEquals(RecurringScheduleStatus.PAUSED, fixture.storedSchedule().status)
        // A pause is not a deletion: the date already generated is still there to be paid or skipped.
        assertEquals(1, fixture.recurringDao.storedOccurrences.size)
        assertNull(viewModel.content().actionError)
        assertTrue(fixture.transactionDao.inserted.isEmpty())
    }

    @Test
    fun resumingStartsFromTodayRatherThanBackFillingThePausedWindow() = runTest {
        val fixture = Fixture(
            schedules = listOf(
                schedule(
                    status = RecurringScheduleStatus.PAUSED,
                    startDate = LocalDate.of(2026, 10, 5),
                    generatedThrough = LocalDate.of(2026, 10, 4),
                ),
            ),
        )
        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        viewModel.resume()
        advanceUntilIdle()

        assertEquals(RecurringScheduleStatus.ACTIVE, fixture.storedSchedule().status)

        // The date that fell inside the pause is not conjured back as a sudden overdue bill.
        val dueDates = fixture.recurringDao.storedOccurrences.map { it.dueDate }
        assertTrue(LocalDate.of(2026, 10, 5) !in dueDates)
        assertEquals(LocalDate.of(2026, 11, 5), dueDates.minOrNull())

        // And yet again: nothing about a rule changing moves a total.
        assertTrue(fixture.transactionDao.inserted.isEmpty())
    }

    @Test
    fun stoppingTheRuleForGoodLeavesTheScheduleAndItsHistoryInPlace() = runTest {
        val fixture = Fixture(
            schedules = listOf(schedule()),
            occurrences = listOf(occurrence(id = 1L, dueDate = LocalDate.of(2026, 10, 5))),
        )
        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        viewModel.retire()
        advanceUntilIdle()

        assertEquals(RecurringScheduleStatus.RETIRED, fixture.storedSchedule().status)
        assertEquals(1, fixture.recurringDao.storedOccurrences.size)
        assertNull(viewModel.content().actionError)
    }

    @Test
    fun aFailedResumeIsSaidBesideFiguresThatAreStillTrue() = runTest {
        val fixture = Fixture(
            schedules = listOf(
                schedule(
                    status = RecurringScheduleStatus.PAUSED,
                    startDate = LocalDate.of(2026, 10, 5),
                    generatedThrough = LocalDate.of(2026, 10, 4),
                ),
            ),
        )
        fixture.recurringDao.writeFailure = IOException("the disk filled up")

        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        viewModel.resume()
        advanceUntilIdle()

        val content = viewModel.content()

        // The schedule is still on screen: reporting a failed action by throwing the household's own data
        // away would lose more than the failure was worth.
        assertTrue(content.actionError is ActionError.Failed)
        assertEquals("Shop rent", content.schedule.name)
    }

    @Test
    fun openingThePaymentDialogWritesNothingAtAll() = runTest {
        val fixture = Fixture(
            schedules = listOf(schedule()),
            occurrences = listOf(occurrence(id = 7L, dueDate = LocalDate.of(2026, 10, 5))),
        )
        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        viewModel.requestPay(checkNotNull(viewModel.content().outstanding))
        advanceUntilIdle()

        val pay = checkNotNull(viewModel.pay.value)
        assertEquals(7L, pay.occurrenceId)
        assertEquals("Shop rent", pay.scheduleName)
        assertEquals("Rent", pay.categoryName)
        assertEquals(LocalDate.of(2026, 10, 5), pay.dueDate)
        assertEquals(1_500_000L, pay.expectedAmountPaise)
        // Pre-filled from what the schedule expected, editable, and nothing written by looking.
        assertEquals("15000", pay.amountInput)
        assertTrue(fixture.transactionDao.inserted.isEmpty())
    }

    @Test
    fun confirmingAPaymentRecordsExactlyOneExpenseAndClosesTheDialog() = runTest {
        val fixture = Fixture(
            schedules = listOf(schedule()),
            occurrences = listOf(occurrence(id = 7L, dueDate = LocalDate.of(2026, 10, 5))),
        )
        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        viewModel.requestPay(checkNotNull(viewModel.content().outstanding))
        viewModel.confirmPay()
        advanceUntilIdle()

        val expense = fixture.transactionDao.inserted.single()

        assertEquals(1_500_000L, expense.amountPaise)
        assertEquals(TransactionType.EXPENSE, expense.type)
        assertEquals(LocalDate.of(2026, 10, 5), expense.transactionDate)
        assertEquals("Shop rent", expense.description)

        // Success needs no callback: the rows are database flows, so the screen has already been told.
        assertNull(viewModel.pay.value)
        assertEquals(RecurringOccurrenceStatus.PAID, fixture.recurringDao.storedOccurrences.single().status)
    }

    @Test
    fun skippingRecordsNoExpenseAndClosesTheConfirmation() = runTest {
        val fixture = Fixture(
            schedules = listOf(schedule()),
            occurrences = listOf(occurrence(id = 7L, dueDate = LocalDate.of(2026, 10, 5))),
        )
        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        viewModel.requestSkip(checkNotNull(viewModel.content().outstanding))
        viewModel.confirmSkip()
        advanceUntilIdle()

        assertNull(viewModel.skip.value)
        assertTrue(fixture.transactionDao.inserted.isEmpty())
        // Kept, so it is never generated again - but not paid, and no total moved.
        assertEquals(RecurringOccurrenceStatus.SKIPPED, fixture.recurringDao.storedOccurrences.single().status)
    }

    // ------------------------------------------------------------------ fixtures

    /** Starts collecting the state the way the screen does, so the shared flow actually runs. */
    private fun TestScope.collectUiState(viewModel: RecurringDetailViewModel) {
        backgroundScope.launch { viewModel.uiState.collect { } }
    }

    /** Asserts the state is [RecurringDetailUiState.Content] and returns it. */
    private fun RecurringDetailViewModel.content(): RecurringDetailUiState.Content {
        val state = uiState.value
        assertTrue("expected the schedule to have loaded, was $state", state is RecurringDetailUiState.Content)
        return state as RecurringDetailUiState.Content
    }

    private fun Fixture.viewModel(scheduleId: Long = SCHEDULE_ID): RecurringDetailViewModel =
        RecurringDetailViewModel(repository, scheduleId, today = TODAY)
            .also { viewModelStore.put("recurring-detail", it) }

    private fun Fixture.storedSchedule(): RecurringScheduleEntity = recurringDao.storedSchedules.single()

    /**
     * The whole data layer over the in-memory fakes, wired the way `HomeFinanceApp` wires the real one,
     * so a test exercises the same graph production does.
     */
    private class Fixture(
        schedules: List<RecurringScheduleEntity> = emptyList(),
        occurrences: List<RecurringOccurrenceEntity> = emptyList(),
        categories: List<CategoryEntity> = DEFAULT_CATEGORIES,
    ) {
        private val names: Map<Long, String> = categories.associate { it.id to it.name }

        private val categoryDao = FakeCategoryDao(categories)

        val transactionDao = FakeTransactionDao(categoryNames = names)

        val recurringDao = FakeRecurringDao(
            categoryNames = names,
            initialSchedules = schedules,
            initialOccurrences = occurrences,
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

        val TODAY: LocalDate = LocalDate.of(2026, 10, 20)
        val SEEDED_AT: Instant = Instant.parse("2026-10-01T04:30:00Z")

        val DEFAULT_CATEGORIES: List<CategoryEntity> = listOf(
            CategoryEntity.create("Rent", TransactionType.EXPENSE).copy(id = RENT_ID),
        )

        /**
         * A monthly schedule on the 5th, already generated up to today, so that a test which is not about
         * generation is not disturbed by it.
         */
        fun schedule(
            status: RecurringScheduleStatus = RecurringScheduleStatus.ACTIVE,
            startDate: LocalDate = LocalDate.of(2026, 10, 5),
            generatedThrough: LocalDate? = TODAY,
        ): RecurringScheduleEntity = RecurringScheduleEntity(
            id = SCHEDULE_ID,
            name = "Shop rent",
            description = "Ground floor, paid by UPI",
            categoryId = RENT_ID,
            amountPaise = 1_500_000L,
            frequency = RecurrenceFrequency.MONTHLY,
            intervalCount = 1,
            startDate = startDate,
            endDate = LocalDate.of(2027, 3, 5),
            status = status,
            generatedThrough = generatedThrough,
            createdAt = SEEDED_AT,
            updatedAt = SEEDED_AT,
        )

        fun occurrence(
            id: Long,
            dueDate: LocalDate,
            status: RecurringOccurrenceStatus = RecurringOccurrenceStatus.PENDING,
            transactionId: Long? = null,
        ): RecurringOccurrenceEntity = RecurringOccurrenceEntity(
            id = id,
            scheduleId = SCHEDULE_ID,
            dueDate = dueDate,
            amountPaise = 1_500_000L,
            status = status,
            transactionId = transactionId,
            createdAt = SEEDED_AT,
            updatedAt = SEEDED_AT,
        )
    }
}
