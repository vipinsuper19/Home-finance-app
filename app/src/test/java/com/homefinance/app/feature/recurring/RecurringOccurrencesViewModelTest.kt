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
 * One schedule's occurrence history, driven with fake DAOs behind the real repository.
 *
 * The history screen owns nothing but a read and two dialogs, so what is pinned here is that the title
 * comes from the same read as the rows - so the two can never describe different schedules - that a
 * schedule with no occurrences yet is drawn as an empty history rather than a failure, and that both
 * settle actions reach the repository through this screen exactly as they do through the details one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecurringOccurrencesViewModelTest {

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
    fun theScreenStartsOnASpinnerRatherThanClaimingTheHistoryIsEmpty() = runTest {
        val viewModel = Fixture(schedules = listOf(schedule())).viewModel()

        assertEquals(RecurringOccurrencesUiState.Loading, viewModel.uiState.value)
    }

    @Test
    fun theTitleAndTheRowsComeFromTheSameRead() = runTest {
        val viewModel = Fixture(
            schedules = listOf(schedule()),
            occurrences = listOf(occurrence(id = 1L, dueDate = LocalDate.of(2026, 10, 5))),
        ).viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        val content = viewModel.content()

        // The name is carried by the read rather than passed in by the caller, so a schedule renamed
        // since the row was tapped can never be drawn under its old name here.
        assertEquals("Shop rent", content.scheduleName)
        assertEquals(1, content.occurrences.size)
    }

    @Test
    fun everyDateIsListedNewestFirst() = runTest {
        val viewModel = Fixture(
            schedules = listOf(schedule()),
            occurrences = (1L..6L).map { id ->
                occurrence(id = id, dueDate = LocalDate.of(2026, 1, 5).plusMonths(id - 1))
            },
        ).viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        // The full history, not the details screen's short list: nothing is truncated here.
        assertEquals(
            listOf(6L, 5L, 4L, 3L, 2L, 1L),
            viewModel.content().occurrences.map { it.id },
        )
    }

    @Test
    fun aScheduleWithNothingGeneratedYetIsAnEmptySuccessRatherThanAFailure() = runTest {
        val viewModel = Fixture(schedules = listOf(schedule())).viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("expected an empty history, was $state", state is RecurringOccurrencesUiState.Content)
        assertTrue((state as RecurringOccurrencesUiState.Content).occurrences.isEmpty())
    }

    @Test
    fun aScheduleThatHasGoneIsNotFoundRatherThanAFailure() = runTest {
        val viewModel = Fixture().viewModel(scheduleId = 4_242L)

        collectUiState(viewModel)
        advanceUntilIdle()

        assertEquals(RecurringOccurrencesUiState.NotFound, viewModel.uiState.value)
    }

    @Test
    fun aFailedReadIsAFailureRatherThanAnEmptyHistory() = runTest {
        val fixture = Fixture(schedules = listOf(schedule()))
        fixture.recurringDao.readFailure = IOException("the database could not be read")

        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("expected a failed read, was $state", state is RecurringOccurrencesUiState.Failed)
        assertEquals(
            "the database could not be read",
            (state as RecurringOccurrencesUiState.Failed).cause.message,
        )
    }

    @Test
    fun retryReReadsTheHistoryAfterAFailure() = runTest {
        val fixture = Fixture(
            schedules = listOf(schedule()),
            occurrences = listOf(occurrence(id = 1L, dueDate = LocalDate.of(2026, 10, 5))),
        )
        fixture.recurringDao.readFailure = IOException("the database could not be read")

        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is RecurringOccurrencesUiState.Failed)

        fixture.recurringDao.readFailure = null
        viewModel.retry()
        advanceUntilIdle()

        assertEquals(1, viewModel.content().occurrences.size)
    }

    // ------------------------------------------------------------------ the dialogs

    @Test
    fun openingAPaymentFromTheHistoryWritesNothing() = runTest {
        val fixture = Fixture(
            schedules = listOf(schedule()),
            occurrences = listOf(occurrence(id = 7L, dueDate = LocalDate.of(2026, 10, 5))),
        )
        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        viewModel.requestPay(viewModel.content().occurrences.single())
        advanceUntilIdle()

        val pay = checkNotNull(viewModel.pay.value)
        assertEquals(7L, pay.occurrenceId)
        assertEquals(1_500_000L, pay.expectedAmountPaise)
        assertEquals("15000", pay.amountInput)
        assertTrue(fixture.transactionDao.inserted.isEmpty())
    }

    @Test
    fun confirmingAPaymentFromTheHistoryRecordsOneExpense() = runTest {
        val fixture = Fixture(
            schedules = listOf(schedule()),
            occurrences = listOf(occurrence(id = 7L, dueDate = LocalDate.of(2026, 10, 5))),
        )
        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        viewModel.requestPay(viewModel.content().occurrences.single())
        viewModel.confirmPay()
        advanceUntilIdle()

        assertEquals(1, fixture.transactionDao.inserted.size)
        assertNull(viewModel.pay.value)
        assertEquals(RecurringOccurrenceStatus.PAID, fixture.recurringDao.storedOccurrences.single().status)
    }

    @Test
    fun skippingFromTheHistoryRecordsNoExpense() = runTest {
        val fixture = Fixture(
            schedules = listOf(schedule()),
            occurrences = listOf(occurrence(id = 7L, dueDate = LocalDate.of(2026, 10, 5))),
        )
        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        viewModel.requestSkip(viewModel.content().occurrences.single())
        viewModel.confirmSkip()
        advanceUntilIdle()

        assertNull(viewModel.skip.value)
        assertTrue(fixture.transactionDao.inserted.isEmpty())
        assertEquals(RecurringOccurrenceStatus.SKIPPED, fixture.recurringDao.storedOccurrences.single().status)
    }

    @Test
    fun dismissingAPaymentDialogLeavesTheOccurrenceExactlyAsItWas() = runTest {
        val fixture = Fixture(
            schedules = listOf(schedule()),
            occurrences = listOf(occurrence(id = 7L, dueDate = LocalDate.of(2026, 10, 5))),
        )
        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        viewModel.requestPay(viewModel.content().occurrences.single())
        viewModel.onPayAmountChange("16000")
        viewModel.dismissPay()
        advanceUntilIdle()

        assertNull(viewModel.pay.value)
        assertTrue(fixture.transactionDao.inserted.isEmpty())
        assertEquals(
            RecurringOccurrenceStatus.PENDING,
            fixture.recurringDao.storedOccurrences.single().status,
        )
    }

    // ------------------------------------------------------------------ fixtures

    /** Starts collecting the state the way the screen does, so the shared flow actually runs. */
    private fun TestScope.collectUiState(viewModel: RecurringOccurrencesViewModel) {
        backgroundScope.launch { viewModel.uiState.collect { } }
    }

    /** Asserts the state is [RecurringOccurrencesUiState.Content] and returns it. */
    private fun RecurringOccurrencesViewModel.content(): RecurringOccurrencesUiState.Content {
        val state = uiState.value
        assertTrue(
            "expected the history to have loaded, was $state",
            state is RecurringOccurrencesUiState.Content,
        )
        return state as RecurringOccurrencesUiState.Content
    }

    private fun Fixture.viewModel(scheduleId: Long = SCHEDULE_ID): RecurringOccurrencesViewModel =
        RecurringOccurrencesViewModel(repository, scheduleId, today = TODAY)
            .also { viewModelStore.put("recurring-occurrences", it) }

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

        fun schedule(): RecurringScheduleEntity = RecurringScheduleEntity(
            id = SCHEDULE_ID,
            name = "Shop rent",
            description = "Ground floor, paid by UPI",
            categoryId = RENT_ID,
            amountPaise = 1_500_000L,
            frequency = RecurrenceFrequency.MONTHLY,
            intervalCount = 1,
            startDate = LocalDate.of(2026, 10, 5),
            endDate = null,
            status = RecurringScheduleStatus.ACTIVE,
            generatedThrough = TODAY,
            createdAt = SEEDED_AT,
            updatedAt = SEEDED_AT,
        )

        fun occurrence(
            id: Long,
            dueDate: LocalDate,
            status: RecurringOccurrenceStatus = RecurringOccurrenceStatus.PENDING,
        ): RecurringOccurrenceEntity = RecurringOccurrenceEntity(
            id = id,
            scheduleId = SCHEDULE_ID,
            dueDate = dueDate,
            amountPaise = 1_500_000L,
            status = status,
            transactionId = null,
            createdAt = SEEDED_AT,
            updatedAt = SEEDED_AT,
        )
    }
}
