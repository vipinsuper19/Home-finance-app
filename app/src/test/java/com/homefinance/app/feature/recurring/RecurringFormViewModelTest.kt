package com.homefinance.app.feature.recurring

import androidx.lifecycle.ViewModelStore
import com.homefinance.app.core.util.MoneyParseResult
import com.homefinance.app.data.local.TransactionRunner
import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.data.local.entity.RecurringOccurrenceEntity
import com.homefinance.app.data.local.entity.RecurringScheduleEntity
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
import com.homefinance.app.data.repository.YieldingTransactionRunner
import com.homefinance.app.feature.addtransaction.CategoriesUiState
import com.homefinance.app.feature.addtransaction.EditLoadState
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The recurring form's rules, driven with fake DAOs behind the real repository.
 *
 * Two things are pinned here that a device test cannot pin as cheaply. First, the **order** of the
 * refusals: a form with five things wrong with it says the topmost one, because five simultaneous
 * complaints bury the one the household is looking at. Second, the **save guard**: a second tap - the
 * one a household makes when the first appears not to have worked, including across a configuration
 * change - must not write a second schedule, and a form that has already saved must not write again.
 *
 * The authoritative rules - the amount really is positive, the interval really is in range, the
 * category really is an active expense category - live in `RecurringRepository` and are asserted against
 * a real database in `RecurringRepositoryTest` and `RecurringRepositoryDatabaseIntegrationTest`. What is
 * asserted here is what the *screen* says about them.
 *
 * One shape of failure is deliberately absent: [RecurringSaveError.ScheduleMissing] can only be produced
 * by a schedule that was there when the form opened and has gone since, and nothing in this app deletes
 * a schedule - they are stopped, not removed - so no JVM test can reach it through the DAO surface. The
 * screen's rendering of that state is asserted in `RecurringFormScreenTest`, and the repository's refusal
 * to resurrect a vanished row in `RecurringRepositoryDatabaseIntegrationTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecurringFormViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    /**
     * Holds the view models so [ViewModelStore.clear] can run their `onCleared`.
     *
     * Without it the category-list collector - which is parked on a `StateFlow` that never completes -
     * would outlive the test.
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

    // -------------------------------------------------------------- opening

    @Test
    fun aNewScheduleIsReadyAtOnceAndStartsTodayOnOneMonth() = runTest {
        val viewModel = Fixture().viewModel()

        advanceUntilIdle()

        val state = viewModel.uiState.value

        assertEquals(EditLoadState.Ready, state.editLoad)
        assertEquals(TODAY, state.startDate)
        assertEquals(RecurrenceFrequency.MONTHLY, state.frequency)
        assertEquals("1", state.intervalInput)
        assertNull(state.endDate)
        assertNull(state.category)
        assertEquals("", state.name)
        assertEquals("", state.amountInput)
    }

    @Test
    fun anEditDrawsNothingUntilTheRowHasBeenReadAndThenShowsWhatIsStored() = runTest {
        val fixture = Fixture(schedules = listOf(existingSchedule()))
        val viewModel = fixture.viewModel(editingId = SCHEDULE_ID)

        // Before the read answers there is nothing but a spinner: an apparently empty form under an edit
        // title would invite figures that were about to be overwritten.
        assertEquals(EditLoadState.Loading, viewModel.uiState.value.editLoad)

        advanceUntilIdle()

        val state = viewModel.uiState.value

        assertEquals(EditLoadState.Ready, state.editLoad)
        assertEquals("Shop rent", state.name)
        assertEquals("Ground floor, paid by UPI", state.description)
        // Written back the way a person would have typed it, so an unchanged save stores the same paise.
        assertEquals("15000", state.amountInput)
        assertEquals(RENT_ID, state.category?.id)
        assertEquals("Rent", state.category?.name)
        assertEquals(RecurrenceFrequency.MONTHLY, state.frequency)
        assertEquals("1", state.intervalInput)
        assertEquals(LocalDate.of(2026, 10, 5), state.startDate)
        assertEquals(LocalDate.of(2027, 3, 5), state.endDate)
    }

    @Test
    fun anEditOfARowThatIsGoneSaysSoRatherThanDrawingABlankForm() = runTest {
        val viewModel = Fixture().viewModel(editingId = SCHEDULE_ID)

        advanceUntilIdle()

        assertEquals(EditLoadState.NotFound, viewModel.uiState.value.editLoad)
        assertEquals("", viewModel.uiState.value.name)
    }

    @Test
    fun aFailedEditReadIsSaidSoAndCanBeReadAgain() = runTest {
        val fixture = Fixture(schedules = listOf(existingSchedule()))
        fixture.recurringDao.readFailure = IOException("the database could not be read")

        val viewModel = fixture.viewModel(editingId = SCHEDULE_ID)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.editLoad is EditLoadState.Failed)

        fixture.recurringDao.readFailure = null
        viewModel.retryEditLoad()
        advanceUntilIdle()

        assertEquals(EditLoadState.Ready, viewModel.uiState.value.editLoad)
        assertEquals("Shop rent", viewModel.uiState.value.name)
    }

    @Test
    fun retryingTheEditReadOnANewScheduleDoesNothing() = runTest {
        val viewModel = Fixture().viewModel()

        advanceUntilIdle()
        viewModel.retryEditLoad()
        advanceUntilIdle()

        // There is no row to read again, and asking for one must not turn a new schedule into an edit.
        assertEquals(EditLoadState.Ready, viewModel.uiState.value.editLoad)
    }

    // ------------------------------------------------------------ the category list

    @Test
    fun onlyActiveExpenseCategoriesAreOffered() = runTest {
        val fixture = Fixture(
            categories = listOf(
                category(id = 1L, name = "Rent", active = true),
                category(id = 2L, name = "Old rent", active = false),
                category(id = 3L, name = "Salary", type = TransactionType.INCOME),
            ),
        )
        val viewModel = fixture.viewModel()

        advanceUntilIdle()

        val options = (viewModel.uiState.value.categories as CategoriesUiState.Ready).options
        assertEquals(listOf(1L to "Rent"), options.map { it.id to it.name })
    }

    @Test
    fun aCategoryThatIsNotOnOfferCannotBeChosen() = runTest {
        val viewModel = Fixture().viewModel()

        advanceUntilIdle()
        viewModel.onCategorySelected(9_999L)

        // Keeping an id the form cannot name would put "nothing chosen" on screen while the save used
        // something else.
        assertNull(viewModel.uiState.value.category)
    }

    @Test
    fun choosingACategoryTakesItsNameFromTheOfferedList() = runTest {
        val viewModel = Fixture().viewModel()

        advanceUntilIdle()
        viewModel.onCategorySelected(RENT_ID)

        assertEquals(RENT_ID, viewModel.uiState.value.category?.id)
        assertEquals("Rent", viewModel.uiState.value.category?.name)
    }

    @Test
    fun aFailedCategoryReadIsSaidSoAndCanBeAskedForAgain() = runTest {
        val fixture = Fixture()
        fixture.categoryDao.failure = IOException("the categories could not be read")

        val viewModel = fixture.viewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.categories is CategoriesUiState.Failed)

        fixture.categoryDao.failure = null
        viewModel.retryCategories()
        advanceUntilIdle()

        val options = (viewModel.uiState.value.categories as CategoriesUiState.Ready).options
        assertEquals(listOf(RENT_ID), options.map { it.id })
    }

    // -------------------------------------------------------------- validation

    @Test
    fun aBlankNameIsSaidOnTheNameAndNothingIsWritten() = runTest {
        val fixture = Fixture()
        val viewModel = fixture.viewModel()
        advanceUntilIdle()

        viewModel.onAmountChange("15000")
        viewModel.save()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showNameError)
        assertTrue(fixture.recurringDao.insertedSchedules.isEmpty())
        assertNull(viewModel.uiState.value.savedScheduleId)
    }

    @Test
    fun theFirstComplaintIsTheTopmostFieldRatherThanAllOfThemAtOnce() = runTest {
        val viewModel = Fixture().viewModel()
        advanceUntilIdle()

        // Nothing is filled in at all: blank name, no amount, an interval that is not a number, no
        // category, and an end date before the start. Exactly one of those is said, and it is the one
        // nearest the top of the form.
        viewModel.onIntervalChange("soon")
        viewModel.onEndDateChange(TODAY.minusDays(1))
        viewModel.save()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.showNameError)
        assertNull(state.amountError)
        assertNull(state.intervalError)
        assertFalse(state.showCategoryError)
        assertFalse(state.showEndDateError)
    }

    @Test
    fun anAmountThatIsNotMoneyIsRefusedBeforeTheCategoryIsEvenAskedFor() = runTest {
        val viewModel = Fixture().viewModel()
        advanceUntilIdle()

        viewModel.onNameChange("Shop rent")
        viewModel.onAmountChange("0")
        viewModel.save()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(MoneyParseResult.Problem.NotPositive, state.amountError)
        assertFalse(state.showCategoryError)
        assertFalse(state.showEndDateError)
    }

    @Test
    fun anIntervalThatIsNotACountIsRefusedNext() = runTest {
        val viewModel = Fixture().viewModel()
        advanceUntilIdle()

        viewModel.onNameChange("Shop rent")
        viewModel.onAmountChange("15000")
        viewModel.onIntervalChange("1.5")
        viewModel.save()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.amountError)
        assertEquals(IntervalProblem.NotANumber, state.intervalError)
        assertFalse(state.showCategoryError)
    }

    @Test
    fun aMissingCategoryIsRefusedAfterTheFiguresAreAccepted() = runTest {
        val viewModel = Fixture().viewModel()
        advanceUntilIdle()

        viewModel.onNameChange("Shop rent")
        viewModel.onAmountChange("15000")
        viewModel.save()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.amountError)
        assertNull(state.intervalError)
        assertTrue(state.showCategoryError)
        assertFalse(state.showEndDateError)
    }

    @Test
    fun aLastOccurrenceBeforeTheFirstIsRefusedLast() = runTest {
        val viewModel = Fixture().viewModel()
        advanceUntilIdle()

        viewModel.onNameChange("Shop rent")
        viewModel.onAmountChange("15000")
        viewModel.onCategorySelected(RENT_ID)
        viewModel.onEndDateChange(TODAY.minusDays(1))
        viewModel.save()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.showEndDateError)
        assertNull(state.saveError)
        assertNull(state.savedScheduleId)
    }

    @Test
    fun typingAgainClearsTheComplaintAboutThatField() = runTest {
        val viewModel = Fixture().viewModel()
        advanceUntilIdle()

        viewModel.save()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showNameError)

        viewModel.onNameChange("Shop rent")

        assertFalse(viewModel.uiState.value.showNameError)
    }

    // ------------------------------------------------------------------ saving

    @Test
    fun aCompleteFormWritesOneScheduleAndReportsItsIdOnlyAfterwards() = runTest {
        val fixture = Fixture()
        val viewModel = fixture.viewModel()
        advanceUntilIdle()

        viewModel.onNameChange("  Shop rent  ")
        viewModel.onDescriptionChange("Ground floor, paid by UPI")
        viewModel.onAmountChange("15000")
        viewModel.onCategorySelected(RENT_ID)
        viewModel.onFrequencyChange(RecurrenceFrequency.WEEKLY)
        viewModel.onIntervalChange("2")
        viewModel.onStartDateChange(LocalDate.of(2026, 11, 1))
        viewModel.onEndDateChange(LocalDate.of(2026, 12, 1))
        viewModel.save()
        advanceUntilIdle()

        val stored = fixture.recurringDao.storedSchedules.single()

        assertEquals("Shop rent", stored.name)
        assertEquals("Ground floor, paid by UPI", stored.description)
        assertEquals(1_500_000L, stored.amountPaise)
        assertEquals(RENT_ID, stored.categoryId)
        assertEquals(RecurrenceFrequency.WEEKLY, stored.frequency)
        assertEquals(2, stored.intervalCount)
        assertEquals(LocalDate.of(2026, 11, 1), stored.startDate)
        assertEquals(LocalDate.of(2026, 12, 1), stored.endDate)
        assertEquals(RecurringScheduleStatus.ACTIVE, stored.status)
        assertEquals(NOW, stored.createdAt)

        val state = viewModel.uiState.value
        assertFalse(state.isSaving)
        assertEquals(stored.id, state.savedScheduleId)
        // Creating a schedule is not spending: no expense may appear because a plan now exists.
        assertTrue(fixture.transactionDao.inserted.isEmpty())
    }

    @Test
    fun aSecondTapWhileTheFirstIsStillWritingCannotWriteASecondSchedule() = runTest {
        val fixture = Fixture(runner = YieldingTransactionRunner)
        val viewModel = fixture.viewModel()
        advanceUntilIdle()

        viewModel.onNameChange("Shop rent")
        viewModel.onAmountChange("15000")
        viewModel.onCategorySelected(RENT_ID)

        launch { viewModel.save() }
        yield()
        // The first write is provably suspended mid-transaction, so what refuses the second is the save
        // guard rather than the write happening to finish first.
        viewModel.save()
        advanceUntilIdle()

        assertEquals(1, fixture.recurringDao.insertedSchedules.size)
    }

    @Test
    fun aFormThatHasAlreadySavedRefusesToWriteAgain() = runTest {
        val fixture = Fixture()
        val viewModel = fixture.viewModel()
        advanceUntilIdle()

        viewModel.onNameChange("Shop rent")
        viewModel.onAmountChange("15000")
        viewModel.onCategorySelected(RENT_ID)
        viewModel.save()
        advanceUntilIdle()

        viewModel.save()
        advanceUntilIdle()

        assertEquals(1, fixture.recurringDao.insertedSchedules.size)
    }

    @Test
    fun anEditRewritesTheRowItReadRatherThanRecordingAnother() = runTest {
        val fixture = Fixture(schedules = listOf(existingSchedule()))
        val viewModel = fixture.viewModel(editingId = SCHEDULE_ID)
        advanceUntilIdle()

        viewModel.onNameChange("Shop rent (revised)")
        viewModel.onAmountChange("16000")
        viewModel.save()
        advanceUntilIdle()

        val stored = fixture.recurringDao.storedSchedules.single()

        assertEquals(SCHEDULE_ID, stored.id)
        assertEquals("Shop rent (revised)", stored.name)
        assertEquals(1_600_000L, stored.amountPaise)
        // An edit cannot move a schedule to a new identity or backdate when it was first recorded.
        assertEquals(SEEDED_AT, stored.createdAt)
        assertEquals(NOW, stored.updatedAt)
        assertEquals(SCHEDULE_ID, viewModel.uiState.value.savedScheduleId)
    }

    @Test
    fun anEditLeavesAScheduleUnderARetiredCategorySavable() = runTest {
        val fixture = Fixture(
            schedules = listOf(existingSchedule()),
            categories = listOf(category(id = RENT_ID, name = "Rent", active = false)),
        )
        val viewModel = fixture.viewModel(editingId = SCHEDULE_ID)
        advanceUntilIdle()

        // Nothing is offered to pick, but the name the row was filed under comes back with the row, and
        // turning a category off must not make the schedules under it uneditable.
        assertEquals("Rent", viewModel.uiState.value.category?.name)

        viewModel.onAmountChange("16000")
        viewModel.save()
        advanceUntilIdle()

        assertEquals(1_600_000L, fixture.recurringDao.storedSchedules.single().amountPaise)
        assertNull(viewModel.uiState.value.saveError)
    }

    @Test
    fun aCategoryRetiredBetweenChoosingItAndSavingIsSaidSoAndNothingIsWritten() = runTest {
        val fixture = Fixture()
        val viewModel = fixture.viewModel()
        advanceUntilIdle()

        viewModel.onNameChange("Shop rent")
        viewModel.onAmountChange("15000")
        viewModel.onCategorySelected(RENT_ID)

        // The category is turned off in another screen while this form is open. The picker cannot have
        // offered a retired category, so this is the one refusal from the repository that the form's own
        // validation could not have caught.
        fixture.categoryDao.deactivate(RENT_ID)
        viewModel.save()
        advanceUntilIdle()

        assertEquals(RecurringSaveError.CategoryUnavailable, viewModel.uiState.value.saveError)
        assertTrue(fixture.recurringDao.insertedSchedules.isEmpty())
        assertNull(viewModel.uiState.value.savedScheduleId)
    }

    @Test
    fun aFailedWriteIsSaidSoAndLeavesTheTypedFiguresAlone() = runTest {
        val fixture = Fixture()
        fixture.recurringDao.writeFailure = IOException("the disk filled up")

        val viewModel = fixture.viewModel()
        advanceUntilIdle()

        viewModel.onNameChange("Shop rent")
        viewModel.onAmountChange("15000")
        viewModel.onCategorySelected(RENT_ID)
        viewModel.save()
        advanceUntilIdle()

        val state = viewModel.uiState.value

        assertTrue(state.saveError is RecurringSaveError.Failed)
        assertFalse(state.isSaving)
        // Not reported as saved, and the household's own figures are still on screen to try again with.
        assertNull(state.savedScheduleId)
        assertEquals("Shop rent", state.name)
        assertEquals("15000", state.amountInput)
    }

    // ------------------------------------------------------------------ fixtures

    private fun Fixture.viewModel(editingId: Long? = null): RecurringFormViewModel =
        RecurringFormViewModel(
            repository = repository,
            categories = categoryRepository,
            today = TODAY,
            editingId = editingId,
            instant = { NOW },
        ).also { viewModelStore.put("recurring-form", it) }

    /**
     * The data layer over in-memory fakes, wired the way `HomeFinanceApp` wires the real one, so a test
     * exercises the same graph production does.
     */
    private class Fixture(
        schedules: List<RecurringScheduleEntity> = emptyList(),
        occurrences: List<RecurringOccurrenceEntity> = emptyList(),
        categories: List<CategoryEntity> = DEFAULT_CATEGORIES,
        runner: TransactionRunner = DirectTransactionRunner,
    ) {
        private val names: Map<Long, String> = categories.associate { it.id to it.name }

        val categoryDao = FakeCategoryDao(categories)

        val transactionDao = FakeTransactionDao(categoryNames = names)

        val recurringDao = FakeRecurringDao(
            categoryNames = names,
            initialSchedules = schedules,
            initialOccurrences = occurrences,
        )

        val categoryRepository = CategoryRepository(categoryDao)

        val repository = RecurringRepository(
            recurringDao = recurringDao,
            transactions = TransactionRepository(transactionDao, categoryDao),
            categories = categoryRepository,
            generator = RecurrenceGenerator(recurringDao, runner),
            transactionRunner = runner,
        )
    }

    private companion object {

        const val SCHEDULE_ID = 1L
        const val RENT_ID = 10L

        val TODAY: LocalDate = LocalDate.of(2026, 10, 20)
        val NOW: Instant = Instant.parse("2026-10-20T04:30:00Z")
        val SEEDED_AT: Instant = Instant.parse("2026-10-01T04:30:00Z")

        val DEFAULT_CATEGORIES: List<CategoryEntity> = listOf(category(id = RENT_ID, name = "Rent"))

        fun category(
            id: Long,
            name: String,
            active: Boolean = true,
            type: TransactionType = TransactionType.EXPENSE,
        ): CategoryEntity = CategoryEntity.create(name, type).copy(id = id, isActive = active)

        /**
         * A monthly schedule already generated up to today, so that a test about the form is not
         * disturbed by generation happening underneath it.
         */
        fun existingSchedule(): RecurringScheduleEntity = RecurringScheduleEntity(
            id = SCHEDULE_ID,
            name = "Shop rent",
            description = "Ground floor, paid by UPI",
            categoryId = RENT_ID,
            amountPaise = 1_500_000L,
            frequency = RecurrenceFrequency.MONTHLY,
            intervalCount = 1,
            startDate = LocalDate.of(2026, 10, 5),
            endDate = LocalDate.of(2027, 3, 5),
            status = RecurringScheduleStatus.ACTIVE,
            generatedThrough = TODAY,
            createdAt = SEEDED_AT,
            updatedAt = SEEDED_AT,
        )
    }
}
