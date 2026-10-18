package com.homefinance.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homefinance.app.data.local.HomeFinanceDatabase
import com.homefinance.app.data.local.RoomTransactionRunner
import com.homefinance.app.data.local.TransactionRunner
import com.homefinance.app.data.local.deleteTestDatabase
import com.homefinance.app.data.local.isolatedFileDatabase
import com.homefinance.app.data.model.RecurrenceFrequency
import com.homefinance.app.data.model.RecurringOccurrenceStatus
import com.homefinance.app.data.model.RecurringScheduleStatus
import com.homefinance.app.data.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The recurring repository against a **real** Room database on disk, wired the way
 * [com.homefinance.app.HomeFinanceApp] wires it.
 *
 * ### What only this class can show
 *
 * `RecurringRepositoryTest` runs the same rules against fakes in milliseconds, and it is the right place
 * for the rule *logic*. But the recurring feature's central promise is a **transactional** one: recording a
 * payment writes an expense into the ledger *and* links the occurrence to it, and no failure may leave one
 * without the other. A fake cannot demonstrate that - it has no transaction to roll back - so the
 * atomicity claims are made here, against real SQLite, with a [TransactionRunner] that fails *inside* the
 * real transaction on purpose.
 *
 * The same argument applies to "exactly one expense per paid occurrence" under a repeated tap and under two
 * genuinely concurrent taps: both are settled by a real conditional `UPDATE` inside a real transaction,
 * which is the only arrangement in which the loser can be told to roll its own write back.
 *
 * `today` is pinned to 2026-10-20 throughout, and the dates each test expects are worked out from that day,
 * so nothing here depends on when the suite is run. The planning horizon is ninety days, which is why a
 * monthly schedule starting on the 5th is expected to reach the 5th of January.
 */
@RunWith(AndroidJUnit4::class)
class RecurringRepositoryDatabaseIntegrationTest {

    private lateinit var database: HomeFinanceDatabase
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var transactionRepository: TransactionRepository
    private lateinit var recurringRepository: RecurringRepository

    private val today = LocalDate.of(2026, 10, 20)
    private val now = Instant.parse("2026-10-20T06:30:00Z")

    @Before
    fun setUp() {
        deleteTestDatabase()
        database = isolatedFileDatabase()
        categoryRepository = CategoryRepository(database.categoryDao())
        transactionRepository = TransactionRepository(
            transactionDao = database.transactionDao(),
            categoryDao = database.categoryDao(),
        )
        recurringRepository = repositoryOver(database, RoomTransactionRunner(database))
    }

    @After
    fun tearDown() {
        database.close()
        deleteTestDatabase()
    }

    // ------------------------------------------------ a schedule is not money

    @Test
    fun creatingAScheduleWritesNoExpenseAndMovesNoTotal() {
        runBlocking {
            val categoryId = expenseCategory()
            val scheduleId = createSchedule(categoryId, startDate = LocalDate.of(2026, 1, 5))

            assertNotNull(recurringRepository.observeSchedule(scheduleId).first())
            // The first thing the feature must never do. A schedule is a plan; until the household says a
            // date was paid, the ledger is untouched and every total in the app is unchanged.
            assertEquals(0, transactions())
            assertEquals(0, transactionsFor(categoryId))
        }
    }

    @Test
    fun aGeneratedOccurrenceIsAnExpectationAndNotAnExpense() {
        runBlocking {
            val categoryId = expenseCategory()
            val scheduleId = createSchedule(
                categoryId = categoryId,
                name = "Milk",
                amountPaise = 6_000L,
                frequency = RecurrenceFrequency.DAILY,
                startDate = today.minusDays(3),
            )

            // Four days of milk have fallen due and been generated. Not one of them is money yet: those are
            // expectations the household has not acted on, and marking them paid because a date went by
            // would be inventing expenses.
            assertTrue(recurringRepository.observeOccurrences(scheduleId).first().isNotEmpty())
            assertEquals(0, transactions())
        }
    }

    @Test
    fun generatingTwiceCreatesNothingTheSecondTime() {
        runBlocking {
            val categoryId = expenseCategory()
            val scheduleId = createSchedule(categoryId, startDate = today.minusMonths(2))

            val occurrences = recurringRepository.observeOccurrences(scheduleId).first().size
            assertTrue(occurrences > 0)

            // The progress marker is an optimisation, and the unique index is the authority: this call is
            // safe however many times it runs and wherever it starts from.
            assertEquals(0, recurringRepository.generateDueOccurrences(today, now))
            assertEquals(occurrences, recurringRepository.observeOccurrences(scheduleId).first().size)
        }
    }

    @Test
    fun generationCatchesUpAMissedPeriodWithoutWritingAnExpense() {
        runBlocking {
            val categoryId = expenseCategory()
            // Started six months ago and never opened since: every monthly date in between must be there
            // when the household finally looks, and the first to be generated is the one five months back,
            // not today's.
            val scheduleId = createSchedule(categoryId, startDate = LocalDate.of(2026, 4, 5))

            assertEquals(
                listOf(
                    LocalDate.of(2026, 4, 5),
                    LocalDate.of(2026, 5, 5),
                    LocalDate.of(2026, 6, 5),
                    LocalDate.of(2026, 7, 5),
                    LocalDate.of(2026, 8, 5),
                    LocalDate.of(2026, 9, 5),
                    LocalDate.of(2026, 10, 5),
                    LocalDate.of(2026, 11, 5),
                    LocalDate.of(2026, 12, 5),
                    LocalDate.of(2027, 1, 5),
                ),
                dueDates(scheduleId),
            )
            assertEquals(0, transactions())
        }
    }

    @Test
    fun aPausedScheduleGeneratesNothingAndKeepsWhatItHad() {
        runBlocking {
            val categoryId = expenseCategory()
            val scheduleId = createSchedule(categoryId, startDate = today.minusMonths(1))
            val before = recurringRepository.observeOccurrences(scheduleId).first().size

            assertTrue(recurringRepository.pause(scheduleId, now))
            assertEquals(0, recurringRepository.generateDueOccurrences(today, now))

            // Pausing does not delete. The dates already generated stay usable - the household may still
            // want to pay or skip one - and nothing new appears.
            assertEquals(before, recurringRepository.observeOccurrences(scheduleId).first().size)
        }
    }

    @Test
    fun aStoppedScheduleGeneratesNothing() {
        runBlocking {
            val categoryId = expenseCategory()
            val scheduleId = createSchedule(categoryId, startDate = today.minusMonths(1))
            val before = recurringRepository.observeOccurrences(scheduleId).first().size

            assertTrue(recurringRepository.retire(scheduleId, now))

            assertEquals(0, recurringRepository.generateDueOccurrences(today, now))
            assertEquals(before, recurringRepository.observeOccurrences(scheduleId).first().size)
            assertEquals(
                RecurringScheduleStatus.RETIRED,
                recurringRepository.observeSchedule(scheduleId).first()!!.status,
            )
        }
    }

    // --------------------------------------------------------- what is due when

    @Test
    fun generationStopsAtTheEndDateWhichIsInclusive() {
        runBlocking {
            val categoryId = expenseCategory()
            val scheduleId = createSchedule(
                categoryId = categoryId,
                name = "Insurance",
                startDate = LocalDate.of(2026, 8, 5),
                endDate = LocalDate.of(2026, 10, 5),
            )

            // The 5th of October is the last date that may fall; the 5th of November never appears, however
            // many times generation runs and however far the horizon reaches past it.
            assertEquals(
                listOf(
                    LocalDate.of(2026, 8, 5),
                    LocalDate.of(2026, 9, 5),
                    LocalDate.of(2026, 10, 5),
                ),
                dueDates(scheduleId),
            )
        }
    }

    @Test
    fun generationReachesThePlanningHorizonBeyondToday() {
        runBlocking {
            val categoryId = expenseCategory()
            val scheduleId = createSchedule(
                categoryId = categoryId,
                name = "Milk",
                amountPaise = 6_000L,
                frequency = RecurrenceFrequency.DAILY,
                startDate = today,
            )

            val dates = recurringRepository.observeOccurrences(scheduleId).first().map { it.dueDate }

            // Generated a little ahead of today on purpose: the point of the horizon is that the list can
            // say when a bill is next due without waiting for the day to arrive. Ninety days is the bound -
            // and a bound rather than a promise, because generation runs again on every visit.
            assertEquals(today, dates.min())
            assertEquals(today.plusDays(RecurrenceGenerator.PLANNING_HORIZON_DAYS), dates.max())
            assertEquals(RecurrenceGenerator.PLANNING_HORIZON_DAYS + 1, dates.size.toLong())
        }
    }

    @Test
    fun monthEndClampsToTheLastDayOfAShortMonthWithoutDrifting() {
        runBlocking {
            val categoryId = expenseCategory()
            val scheduleId = createSchedule(
                categoryId = categoryId,
                name = "Broadband",
                startDate = LocalDate.of(2026, 1, 31),
                endDate = LocalDate.of(2026, 5, 31),
            )

            // Anchored on the 31st, so February is clamped to its last day - and April is *not* the 28th,
            // because the anchor is the 31st rather than "28 days after". That is the difference between
            // calendar arithmetic and fixed-day addition, and it is what stops the date drifting a little
            // further every month.
            assertEquals(
                listOf(
                    LocalDate.of(2026, 1, 31),
                    LocalDate.of(2026, 2, 28),
                    LocalDate.of(2026, 3, 31),
                    LocalDate.of(2026, 4, 30),
                    LocalDate.of(2026, 5, 31),
                ),
                dueDates(scheduleId),
            )
        }
    }

    @Test
    fun aLeapYearFebruaryDoesNotRatchetTheAnchorOffTheTwentyNinth() {
        runBlocking {
            val categoryId = expenseCategory()
            val scheduleId = recurringRepository.createSchedule(
                name = "Insurance",
                categoryId = categoryId,
                amountPaise = 100_000L,
                frequency = RecurrenceFrequency.YEARLY,
                intervalCount = 1,
                startDate = LocalDate.of(2024, 2, 29),
                endDate = LocalDate.of(2028, 2, 29),
                // Read after the leap day has come round again, so the whole clamp-and-return sequence is
                // in view at once.
                today = LocalDate.of(2028, 3, 1),
                now = now,
            )

            // 2028 is a leap year and the anchor is still the 29th. Clamping to the 28th in the intervening
            // years must not have moved it.
            assertEquals(
                listOf(
                    LocalDate.of(2024, 2, 29),
                    LocalDate.of(2025, 2, 28),
                    LocalDate.of(2026, 2, 28),
                    LocalDate.of(2027, 2, 28),
                    LocalDate.of(2028, 2, 29),
                ),
                dueDates(scheduleId),
            )
        }
    }

    @Test
    fun aSkippedOccurrenceIsNeverGeneratedAgain() {
        runBlocking {
            val categoryId = expenseCategory()
            val scheduleId = createSchedule(categoryId, startDate = today.minusMonths(1))
            val first = recurringRepository.observeOccurrences(scheduleId).first()
                .minByOrNull { it.dueDate }!!

            assertEquals(SkipOutcome.Skipped, recurringRepository.skipOccurrence(first.id, now))

            recurringRepository.generateDueOccurrences(today, now)
            recurringRepository.generateDueOccurrences(today, now)

            val stored = recurringRepository.observeOccurrences(scheduleId).first()
                .single { it.id == first.id }
            assertEquals(RecurringOccurrenceStatus.SKIPPED, stored.status)
            // Exactly one row for that date, and it is the skipped one. The row is kept precisely so the
            // date cannot come round again as a surprise.
            assertEquals(
                1,
                recurringRepository.observeOccurrences(scheduleId).first()
                    .count { it.dueDate == first.dueDate },
            )
            assertEquals(0, transactions())
        }
    }

    @Test
    fun aPaidOccurrenceIsNeverGeneratedAgain() {
        runBlocking {
            val categoryId = expenseCategory()
            val scheduleId = createSchedule(categoryId, startDate = today.minusMonths(1))
            val first = recurringRepository.observeOccurrences(scheduleId).first()
                .minByOrNull { it.dueDate }!!

            val paid = recurringRepository.markOccurrencePaid(first.id, first.amountPaise, now)
            assertTrue(paid is MarkPaidOutcome.Paid)

            recurringRepository.generateDueOccurrences(today, now)

            assertEquals(
                1,
                recurringRepository.observeOccurrences(scheduleId).first()
                    .count { it.dueDate == first.dueDate },
            )
            assertEquals(1, transactions())
        }
    }

    // ------------------------------------------------------------ paying a bill

    @Test
    fun payingAnOccurrenceWritesExactlyOneExpenseLinkedToIt() {
        runBlocking {
            val categoryId = expenseCategory()
            val scheduleId = createSchedule(categoryId, startDate = today.minusMonths(1))
            val occurrence = recurringRepository.observeOccurrences(scheduleId).first()
                .minByOrNull { it.dueDate }!!

            val paid = recurringRepository.markOccurrencePaid(
                occurrenceId = occurrence.id,
                amountPaise = occurrence.amountPaise,
                now = now,
            ) as MarkPaidOutcome.Paid

            val stored = recurringRepository.observeOccurrences(scheduleId).first()
                .single { it.id == occurrence.id }
            assertEquals(RecurringOccurrenceStatus.PAID, stored.status)
            assertEquals(paid.transactionId, stored.transactionId)

            // One expense, and it is the one the occurrence points at. No second row, no orphan.
            assertEquals(1, transactions())
            assertNotNull(transactionRepository.getById(paid.transactionId))
            assertEquals(1, transactionsFor(categoryId))
        }
    }

    @Test
    fun theRecordedExpenseIsDatedTheOccurrenceAndFiledUnderTheSchedulesCategory() {
        runBlocking {
            val categoryId = expenseCategory()
            val scheduleId = createSchedule(categoryId, startDate = today.minusMonths(1))
            val occurrence = recurringRepository.observeOccurrences(scheduleId).first()
                .minByOrNull { it.dueDate }!!

            val paid = recurringRepository.markOccurrencePaid(
                occurrenceId = occurrence.id,
                amountPaise = occurrence.amountPaise,
                now = now,
            ) as MarkPaidOutcome.Paid

            val transaction = transactionRepository.getById(paid.transactionId)!!
            // The bill's own date, not the day it was recorded: rent paid late is still October's rent, and
            // the month it belongs to is the month the household is looking at.
            assertEquals(occurrence.dueDate, transaction.transactionDate)
            assertEquals(TransactionType.EXPENSE, transaction.type)
            assertEquals(categoryId, transaction.categoryId)
            assertEquals(occurrence.amountPaise, transaction.amountPaise)
            assertEquals("Shop rent", transaction.description)
        }
    }

    @Test
    fun anIncomeScheduleTurnsIntoIncomeWhenItIsPaid() {
        runBlocking {
            val categoryId = incomeCategory()
            val scheduleId = createSchedule(
                categoryId = categoryId,
                name = "Salary",
                amountPaise = 4_500_000L,
                startDate = today.minusMonths(1),
            )
            val occurrence = recurringRepository.observeOccurrences(scheduleId).first()
                .minByOrNull { it.dueDate }!!

            val paid = recurringRepository.markOccurrencePaid(
                occurrenceId = occurrence.id,
                amountPaise = occurrence.amountPaise,
                now = now,
            ) as MarkPaidOutcome.Paid

            // Nothing anywhere on the schedule says "income": the entry's direction is read from the
            // category it is filed under, which is what lets a schedule be either without a second
            // column to disagree with the category.
            val transaction = transactionRepository.getById(paid.transactionId)!!
            assertEquals(TransactionType.INCOME, transaction.type)
            assertEquals(categoryId, transaction.categoryId)
            assertEquals(4_500_000L, transaction.amountPaise)
        }
    }

    @Test
    fun aUnitPriceIsStoredOnTheScheduleAndNeverCopiedOntoTheEntry() {
        runBlocking {
            val categoryId = expenseCategory(name = "Milk")
            val scheduleId = createSchedule(
                categoryId = categoryId,
                name = "Milk",
                amountPaise = 6_000L,
                unit = "litre",
                ratePaise = 6_000L,
                startDate = today.minusMonths(1),
            )
            val occurrence = recurringRepository.observeOccurrences(scheduleId).first()
                .minByOrNull { it.dueDate }!!

            // The plan keeps the price of one unit, because that is what the household told it.
            val stored = recurringRepository.observeSchedule(scheduleId).first()!!
            assertEquals("litre", stored.unit)
            assertEquals(6_000L, stored.ratePaise)

            val paid = recurringRepository.markOccurrencePaid(
                occurrenceId = occurrence.id,
                amountPaise = occurrence.amountPaise,
                now = now,
            ) as MarkPaidOutcome.Paid

            // The entry does not: one rate with no quantity beside it would be half of a sum that does
            // not multiply out, and the ledger's own rule is that quantity and rate are a pair.
            val transaction = transactionRepository.getById(paid.transactionId)!!
            assertEquals(6_000L, transaction.amountPaise)
            assertNull(transaction.quantityThousandths)
            assertNull(transaction.ratePaise)
        }
    }

    @Test
    fun theAmountPaidMayDifferFromWhatWasExpected() {
        runBlocking {
            val categoryId = expenseCategory()
            val scheduleId = createSchedule(categoryId, startDate = today.minusMonths(1))
            val occurrence = recurringRepository.observeOccurrences(scheduleId).first()
                .minByOrNull { it.dueDate }!!

            // The household paid a little more this month. The occurrence was *expected* to be the old
            // figure and keeps it; the expense records what actually left the account.
            val paid = recurringRepository.markOccurrencePaid(
                occurrenceId = occurrence.id,
                amountPaise = 1_600_000L,
                now = now,
            ) as MarkPaidOutcome.Paid

            assertEquals(1_600_000L, transactionRepository.getById(paid.transactionId)!!.amountPaise)
            assertEquals(
                1_500_000L,
                recurringRepository.observeOccurrences(scheduleId).first()
                    .single { it.id == occurrence.id }.amountPaise,
            )
        }
    }

    @Test
    fun payingTheSameOccurrenceTwiceLeavesOneExpense() {
        runBlocking {
            val categoryId = expenseCategory()
            val scheduleId = createSchedule(categoryId, startDate = today.minusMonths(1))
            val occurrence = recurringRepository.observeOccurrences(scheduleId).first()
                .minByOrNull { it.dueDate }!!

            val first = recurringRepository.markOccurrencePaid(occurrence.id, 1_500_000L, now)
            val second = recurringRepository.markOccurrencePaid(occurrence.id, 1_500_000L, now)

            assertTrue(first is MarkPaidOutcome.Paid)
            // A repeated tap - the screen did not change, the household taps again - must not become a
            // second rent payment.
            assertEquals(MarkPaidOutcome.AlreadySettled, second)
            assertEquals(1, transactions())
        }
    }

    @Test
    fun twoConcurrentPaymentsLeaveExactlyOneExpense() {
        runBlocking {
            val categoryId = expenseCategory()
            val scheduleId = createSchedule(categoryId, startDate = today.minusMonths(1))
            val occurrence = recurringRepository.observeOccurrences(scheduleId).first()
                .minByOrNull { it.dueDate }!!

            // Two taps genuinely at once, on two dispatcher threads. Room serialises the two transactions,
            // so one links the occurrence and the other finds it no longer pending and rolls its own
            // expense back. This is the case the conditional UPDATE exists for.
            val outcomes = withContext(Dispatchers.IO) {
                listOf(
                    async { recurringRepository.markOccurrencePaid(occurrence.id, 1_500_000L, now) },
                    async { recurringRepository.markOccurrencePaid(occurrence.id, 1_500_000L, now) },
                ).awaitAll()
            }

            assertEquals(1, outcomes.count { it is MarkPaidOutcome.Paid })
            assertEquals(1, outcomes.count { it == MarkPaidOutcome.AlreadySettled })
            assertEquals(1, transactions())
            assertEquals(
                RecurringOccurrenceStatus.PAID,
                recurringRepository.observeOccurrences(scheduleId).first()
                    .single { it.id == occurrence.id }.status,
            )
        }
    }

    @Test
    fun aFailureInsideTheTransactionLeavesTheOccurrencePendingAndTheLedgerEmpty() {
        runBlocking {
            val categoryId = expenseCategory()
            val scheduleId = createSchedule(categoryId, startDate = today.minusMonths(1))
            val occurrence = recurringRepository.observeOccurrences(scheduleId).first()
                .minByOrNull { it.dueDate }!!

            // The real runner, wrapped so the block fails *after it has written*: the only way to show the
            // expense was really written inside the transaction and really rolled back, rather than never
            // having been written at all.
            val failing = FailingInsideTransactionRunner(
                real = RoomTransactionRunner(database),
                database = database,
                occurrenceId = occurrence.id,
            )
            val repository = repositoryOver(database, failing)

            assertThrows(InjectedFailure::class.java) {
                runBlocking { repository.markOccurrencePaid(occurrence.id, 1_500_000L, now) }
            }

            // Proof the block ran to completion: inside the transaction, the expense existed and the
            // occurrence pointed at it.
            assertTrue("the expense was never written inside the transaction", failing.expenseWasWritten())
            assertTrue("the occurrence was never linked inside the transaction", failing.occurrenceWasLinked())

            // And proof the rollback worked: afterwards the household has no expense, no link, and a bill
            // still waiting to be paid. A half-written state - an expense nothing points at - is exactly
            // what the transaction exists to make impossible.
            assertNull(transactionRepository.getById(failing.writtenTransactionId!!))
            assertEquals(0, transactions())
            val stored = recurringRepository.observeOccurrences(scheduleId).first()
                .single { it.id == occurrence.id }
            assertEquals(RecurringOccurrenceStatus.PENDING, stored.status)
            assertNull(stored.transactionId)
        }
    }

    @Test
    fun aPaymentOfZeroIsRefusedBeforeAnythingIsWritten() {
        runBlocking {
            val categoryId = expenseCategory()
            val scheduleId = createSchedule(categoryId, startDate = today.minusMonths(1))
            val occurrence = recurringRepository.observeOccurrences(scheduleId).first()
                .minByOrNull { it.dueDate }!!

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { recurringRepository.markOccurrencePaid(occurrence.id, 0L, now) }
            }

            assertEquals(0, transactions())
            assertEquals(
                RecurringOccurrenceStatus.PENDING,
                recurringRepository.observeOccurrences(scheduleId).first()
                    .single { it.id == occurrence.id }.status,
            )
        }
    }

    // --------------------------------------------------------------- skipping

    @Test
    fun skippingWritesNoExpenseAndKeepsTheOccurrence() {
        runBlocking {
            val categoryId = expenseCategory()
            val scheduleId = createSchedule(categoryId, startDate = today.minusMonths(1))
            val occurrence = recurringRepository.observeOccurrences(scheduleId).first()
                .minByOrNull { it.dueDate }!!

            assertEquals(SkipOutcome.Skipped, recurringRepository.skipOccurrence(occurrence.id, now))

            assertEquals(0, transactions())
            val stored = recurringRepository.observeOccurrences(scheduleId).first()
                .single { it.id == occurrence.id }
            // Differs from a deletion: the row is kept, so the date can never come round again as a
            // surprise, and the household's totals never saw it at all.
            assertEquals(RecurringOccurrenceStatus.SKIPPED, stored.status)
            assertNull(stored.transactionId)
        }
    }

    @Test
    fun skippingTwiceReportsTheSettledOccurrenceTheSecondTime() {
        runBlocking {
            val categoryId = expenseCategory()
            val scheduleId = createSchedule(categoryId, startDate = today.minusMonths(1))
            val occurrence = recurringRepository.observeOccurrences(scheduleId).first()
                .minByOrNull { it.dueDate }!!

            assertEquals(SkipOutcome.Skipped, recurringRepository.skipOccurrence(occurrence.id, now))
            assertEquals(
                SkipOutcome.AlreadySettled,
                recurringRepository.skipOccurrence(occurrence.id, now),
            )
        }
    }

    @Test
    fun aPaidOccurrenceCannotBeSkippedAndASkippedOneCannotBePaid() {
        runBlocking {
            val categoryId = expenseCategory()
            val scheduleId = createSchedule(categoryId, startDate = today.minusMonths(1))
            val occurrences = recurringRepository.observeOccurrences(scheduleId).first()
                .sortedBy { it.dueDate }
            val toPay = occurrences[0]
            val toSkip = occurrences[1]

            recurringRepository.markOccurrencePaid(toPay.id, toPay.amountPaise, now)
            assertEquals(SkipOutcome.AlreadySettled, recurringRepository.skipOccurrence(toPay.id, now))

            recurringRepository.skipOccurrence(toSkip.id, now)
            assertEquals(
                MarkPaidOutcome.AlreadySettled,
                recurringRepository.markOccurrencePaid(toSkip.id, toSkip.amountPaise, now),
            )

            // One expense for the one that was paid, and none for the one that was skipped.
            assertEquals(1, transactions())
        }
    }

    @Test
    fun settlingAnOccurrenceThatDoesNotExistIsReportedRatherThanFailing() {
        runBlocking {
            assertEquals(
                MarkPaidOutcome.OccurrenceNotFound,
                recurringRepository.markOccurrencePaid(9_999L, 100L, now),
            )
            assertEquals(SkipOutcome.OccurrenceNotFound, recurringRepository.skipOccurrence(9_999L, now))
        }
    }

    // --------------------------------------------------------- the flow emits

    @Test
    fun theScheduleFlowCarriesTheNextDueDateAndThenNoDateOnceItIsPaid() {
        runBlocking {
            val categoryId = expenseCategory()
            val scheduleId = createSchedule(
                categoryId = categoryId,
                startDate = today.minusMonths(1),
                endDate = today.minusMonths(1),
            )
            val occurrence = recurringRepository.observeOccurrences(scheduleId).first().single()

            assertEquals(occurrence.dueDate, recurringRepository.observeSchedule(scheduleId).first()!!.nextDueDate)

            recurringRepository.markOccurrencePaid(occurrence.id, occurrence.amountPaise, now)

            // The screens read this query, so a payment recorded anywhere is reflected everywhere without
            // any screen refreshing anything.
            assertNull(recurringRepository.observeSchedule(scheduleId).first()!!.nextDueDate)
            assertEquals(
                RecurringOccurrenceStatus.PAID,
                recurringRepository.observeOccurrences(scheduleId).first().single().status,
            )
        }
    }

    // -------------------------------------------------------- editing a rule

    @Test
    fun editingAScheduleKeepsItsIdentityAndItsPaidHistory() {
        runBlocking {
            val categoryId = expenseCategory()
            val scheduleId = createSchedule(categoryId, startDate = LocalDate.of(2026, 8, 5))
            val before = recurringRepository.observeSchedule(scheduleId).first()!!
            val paid = recurringRepository.observeOccurrences(scheduleId).first()
                .minByOrNull { it.dueDate }!!
            recurringRepository.markOccurrencePaid(paid.id, paid.amountPaise, now)

            assertTrue(
                recurringRepository.updateSchedule(
                    id = scheduleId,
                    name = "Shop rent (revised)",
                    categoryId = categoryId,
                    amountPaise = 1_600_000L,
                    frequency = RecurrenceFrequency.MONTHLY,
                    intervalCount = 1,
                    startDate = LocalDate.of(2026, 8, 5),
                    endDate = null,
                    today = today,
                    now = now,
                ),
            )

            val after = recurringRepository.observeSchedule(scheduleId).first()!!
            assertEquals(scheduleId, after.id)
            assertEquals(before.createdAt, after.createdAt)

            // The paid occurrence keeps what it was expected to be and the expense it was paid as. An edit
            // changes what happens next, never what already happened.
            val storedPaid = recurringRepository.observeOccurrences(scheduleId).first()
                .single { it.id == paid.id }
            assertEquals(RecurringOccurrenceStatus.PAID, storedPaid.status)
            assertEquals(1_500_000L, storedPaid.amountPaise)
            assertEquals(1_500_000L, transactionRepository.getById(storedPaid.transactionId!!)!!.amountPaise)
            assertEquals(1, transactions())
        }
    }

    @Test
    fun editingWithoutMovingTheStartDateCreatesNoNewOccurrences() {
        runBlocking {
            val categoryId = expenseCategory()
            val scheduleId = createSchedule(categoryId, startDate = LocalDate.of(2026, 8, 5))
            val before = dueDates(scheduleId)

            assertTrue(
                recurringRepository.updateSchedule(
                    id = scheduleId,
                    name = "Shop rent",
                    categoryId = categoryId,
                    amountPaise = 1_600_000L,
                    frequency = RecurrenceFrequency.MONTHLY,
                    intervalCount = 1,
                    startDate = LocalDate.of(2026, 8, 5),
                    endDate = null,
                    today = today,
                    now = now,
                ),
            )

            assertEquals(
                before,
                dueDates(scheduleId),
            )
        }
    }

    @Test
    fun movingTheStartDateEarlierFillsInTheBackdatedPeriodThatIsMissing() {
        runBlocking {
            val categoryId = expenseCategory()
            val scheduleId = createSchedule(categoryId, startDate = LocalDate.of(2026, 8, 5))

            assertTrue(
                recurringRepository.updateSchedule(
                    id = scheduleId,
                    name = "Shop rent",
                    categoryId = categoryId,
                    amountPaise = 1_500_000L,
                    frequency = RecurrenceFrequency.MONTHLY,
                    intervalCount = 1,
                    startDate = LocalDate.of(2026, 6, 5),
                    endDate = null,
                    today = today,
                    now = now,
                ),
            )

            // The dates the widened rule implies and that were not there before are filled in, and the ones
            // already generated are not duplicated.
            assertEquals(
                listOf(
                    LocalDate.of(2026, 6, 5),
                    LocalDate.of(2026, 7, 5),
                    LocalDate.of(2026, 8, 5),
                    LocalDate.of(2026, 9, 5),
                    LocalDate.of(2026, 10, 5),
                    LocalDate.of(2026, 11, 5),
                    LocalDate.of(2026, 12, 5),
                    LocalDate.of(2027, 1, 5),
                ),
                dueDates(scheduleId),
            )
        }
    }

    @Test
    fun editingAScheduleThatIsGoneWritesNothingAndReportsSo() {
        runBlocking {
            val categoryId = expenseCategory()

            // No replacement is inserted: a stale form must not resurrect a schedule under a new id, which
            // would silently give the household a second copy of the same bill.
            assertFalse(
                recurringRepository.updateSchedule(
                    id = 9_999L,
                    name = "Shop rent",
                    categoryId = categoryId,
                    amountPaise = 1_500_000L,
                    frequency = RecurrenceFrequency.MONTHLY,
                    intervalCount = 1,
                    startDate = today,
                    endDate = null,
                    today = today,
                    now = now,
                ),
            )
            assertEquals(0, recurringRepository.observeSchedules().first().size)
        }
    }

    // ------------------------------------------------------- pause and resume

    @Test
    fun pausingAndResumingRepeatedlyCreatesNoDuplicates() {
        runBlocking {
            val categoryId = expenseCategory()
            val scheduleId = createSchedule(categoryId, startDate = LocalDate.of(2026, 8, 5))

            repeat(3) {
                assertTrue(recurringRepository.pause(scheduleId, now))
                assertTrue(recurringRepository.resume(scheduleId, today, now))
            }

            val dates = dueDates(scheduleId)
            assertEquals(dates.distinct(), dates)
            assertEquals(0, transactions())
        }
    }

    @Test
    fun resumingDoesNotBackFillTheDatesThatFellInsideThePause() {
        runBlocking {
            val categoryId = expenseCategory()
            // Monthly on the 5th, started the month before today, so the first generation reaches the 5th
            // of January. The household then pauses and comes back in the middle of February.
            val scheduleId = createSchedule(categoryId, startDate = LocalDate.of(2026, 9, 5))
            assertTrue(recurringRepository.pause(scheduleId, now))

            val resumeDay = today.plusDays(120)
            assertTrue(recurringRepository.resume(scheduleId, resumeDay, now))

            // Nothing was generated for the paused window. The 5th of February fell while the schedule was
            // paused, so it is not expected - and the next date is the first one after the pause ended, not a
            // pile of suddenly-overdue bills from the month that was skipped.
            assertEquals(
                listOf(
                    LocalDate.of(2026, 9, 5),
                    LocalDate.of(2026, 10, 5),
                    LocalDate.of(2026, 11, 5),
                    LocalDate.of(2026, 12, 5),
                    LocalDate.of(2027, 1, 5),
                    LocalDate.of(2027, 3, 5),
                    LocalDate.of(2027, 4, 5),
                    LocalDate.of(2027, 5, 5),
                ),
                recurringRepository.observeOccurrences(scheduleId).first().map { it.dueDate }.sorted(),
            )
        }
    }

    @Test
    fun pausingAnAlreadyPausedScheduleChangesNothingAndIsNotAnError() {
        runBlocking {
            val categoryId = expenseCategory()
            val scheduleId = createSchedule(categoryId, startDate = today.minusMonths(1))

            assertTrue(recurringRepository.pause(scheduleId, now))
            assertFalse(recurringRepository.pause(scheduleId, now))
            assertEquals(
                RecurringScheduleStatus.PAUSED,
                recurringRepository.observeSchedule(scheduleId).first()!!.status,
            )
        }
    }

    @Test
    fun anOccurrenceOfAPausedScheduleCanStillBePaid() {
        runBlocking {
            val categoryId = expenseCategory()
            val scheduleId = createSchedule(categoryId, startDate = today.minusMonths(1))
            val occurrence = recurringRepository.observeOccurrences(scheduleId).first()
                .minByOrNull { it.dueDate }!!

            recurringRepository.pause(scheduleId, now)

            // A pause stops future expectations; it does not erase a bill the household already owes, and
            // refusing to record it would leave the ledger wrong in order to make a point about the
            // schedule being over.
            assertTrue(
                recurringRepository.markOccurrencePaid(occurrence.id, occurrence.amountPaise, now)
                    is MarkPaidOutcome.Paid,
            )
            assertEquals(1, transactions())
        }
    }

    // --------------------------------------------------------- creation rules

    @Test
    fun anIncomeCategoryIsAllowedAndItsPaymentsAreFiledAsIncome() {
        runBlocking {
            val incomeCategoryId = incomeCategory("Salary")

            // A schedule is a plan, not an expense: rent comes round and so does a salary, and the
            // direction is the *category's* own type rather than a property of the schedule.
            val scheduleId = createSchedule(
                incomeCategoryId,
                name = "Salary",
                startDate = today.minusMonths(1),
            )
            val occurrence = recurringRepository.observeOccurrences(scheduleId).first()
                .minByOrNull { it.dueDate }!!

            assertTrue(
                recurringRepository.markOccurrencePaid(occurrence.id, occurrence.amountPaise, now)
                    is MarkPaidOutcome.Paid,
            )

            // The entry the payment wrote carries the category's direction, not a default: an income
            // schedule that quietly recorded an expense would corrupt every total built on top of it.
            val entry = database.transactionDao().observeAll().first().single()
            assertEquals(TransactionType.INCOME, entry.type)
            assertEquals(incomeCategoryId, entry.categoryId)
        }
    }

    @Test
    fun anExpenseCategoryIsAllowedTooBecauseTheCategoryDecidesTheDirection() {
        runBlocking {
            val categoryId = expenseCategory()

            val scheduleId = createSchedule(categoryId, startDate = today.minusMonths(1))
            val occurrence = recurringRepository.observeOccurrences(scheduleId).first()
                .minByOrNull { it.dueDate }!!

            assertTrue(
                recurringRepository.markOccurrencePaid(occurrence.id, occurrence.amountPaise, now)
                    is MarkPaidOutcome.Paid,
            )

            val entry = database.transactionDao().observeAll().first().single()
            assertEquals(TransactionType.EXPENSE, entry.type)
        }
    }

    @Test
    fun aRetiredCategoryCannotBeChosenForANewSchedule() {
        runBlocking {
            val categoryId = categoryRepository.create("Rent", TransactionType.EXPENSE)
            categoryRepository.deactivate(categoryId)

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { createSchedule(categoryId, startDate = today) }
            }
        }
    }

    @Test
    fun aCategoryThatDoesNotExistCannotBeChosen() {
        runBlocking {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { createSchedule(4_242L, startDate = today) }
            }
        }
    }

    @Test
    fun aScheduleCannotEndBeforeItStarts() {
        runBlocking {
            val categoryId = expenseCategory()

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    createSchedule(
                        categoryId = categoryId,
                        startDate = LocalDate.of(2026, 10, 5),
                        endDate = LocalDate.of(2026, 10, 4),
                    )
                }
            }
        }
    }

    @Test
    fun anIntervalOutsideWhatCanBeStoredIsRefused() {
        runBlocking {
            val categoryId = expenseCategory()

            for (interval in listOf(0, -1, 1_001)) {
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking {
                        createSchedule(categoryId = categoryId, startDate = today, intervalCount = interval)
                    }
                }
            }
        }
    }

    @Test
    fun aBlankNameAndANonPositiveAmountAreRefused() {
        runBlocking {
            val categoryId = expenseCategory()

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { createSchedule(categoryId = categoryId, startDate = today, name = "   ") }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { createSchedule(categoryId = categoryId, startDate = today, amountPaise = 0L) }
            }

            assertEquals(0, recurringRepository.observeSchedules().first().size)
        }
    }

    @Test
    fun theSchedulesNameIsTrimmedAndABlankDescriptionIsStoredAsNothing() {
        runBlocking {
            val categoryId = expenseCategory()
            val scheduleId = createSchedule(
                categoryId = categoryId,
                startDate = today,
                name = "  Shop   rent ",
                description = "   ",
            )

            val stored = recurringRepository.observeSchedule(scheduleId).first()!!
            assertEquals("Shop rent", stored.name)
            assertNull(stored.description)
        }
    }

    @Test
    fun aRetiredCategoryMayStillBeKeptByTheScheduleAlreadyFiledUnderIt() {
        runBlocking {
            val categoryId = categoryRepository.create("Rent", TransactionType.EXPENSE)
            val scheduleId = createSchedule(categoryId, startDate = today.minusMonths(1))
            categoryRepository.deactivate(categoryId)

            // The picker no longer offers the category, but a schedule must not become uneditable because a
            // category was turned off after it was written.
            assertTrue(
                recurringRepository.updateSchedule(
                    id = scheduleId,
                    name = "Shop rent",
                    categoryId = categoryId,
                    amountPaise = 1_600_000L,
                    frequency = RecurrenceFrequency.MONTHLY,
                    intervalCount = 1,
                    startDate = today.minusMonths(1),
                    endDate = null,
                    today = today,
                    now = now,
                ),
            )
        }
    }

    // ---------------------------------------------------------------- helpers

    /** The production wiring, with only the [TransactionRunner] swappable. */
    private fun repositoryOver(
        database: HomeFinanceDatabase,
        runner: TransactionRunner,
    ): RecurringRepository = RecurringRepository(
        recurringDao = database.recurringDao(),
        transactions = TransactionRepository(
            transactionDao = database.transactionDao(),
            categoryDao = database.categoryDao(),
        ),
        categories = CategoryRepository(database.categoryDao()),
        generator = RecurrenceGenerator(
            recurringDao = database.recurringDao(),
            transactionRunner = RoomTransactionRunner(database),
        ),
        transactionRunner = runner,
    )

    private suspend fun expenseCategory(name: String = "Rent"): Long =
        categoryRepository.create(name, TransactionType.EXPENSE)

    private suspend fun incomeCategory(name: String = "Salary"): Long =
        categoryRepository.create(name, TransactionType.INCOME)

    private suspend fun createSchedule(
        categoryId: Long,
        name: String = "Shop rent",
        description: String? = null,
        amountPaise: Long = 1_500_000L,
        unit: String? = null,
        ratePaise: Long? = null,
        frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
        intervalCount: Int = 1,
        startDate: LocalDate,
        endDate: LocalDate? = null,
    ): Long = recurringRepository.createSchedule(
        name = name,
        categoryId = categoryId,
        amountPaise = amountPaise,
        frequency = frequency,
        intervalCount = intervalCount,
        startDate = startDate,
        endDate = endDate,
        today = today,
        description = description,
        unit = unit,
        ratePaise = ratePaise,
        now = now,
    )

    /**
     * The dates a schedule expects, oldest first.
     *
     * `observeOccurrences` is newest-first, because that is the order the occurrence screen draws; a test
     * about *which* dates a rule produces reads better oldest-first, so the ordering is normalised here
     * rather than repeated at each call site. The descending order itself is asserted against Room in
     * `RecurringDaoTest`, which is the only place an `ORDER BY` can be checked honestly.
     */
    private suspend fun dueDates(scheduleId: Long): List<LocalDate> =
        recurringRepository.observeOccurrences(scheduleId).first().map { it.dueDate }.sorted()

    private suspend fun transactions(): Int = database.transactionDao().observeAll().first().size

    private suspend fun transactionsFor(categoryId: Long): Int =
        database.transactionDao().observeAll().first().count { it.categoryId == categoryId }
}

/**
 * A failure deliberately thrown from inside the payment transaction, so the rollback can be observed.
 *
 * Named rather than a bare `RuntimeException` so the test can assert that *this* failure came back out and
 * not something else on the way.
 */
private class InjectedFailure : RuntimeException("injected failure inside the payment transaction")

/**
 * The real transaction, with the block's own writes read back *inside* it and then a failure thrown.
 *
 * Reading the writes back is what makes this an assertion rather than a coincidence. Without it, "no expense
 * afterwards" would pass just as well if the payment had failed before writing anything, and the rollback it
 * is meant to prove would never have been exercised.
 */
private class FailingInsideTransactionRunner(
    private val real: TransactionRunner,
    private val database: HomeFinanceDatabase,
    private val occurrenceId: Long,
) : TransactionRunner {

    /** The expense this attempt wrote, captured from the outcome before the failure. */
    var writtenTransactionId: Long? = null
        private set

    private var expenseInside: Boolean = false
    private var linkInside: Boolean = false

    fun expenseWasWritten(): Boolean = expenseInside

    fun occurrenceWasLinked(): Boolean = linkInside

    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        real.inTransaction {
            val paid = block() as MarkPaidOutcome.Paid
            writtenTransactionId = paid.transactionId

            // Inside the transaction, both writes are visible - which is the whole point.
            expenseInside = database.transactionDao().getById(paid.transactionId) != null
            linkInside = database.recurringDao().getOccurrenceById(occurrenceId)?.transactionId ==
                paid.transactionId

            throw InjectedFailure()
        }
}
