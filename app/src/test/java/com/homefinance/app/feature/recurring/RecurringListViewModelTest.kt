package com.homefinance.app.feature.recurring

import androidx.lifecycle.ViewModelStore
import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.data.local.entity.RecurringOccurrenceEntity
import com.homefinance.app.data.local.entity.RecurringScheduleEntity
import com.homefinance.app.data.local.relation.RecurringOccurrenceDetail
import com.homefinance.app.data.model.RecurrenceFrequency
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
import kotlinx.coroutines.flow.first
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Recurring tab's state machine, driven with fake DAOs behind the real repository.
 *
 * The assertions here are about the two things this screen is trusted with and that nothing else can
 * check as cheaply. First, that opening the tab is where the generation run happens - and that a run
 * which only creates expectations never writes a ledger entry, however many dates it fills in. Second,
 * that a failure is never allowed to masquerade as an empty list: "you have no schedules" and "your
 * schedules could not be read" are the same pixels if the state is sloppy, and only one of them is true.
 *
 * `Dispatchers.Main` - which `viewModelScope` uses and which does not exist off-device - is replaced with
 * a test dispatcher, the day the screen believes it is is pinned through the constructor, and no state is
 * read until the work has been advanced to completion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecurringListViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    /**
     * Holds the view models so [ViewModelStore.clear] can run their `onCleared`, which is what stops the
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
    fun theScreenStartsOnASpinnerRatherThanClaimingTheHouseholdHasNothing() = runTest {
        val viewModel = Fixture().viewModel()

        assertEquals(RecurringListUiState.Loading, viewModel.uiState.value)
    }

    @Test
    fun anEmptyDatabaseIsAReadyListRatherThanAFailure() = runTest {
        val viewModel = Fixture().viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        val state = viewModel.ready()

        assertTrue(state.schedules.isEmpty())
        assertNull(state.actionError)
    }

    @Test
    fun theSchedulesArriveWithTheirNamesAndOrdering() = runTest {
        val viewModel = Fixture(
            schedules = listOf(
                schedule(id = 1L, name = "Shop rent", status = RecurringScheduleStatus.ACTIVE),
                schedule(id = 2L, name = "Broadband", status = RecurringScheduleStatus.PAUSED),
            ),
        ).viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        // Whatever the query ordered, the screen draws; the ordering itself is asserted against Room in
        // `RecurringDaoTest`, which is the only place that can honestly check an `ORDER BY`.
        assertEquals(listOf("Shop rent", "Broadband"), viewModel.ready().schedules.map { it.name })
    }

    // --------------------------------------------------------------- failing reads

    @Test
    fun aFailedReadBecomesAFailureAndNotAnEmptyList() = runTest {
        val fixture = Fixture()
        fixture.recurringDao.readFailure = IOException("the database could not be read")

        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        // The distinction this test exists for: a household whose schedules cannot be read still *has*
        // schedules, and a blank list with an invitation to add one would tell them the opposite.
        assertTrue(viewModel.uiState.value is RecurringListUiState.Failed)
    }

    @Test
    fun retryReReadsTheSchedulesAfterAFailure() = runTest {
        val fixture = Fixture(schedules = listOf(schedule(id = 1L, name = "Shop rent")))
        fixture.recurringDao.readFailure = IOException("the database could not be read")

        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is RecurringListUiState.Failed)

        fixture.recurringDao.readFailure = null
        viewModel.retry()
        advanceUntilIdle()

        assertEquals(listOf("Shop rent"), viewModel.ready().schedules.map { it.name })
    }

    // ------------------------------------------------------------------ generation

    @Test
    fun openingTheTabFillsInTheDueDatesWithoutWritingALedgerEntry() = runTest {
        val fixture = Fixture(
            schedules = listOf(schedule(startDate = LocalDate.of(2026, 10, 5), generatedThrough = null)),
        )

        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        val card = viewModel.ready().schedules.single()

        // The run happened: the schedule now knows when it is next expected.
        assertEquals(LocalDate.of(2026, 10, 5), card.nextDueDate)
        assertEquals(OccurrenceDisplayState.OVERDUE, card.nextDueState)

        // And it created expectations only. This is the financial rule the whole feature turns on: a
        // due date passing is not spending, and nothing in this app may quietly move a total.
        assertTrue(fixture.transactionDao.inserted.isEmpty())
    }

    @Test
    fun visitingTheTabAgainDoesNotDuplicateWhatIsAlreadyGenerated() = runTest {
        val fixture = Fixture(
            schedules = listOf(schedule(startDate = LocalDate.of(2026, 10, 5), generatedThrough = null)),
        )

        val first = fixture.viewModel()
        collectUiState(first)
        advanceUntilIdle()

        val generatedOnce = fixture.occurrencesOf(SCHEDULE_ID).size
        assertTrue("the first visit should generate the dates the rule implies", generatedOnce > 0)

        // A second visit - the household leaving the tab and coming back, or the app being opened
        // tomorrow - runs the same engine over the same rows. The unique index is the authority on
        // duplicates; this proves the run is genuinely re-runnable rather than merely guarded once.
        first.retry()
        advanceUntilIdle()

        assertEquals(generatedOnce, fixture.occurrencesOf(SCHEDULE_ID).size)
        assertTrue(fixture.transactionDao.inserted.isEmpty())
    }

    @Test
    fun aFailedGenerationIsSaidBesideTheSchedulesRatherThanInsteadOfThem() = runTest {
        val fixture = Fixture(
            schedules = listOf(schedule(startDate = LocalDate.of(2026, 10, 5), generatedThrough = null)),
        )
        fixture.recurringDao.writeFailure = IOException("the disk filled up")

        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        val state = viewModel.ready()

        // The schedules on screen are still true, so they stay; a run that did not finish is reported
        // because a next due date silently a month out of date would be a lie of omission.
        assertEquals(listOf("Shop rent"), state.schedules.map { it.name })
        assertTrue(state.actionError is ActionError.GenerationFailed)
    }

    // ------------------------------------------------------------- pause and resume

    @Test
    fun switchingAScheduleOffPausesItAndWritesNothingElse() = runTest {
        val fixture = Fixture(schedules = listOf(schedule()))
        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        viewModel.setActive(SCHEDULE_ID, active = false)
        advanceUntilIdle()

        assertEquals(RecurringScheduleStatus.PAUSED, fixture.storedSchedule().status)
        assertNull(viewModel.ready().actionError)
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

        viewModel.setActive(SCHEDULE_ID, active = true)
        advanceUntilIdle()

        assertEquals(RecurringScheduleStatus.ACTIVE, fixture.storedSchedule().status)

        // Pausing means "stop expecting this", so the date that fell inside the pause is not suddenly
        // conjured back as something overdue: the next expectation is the next real one.
        val dueDates = fixture.occurrencesOf(SCHEDULE_ID).map { it.dueDate }
        assertTrue(LocalDate.of(2026, 10, 5) !in dueDates)
        assertEquals(LocalDate.of(2026, 11, 5), dueDates.minOrNull())
        assertTrue(fixture.transactionDao.inserted.isEmpty())
    }

    @Test
    fun aFailedResumeIsReportedBesideAStillValidList() = runTest {
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

        viewModel.setActive(SCHEDULE_ID, active = true)
        advanceUntilIdle()

        val state = viewModel.ready()

        assertEquals(listOf("Shop rent"), state.schedules.map { it.name })
        assertTrue(state.actionError is ActionError.Failed)
    }

    @Test
    fun theFailureStripCanBeDismissedWithoutTouchingTheSchedules() = runTest {
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

        viewModel.setActive(SCHEDULE_ID, active = true)
        advanceUntilIdle()
        assertTrue(viewModel.ready().actionError is ActionError.Failed)

        viewModel.dismissActionError()
        advanceUntilIdle()

        val state = viewModel.ready()
        assertNull(state.actionError)
        assertEquals(listOf("Shop rent"), state.schedules.map { it.name })
    }

    // ----------------------------------------------------------------- the filter

    @Test
    fun theListStartsShowingBothDirections() = runTest {
        val fixture = Fixture(
            schedules = listOf(
                schedule(id = 1L, name = "Shop rent"),
                schedule(id = 2L, name = "Salary", categoryId = SALARY_ID, amountPaise = 4_500_000L),
            ),
        )
        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        val state = viewModel.ready()
        assertEquals(ScheduleTypeFilter.ALL, state.filter)
        // The order the query chose is Room's business (asserted in `RecurringDaoTest`); what matters here
        // is that the default filter hides nothing.
        assertEquals(setOf("Shop rent", "Salary"), state.schedules.map { it.name }.toSet())
    }

    @Test
    fun choosingIncomeLeavesOnlyTheIncomeSchedules() = runTest {
        val fixture = Fixture(
            schedules = listOf(
                schedule(id = 1L, name = "Shop rent"),
                schedule(id = 2L, name = "Salary", categoryId = SALARY_ID, amountPaise = 4_500_000L),
            ),
        )
        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        viewModel.onFilterChange(ScheduleTypeFilter.INCOME)
        advanceUntilIdle()

        // Filtered in memory, from the rows the query already returned: a filter must never re-read the
        // database, because a re-read is a second chance to disagree with what is on screen.
        val state = viewModel.ready()
        assertEquals(listOf("Salary"), state.schedules.map { it.name })
        assertEquals(TransactionType.INCOME, state.schedules.single().categoryType)
    }

    @Test
    fun choosingExpenseLeavesOnlyTheExpenseSchedules() = runTest {
        val fixture = Fixture(
            schedules = listOf(
                schedule(id = 1L, name = "Shop rent"),
                schedule(id = 2L, name = "Salary", categoryId = SALARY_ID, amountPaise = 4_500_000L),
            ),
        )
        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        viewModel.onFilterChange(ScheduleTypeFilter.EXPENSE)
        advanceUntilIdle()

        assertEquals(listOf("Shop rent"), viewModel.ready().schedules.map { it.name })
    }

    @Test
    fun aFilterThatExcludesEverythingStillSaysTheHouseholdHasSchedules() = runTest {
        val fixture = Fixture(schedules = listOf(schedule(id = 1L, name = "Shop rent")))
        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        viewModel.onFilterChange(ScheduleTypeFilter.INCOME)
        advanceUntilIdle()

        // The distinction the screen draws: "you have no schedules" and "none of yours are income" are
        // different sentences, and printing the first for the second would be a lie about the household's
        // own ledger.
        val state = viewModel.ready()
        assertEquals(emptyList<String>(), state.schedules.map { it.name })
        assertTrue(state.hasAnySchedule)
    }

    @Test
    fun anEmptyDatabaseSaysThereIsNothingToFilter() = runTest {
        val viewModel = Fixture().viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        assertFalse(viewModel.ready().hasAnySchedule)
    }

    @Test
    fun choosingAllAgainBringsTheWholeListBack() = runTest {
        val fixture = Fixture(
            schedules = listOf(
                schedule(id = 1L, name = "Shop rent"),
                schedule(id = 2L, name = "Salary", categoryId = SALARY_ID, amountPaise = 4_500_000L),
            ),
        )
        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        viewModel.onFilterChange(ScheduleTypeFilter.INCOME)
        advanceUntilIdle()
        viewModel.onFilterChange(ScheduleTypeFilter.ALL)
        advanceUntilIdle()

        assertEquals(setOf("Shop rent", "Salary"), viewModel.ready().schedules.map { it.name }.toSet())
    }

    @Test
    fun theUnitPriceIsCarriedOntoTheRow() = runTest {
        val fixture = Fixture(
            schedules = listOf(
                schedule(id = 1L, name = "Milk", amountPaise = 6_000L, unit = "litre", ratePaise = 6_000L),
            ),
        )
        val viewModel = fixture.viewModel()

        collectUiState(viewModel)
        advanceUntilIdle()

        val card = viewModel.ready().schedules.single()
        assertEquals("litre", card.unit)
        assertEquals(6_000L, card.ratePaise)
    }

    // ------------------------------------------------------------------ fixtures

    /** Starts collecting the state the way the screen does, so the shared flow actually runs. */
    private fun TestScope.collectUiState(viewModel: RecurringListViewModel) {
        backgroundScope.launch { viewModel.uiState.collect { } }
    }

    /** Asserts the state is [RecurringListUiState.Ready] and returns it. */
    private fun RecurringListViewModel.ready(): RecurringListUiState.Ready {
        val state = uiState.value
        assertTrue("expected the schedules to have loaded, was $state", state is RecurringListUiState.Ready)
        return state as RecurringListUiState.Ready
    }

    private fun Fixture.viewModel(): RecurringListViewModel =
        RecurringListViewModel(repository, today = TODAY).also { viewModelStore.put("recurring", it) }

    private fun Fixture.storedSchedule(): RecurringScheduleEntity = recurringDao.storedSchedules.single()

    /**
     * The rows the occurrence screens would see, read through the DAO's own observation rather than a
     * side door only a test can reach.
     */
    private suspend fun Fixture.occurrencesOf(scheduleId: Long): List<RecurringOccurrenceDetail> =
        recurringDao.observeOccurrencesForSchedule(scheduleId).first()

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

        private val types: Map<Long, TransactionType> = categories.associate { it.id to it.type }

        private val categoryDao = FakeCategoryDao(categories)

        val transactionDao = FakeTransactionDao(categoryNames = names)

        val recurringDao = FakeRecurringDao(
            categoryNames = names,
            initialSchedules = schedules,
            initialOccurrences = occurrences,
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

        val TODAY: LocalDate = LocalDate.of(2026, 10, 20)

        val DEFAULT_CATEGORIES: List<CategoryEntity> = listOf(
            CategoryEntity.create("Rent", TransactionType.EXPENSE).copy(id = RENT_ID),
            CategoryEntity.create("Salary", TransactionType.INCOME).copy(id = SALARY_ID),
        )

        /**
         * A monthly schedule on the 5th that has already been generated up to today, so that a test
         * which is not about generation is not disturbed by it.
         *
         * The direction of the row is not a field here: it is whatever the category it is filed under
         * says, which is the whole reason an income schedule needs no column of its own.
         */
        fun schedule(
            id: Long = SCHEDULE_ID,
            name: String = "Shop rent",
            status: RecurringScheduleStatus = RecurringScheduleStatus.ACTIVE,
            startDate: LocalDate = LocalDate.of(2026, 10, 5),
            generatedThrough: LocalDate? = TODAY,
            categoryId: Long = RENT_ID,
            amountPaise: Long = 1_500_000L,
            unit: String? = null,
            ratePaise: Long? = null,
        ): RecurringScheduleEntity = RecurringScheduleEntity(
            id = id,
            name = name,
            description = null,
            categoryId = categoryId,
            amountPaise = amountPaise,
            unit = unit,
            ratePaise = ratePaise,
            frequency = RecurrenceFrequency.MONTHLY,
            intervalCount = 1,
            startDate = startDate,
            endDate = null,
            status = status,
            generatedThrough = generatedThrough,
            createdAt = Instant.parse("2026-10-01T04:30:00Z"),
            updatedAt = Instant.parse("2026-10-01T04:30:00Z"),
        )
    }
}
