package com.homefinance.app.feature.recurring

import com.homefinance.app.core.util.MoneyParseResult
import com.homefinance.app.data.local.TransactionRunner
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
import com.homefinance.app.data.repository.YieldingTransactionRunner
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "mark as paid" and "skip" state machines, over the real repository and fake DAOs.
 *
 * The rule this file exists to defend is that **money is written by exactly one confirmed tap, and by
 * nothing else**. Opening a dialog, typing in it, cancelling it, skipping an occurrence, or tapping
 * confirm twice must all leave the ledger untouched; only a single confirmed payment may add an expense,
 * and it must add exactly one.
 *
 * The rollback that makes a raced payment safe under real SQLite is asserted against Room in
 * `RecurringRepositoryDatabaseIntegrationTest`. What is asserted here is the layer above it: that the
 * controller cannot even *ask* for a second write, and that every refusal leaves the household's typed
 * figure on screen with a sentence explaining itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OccurrenceActionsControllerTest {

    // ---------------------------------------------------------------- opening only

    @Test
    fun openingThePaymentDialogWritesNothing() = runTest {
        val fixture = Fixture()
        val controller = fixture.controller()

        controller.requestPay(TARGET)

        assertNotNull(controller.pay.value)
        assertEquals("15000", controller.pay.value?.amountInput)
        assertEquals(0, fixture.transactionDao.inserted.size)
        assertEquals(RecurringOccurrenceStatus.PENDING, fixture.storedStatus())

        // The two figures the dialog has to keep apart are both carried into it.
        assertEquals(1_500_000L, controller.pay.value?.expectedAmountPaise)
        assertEquals("Rent", controller.pay.value?.categoryName)
    }

    @Test
    fun dismissingThePaymentDialogWritesNothing() = runTest {
        val fixture = Fixture()
        val controller = fixture.controller()

        controller.requestPay(TARGET)
        controller.dismissPay()

        assertNull(controller.pay.value)
        assertEquals(0, fixture.transactionDao.inserted.size)
    }

    @Test
    fun dismissingTheSkipConfirmationChangesNothing() = runTest {
        val fixture = Fixture()
        val controller = fixture.controller()

        controller.requestSkip(TARGET)
        controller.dismissSkip()

        assertNull(controller.skip.value)
        assertEquals(RecurringOccurrenceStatus.PENDING, fixture.storedStatus())
    }

    // ------------------------------------------------------------------ bad amounts

    @Test
    fun anAmountThatIsNotMoneyIsRefusedAndNothingIsWritten() = runTest {
        val fixture = Fixture()
        val controller = fixture.controller()

        controller.requestPay(TARGET)
        controller.onPayAmountChange("0")
        controller.confirmPay()

        assertEquals(MoneyParseResult.Problem.NotPositive, controller.pay.value?.amountError)
        assertEquals(0, fixture.transactionDao.inserted.size)
        // The dialog stays open with the household's own digits still in it, so the refusal is about
        // what they typed rather than about the dialog having gone away.
        assertEquals("0", controller.pay.value?.amountInput)
    }

    @Test
    fun typingAgainClearsTheLastComplaint() = runTest {
        val fixture = Fixture()
        val controller = fixture.controller()

        controller.requestPay(TARGET)
        controller.onPayAmountChange("100.999")
        controller.confirmPay()
        assertEquals(
            MoneyParseResult.Problem.TooManyDecimalPlaces,
            controller.pay.value?.amountError,
        )

        controller.onPayAmountChange("100.99")

        assertNull(controller.pay.value?.amountError)
    }

    // --------------------------------------------------------------- paying for real

    @Test
    fun confirmingRecordsExactlyOneExpenseDatedTheOccurrenceAndNamedAfterTheSchedule() = runTest {
        val fixture = Fixture()
        val controller = fixture.controller()

        controller.requestPay(TARGET)
        controller.confirmPay()

        val expense = fixture.transactionDao.inserted.single()

        assertEquals(TransactionType.EXPENSE, expense.type)
        assertEquals(1_500_000L, expense.amountPaise)
        assertEquals(RENT_ID, expense.categoryId)
        // The entry belongs to the day the bill was due, not to the day it was typed in, so a payment
        // caught up three weeks late lands in the month it was for.
        assertEquals(DUE_DATE, expense.transactionDate)
        assertEquals("Shop rent", expense.description)

        val occurrence = fixture.storedOccurrence()
        assertEquals(RecurringOccurrenceStatus.PAID, occurrence.status)
        // The occurrence points at the row the ledger actually gave it: this is the fake's first insert,
        // so the first id it hands out.
        assertEquals(1L, occurrence.transactionId)

        // The dialog is finished with: there is nothing left to confirm or to complain about.
        assertNull(controller.pay.value)
    }

    @Test
    fun whatWasActuallyPaidMayDifferFromWhatWasExpected() = runTest {
        val fixture = Fixture()
        val controller = fixture.controller()

        controller.requestPay(TARGET)
        controller.onPayAmountChange("1620.50")
        controller.confirmPay()

        assertEquals(162_050L, fixture.transactionDao.inserted.single().amountPaise)
        // The occurrence keeps the figure it was scheduled for: only the ledger records what really
        // changed hands.
        assertEquals(1_500_000L, fixture.storedOccurrence().amountPaise)
    }

    @Test
    fun aSecondTapCannotProduceASecondExpense() = runTest {
        val fixture = Fixture()
        val controller = fixture.controller()

        controller.requestPay(TARGET)
        launch { controller.confirmPay() }
        launch { controller.confirmPay() }
        advanceUntilIdle()

        assertEquals(1, fixture.transactionDao.inserted.size)

        // And a third tap after the dust settles finds the dialog already closed.
        controller.confirmPay()
        assertEquals(1, fixture.transactionDao.inserted.size)
    }

    @Test
    fun twoTapsArrivingTogetherCannotEither() = runTest {
        // With a runner that actually suspends, the first tap is provably mid-write when the second
        // arrives - so the refusal is the `isSaving` guard doing its job, not merely the fakes being
        // too fast for a race to happen.
        val fixture = Fixture(runner = YieldingTransactionRunner)
        val controller = fixture.controller()

        controller.requestPay(TARGET)
        launch { controller.confirmPay() }
        yield()
        launch { controller.confirmPay() }
        advanceUntilIdle()

        assertEquals(1, fixture.transactionDao.inserted.size)
    }

    // --------------------------------------------------- refusals that arrive late

    @Test
    fun anOccurrenceThatIsAlreadySettledIsSaidSoAndWritesNothing() = runTest {
        val fixture = Fixture(
            occurrences = listOf(
                occurrence(id = OCCURRENCE_ID, status = RecurringOccurrenceStatus.PAID, transactionId = 3L),
            ),
        )
        val controller = fixture.controller()

        controller.requestPay(TARGET)
        controller.confirmPay()

        assertEquals(SettleFailure.AlreadySettled, controller.pay.value?.failure)
        assertEquals(0, fixture.transactionDao.inserted.size)
        // Still on screen: the household is owed the sentence, not a dialog that silently vanished.
        assertNotNull(controller.pay.value)
    }

    @Test
    fun anOccurrenceThatIsGoneIsSaidSo() = runTest {
        val fixture = Fixture(occurrences = emptyList())
        val controller = fixture.controller()

        controller.requestPay(TARGET)
        controller.confirmPay()

        assertEquals(SettleFailure.OccurrenceNotFound, controller.pay.value?.failure)
        assertEquals(0, fixture.transactionDao.inserted.size)
    }

    @Test
    fun aFailedWriteIsReportedAndKeepsTheTypedFigure() = runTest {
        // No categories at all, so the repository's own rule - a transaction must reference an existing
        // category - refuses the write. Nothing can produce this through the app's screens, which is why
        // it is worth proving that it fails safely even so.
        val fixture = Fixture(categories = emptyList())
        val controller = fixture.controller()

        controller.requestPay(TARGET)
        controller.onPayAmountChange("1620.50")
        controller.confirmPay()

        assertTrue(controller.pay.value?.failure is SettleFailure.Failed)
        assertEquals(0, fixture.transactionDao.inserted.size)
        assertEquals("1620.50", controller.pay.value?.amountInput)
        assertEquals(RecurringOccurrenceStatus.PENDING, fixture.storedStatus())
    }

    @Test
    fun aDialogBeingSavedRefusesToBeDismissed() = runTest {
        val fixture = Fixture(runner = YieldingTransactionRunner)
        val controller = fixture.controller()

        controller.requestPay(TARGET)
        launch { controller.confirmPay() }
        yield()

        // The write is in flight, so closing the dialog would hide the outcome that is about to arrive.
        controller.dismissPay()
        assertNotNull(controller.pay.value)

        advanceUntilIdle()
        assertNull(controller.pay.value)
    }

    // ------------------------------------------------------------------------ skipping

    @Test
    fun skippingRecordsNoExpenseAndKeepsTheOccurrence() = runTest {
        val fixture = Fixture()
        val controller = fixture.controller()

        controller.requestSkip(TARGET)
        controller.confirmSkip()

        assertNull(controller.skip.value)
        assertEquals(0, fixture.transactionDao.inserted.size)

        // Kept, so it can never be generated again, and marked, so the screen can say what became of it.
        val occurrence = fixture.storedOccurrence()
        assertEquals(RecurringOccurrenceStatus.SKIPPED, occurrence.status)
        assertNull(occurrence.transactionId)
    }

    @Test
    fun skippingSomethingAlreadySettledIsSaidSo() = runTest {
        val fixture = Fixture(
            occurrences = listOf(occurrence(id = OCCURRENCE_ID, status = RecurringOccurrenceStatus.SKIPPED)),
        )
        val controller = fixture.controller()

        controller.requestSkip(TARGET)
        controller.confirmSkip()

        assertEquals(SettleFailure.AlreadySettled, controller.skip.value?.failure)
        assertNotNull(controller.skip.value)
    }

    @Test
    fun aFailedSkipIsReportedRatherThanPretendedAway() = runTest {
        val fixture = Fixture(occurrences = emptyList())
        val controller = fixture.controller()

        controller.requestSkip(TARGET)
        controller.confirmSkip()

        assertEquals(SettleFailure.OccurrenceNotFound, controller.skip.value?.failure)
    }

    // ------------------------------------------------------------------------ fixtures

    private fun Fixture.storedStatus(): RecurringOccurrenceStatus = storedOccurrence().status

    private fun Fixture.storedOccurrence(): RecurringOccurrenceEntity =
        occurrenceRows().single()

    private fun Fixture.occurrenceRows(): List<RecurringOccurrenceEntity> = recurringDao.storedOccurrences

    private fun Fixture.controller(): OccurrenceActionsController =
        OccurrenceActionsController(repository, instant = { NOW })

    /**
     * The data layer over in-memory fakes, wired the way `HomeFinanceApp` wires the real one.
     *
     * @param categories the categories that exist. Passing none is how a test reaches the repository's
     *   "that category is not there" refusal without corrupting a database to do it.
     */
    private class Fixture(
        schedules: List<RecurringScheduleEntity> = listOf(schedule()),
        occurrences: List<RecurringOccurrenceEntity> = listOf(occurrence(id = OCCURRENCE_ID)),
        categories: List<CategoryEntity> = DEFAULT_CATEGORIES,
        runner: TransactionRunner = DirectTransactionRunner,
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
            generator = RecurrenceGenerator(recurringDao, runner),
            transactionRunner = runner,
        )
    }

    private companion object {

        const val OCCURRENCE_ID = 7L
        const val RENT_ID = 10L

        val DUE_DATE: LocalDate = LocalDate.of(2026, 10, 5)
        val NOW: Instant = Instant.parse("2026-10-20T04:30:00Z")

        val TARGET = OccurrenceToSettle(
            occurrenceId = OCCURRENCE_ID,
            scheduleName = "Shop rent",
            categoryName = "Rent",
            type = TransactionType.EXPENSE,
            dueDate = DUE_DATE,
            amountPaise = 1_500_000L,
        )

        val DEFAULT_CATEGORIES: List<CategoryEntity> = listOf(
            CategoryEntity.create("Rent", TransactionType.EXPENSE).copy(id = RENT_ID),
        )

        fun schedule(): RecurringScheduleEntity = RecurringScheduleEntity(
            id = 1L,
            name = "Shop rent",
            description = null,
            categoryId = RENT_ID,
            amountPaise = 1_500_000L,
            frequency = RecurrenceFrequency.MONTHLY,
            intervalCount = 1,
            startDate = DUE_DATE,
            endDate = null,
            status = RecurringScheduleStatus.ACTIVE,
            generatedThrough = DUE_DATE,
            createdAt = NOW,
            updatedAt = NOW,
        )

        fun occurrence(
            id: Long,
            status: RecurringOccurrenceStatus = RecurringOccurrenceStatus.PENDING,
            transactionId: Long? = null,
        ): RecurringOccurrenceEntity = RecurringOccurrenceEntity(
            id = id,
            scheduleId = 1L,
            dueDate = DUE_DATE,
            amountPaise = 1_500_000L,
            status = status,
            transactionId = transactionId,
            createdAt = NOW,
            updatedAt = NOW,
        )
    }
}
