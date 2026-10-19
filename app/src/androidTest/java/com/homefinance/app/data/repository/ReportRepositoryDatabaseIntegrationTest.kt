package com.homefinance.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homefinance.app.data.local.HomeFinanceDatabase
import com.homefinance.app.data.local.RoomTransactionRunner
import com.homefinance.app.data.local.deleteTestDatabase
import com.homefinance.app.data.local.isolatedFileDatabase
import com.homefinance.app.data.local.relation.TransactionTotals
import com.homefinance.app.data.model.RecurrenceFrequency
import com.homefinance.app.data.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [ReportRepository] driven against a **real** Room database on disk, wired the way
 * [com.homefinance.app.HomeFinanceApp] wires it.
 *
 * Three claims can only be made here. First, that a figure the report shows is the ledger's own
 * arithmetic and not a second implementation of it: the daily rows a chart is drawn from have to add
 * up to the summary above them. Second, that an *expectation* is not money - a schedule's pending
 * occurrences must leave every report figure exactly where it was, and paying one must move it by
 * exactly that occurrence. Third, that a report that is open keeps up with the ledger: the collector
 * runs alongside the writes, because re-reading after each one would pass even if the flow had stopped
 * speaking.
 *
 * `today` is pinned to 2026-10-20 and the period to October 2026, so nothing here depends on when the
 * suite is run.
 */
@RunWith(AndroidJUnit4::class)
class ReportRepositoryDatabaseIntegrationTest {

    private lateinit var database: HomeFinanceDatabase
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var transactionRepository: TransactionRepository
    private lateinit var recurringRepository: RecurringRepository
    private lateinit var reports: ReportRepository

    private val today = LocalDate.of(2026, 10, 20)
    private val now = Instant.parse("2026-10-20T06:30:00Z")

    private val from = LocalDate.of(2026, 10, 1)
    private val to = LocalDate.of(2026, 10, 31)

    @Before
    fun setUp() {
        deleteTestDatabase()
        database = isolatedFileDatabase()
        categoryRepository = CategoryRepository(database.categoryDao())
        transactionRepository = TransactionRepository(
            transactionDao = database.transactionDao(),
            categoryDao = database.categoryDao(),
        )
        recurringRepository = recurringRepository(database)
        reports = ReportRepository(
            transactionDao = database.transactionDao(),
            categoryDao = database.categoryDao(),
        )
    }

    @After
    fun tearDown() {
        database.close()
        deleteTestDatabase()
    }

    // ------------------------------------------------------------------ summary

    @Test
    fun theSummaryIsWhatTheLedgerHoldsAndNothingElse() {
        runBlocking {
            val salary = incomeCategory()
            val groceries = expenseCategory("Groceries")

            transactionRepository.add(TransactionType.INCOME, 200_000L, salary, LocalDate.of(2026, 10, 1), now = now)
            transactionRepository.add(TransactionType.INCOME, 50_000L, salary, LocalDate.of(2026, 10, 20), now = now)
            transactionRepository.add(TransactionType.EXPENSE, 12_345L, groceries, LocalDate.of(2026, 10, 3), now = now)
            transactionRepository.add(TransactionType.EXPENSE, 60_000L, groceries, to, now = now)
            // The day after the period ends belongs to November's report, not this one.
            transactionRepository.add(TransactionType.EXPENSE, 999_999L, groceries, to.plusDays(1L), now = now)

            val totals = reports.observeTotals(from, to).first()

            assertEquals(250_000L, totals.incomePaise)
            assertEquals(72_345L, totals.expensePaise)
            assertEquals(177_655L, totals.netPaise)
            assertEquals(2L, totals.incomeCount)
            assertEquals(2L, totals.expenseCount)
            assertTrue(!totals.isEmpty)
        }
    }

    @Test
    fun theChartAndTheSummaryAreTheSameArithmetic() {
        runBlocking {
            val groceries = expenseCategory("Groceries")

            transactionRepository.add(TransactionType.INCOME, 90_000L, incomeCategory(), LocalDate.of(2026, 10, 1), now = now)
            transactionRepository.add(TransactionType.EXPENSE, 12_000L, groceries, LocalDate.of(2026, 10, 3), now = now)
            transactionRepository.add(TransactionType.EXPENSE, 1_000L, groceries, LocalDate.of(2026, 10, 3), now = now)
            transactionRepository.add(TransactionType.EXPENSE, 5_000L, groceries, LocalDate.of(2026, 10, 14), now = now)

            val totals = reports.observeTotals(from, to).first()
            val days = reports.observeDailyTotals(from, to).first()

            // A report whose chart disagreed with its own summary would be worse than one with no chart.
            assertEquals(totals.expensePaise, days.sumOf { it.expensePaise })
            assertEquals(totals.incomePaise, days.sumOf { it.incomePaise })
            // Two entries on the 3rd, and one on the 14th: one row per day that has anything in it.
            assertEquals(
                listOf(13_000L, 5_000L),
                days.filter { it.expensePaise > 0L }.map { it.expensePaise },
            )
            assertEquals(LocalDate.of(2026, 10, 14), days.last().date)
        }
    }

    @Test
    fun theBreakdownAndTheCategorysOwnListingAgreeAboutTheSameMoney() {
        runBlocking {
            val groceries = expenseCategory("Groceries")

            transactionRepository.add(TransactionType.EXPENSE, 12_000L, groceries, LocalDate.of(2026, 10, 3), now = now)
            transactionRepository.add(TransactionType.EXPENSE, 4_000L, groceries, LocalDate.of(2026, 10, 9), now = now)
            transactionRepository.add(TransactionType.EXPENSE, 60_000L, expenseCategory("Rent"), LocalDate.of(2026, 10, 5), now = now)

            val breakdown = reports.observeCategoryTotals(TransactionType.EXPENSE, from, to).first()
            val groceriesTotal = breakdown.single { it.categoryId == groceries }
            val rows = reports.observeForCategoryInDateRange(groceries, from, to).first()

            assertEquals(groceriesTotal.totalPaise, rows.sumOf { it.amountPaise })
            assertEquals(groceriesTotal.transactionCount, rows.size.toLong())
            // The breakdown is ordered biggest first, so the ring's first wedge is the largest share.
            assertEquals(60_000L, breakdown.first().totalPaise)
        }
    }

    // ------------------------------------------- an expectation is not an expense

    @Test
    fun aPendingRecurringOccurrenceReachesNoReportFigure() {
        runBlocking {
            val rent = expenseCategory("Rent")
            val scheduleId = scheduleMonthlyOnTheFifth(rent)

            val occurrences = recurringRepository.observeOccurrences(scheduleId).first()

            assertTrue("a monthly rule from August has dates due by October", occurrences.isNotEmpty())
            assertTrue(occurrences.any { it.dueDate == LocalDate.of(2026, 10, 5) })

            // Not one of those dates is money the household spent, so not one of them may appear on a
            // report: a schedule is a plan, and only a payment is a fact.
            assertEquals(TransactionTotals.None, reports.observeTotals(from, to).first())
            assertTrue(reports.observeCategoryTotals(TransactionType.EXPENSE, from, to).first().isEmpty())
            assertTrue(reports.observeDailyTotals(from, to).first().isEmpty())
            assertTrue(reports.observeLargestInDateRange(from, to, 5).first().isEmpty())
        }
    }

    @Test
    fun payingOneOccurrenceMovesTheReportByExactlyThatOccurrence() {
        runBlocking {
            val rent = expenseCategory("Rent")
            val scheduleId = scheduleMonthlyOnTheFifth(rent)

            val october = recurringRepository
                .observeOccurrences(scheduleId)
                .first()
                .single { it.dueDate == LocalDate.of(2026, 10, 5) }

            assertEquals(TransactionTotals.None, reports.observeTotals(from, to).first())

            recurringRepository.markOccurrencePaid(october.id, october.amountPaise, now)

            val totals = reports.observeTotals(from, to).first()

            // Marking a date paid is the only thing that turns an expectation into an entry, so it must
            // move the report by that one occurrence - and the occurrence still on the schedule for
            // November must not have come with it.
            assertEquals(october.amountPaise, totals.expensePaise)
            assertEquals(1L, totals.expenseCount)
            assertEquals(
                listOf("Rent"),
                reports.observeCategoryTotals(TransactionType.EXPENSE, from, to).first().map { it.categoryName },
            )
            assertEquals(
                listOf(LocalDate.of(2026, 10, 5)),
                reports.observeDailyTotals(from, to).first().map { it.date },
            )
        }
    }

    @Test
    fun payingTheSameOccurrenceTwiceLeavesTheReportWithOneEntry() {
        runBlocking {
            val rent = expenseCategory("Rent")
            val scheduleId = scheduleMonthlyOnTheFifth(rent)
            val october = recurringRepository.observeOccurrences(scheduleId).first()
                .single { it.dueDate == LocalDate.of(2026, 10, 5) }

            recurringRepository.markOccurrencePaid(october.id, october.amountPaise, now)
            recurringRepository.markOccurrencePaid(october.id, october.amountPaise, now)

            // A repeated tap must not double the household's spending, which is the same guarantee the
            // recurring feature makes - seen from the report's side.
            assertEquals(1L, reports.observeTotals(from, to).first().expenseCount)
        }
    }

    @Test
    fun twoPaymentsInTheSameInstantDoNotBecomeTwoExpenses() {
        runBlocking {
            val rent = expenseCategory("Rent")
            val scheduleId = scheduleMonthlyOnTheFifth(rent)
            val october = recurringRepository.observeOccurrences(scheduleId).first()
                .single { it.dueDate == LocalDate.of(2026, 10, 5) }

            val first = async { recurringRepository.markOccurrencePaid(october.id, october.amountPaise, now) }
            val second = async { recurringRepository.markOccurrencePaid(october.id, october.amountPaise, now) }
            first.await()
            second.await()

            assertEquals(1L, reports.observeTotals(from, to).first().expenseCount)
        }
    }

    // -------------------------------------------------------------- reactivity

    @Test
    fun anOpenReportSpeaksAgainWhenAnEntryIsRecordedChangedAndRemoved() {
        runBlocking {
            val groceries = expenseCategory("Groceries")
            val id = transactionRepository.add(TransactionType.EXPENSE, 1_000L, groceries, LocalDate.of(2026, 10, 4), now = now)
            val emissions = Channel<Long>(Channel.UNLIMITED)

            val collector = launch {
                reports.observeTotals(from, to).collect { emissions.send(it.expensePaise) }
            }

            try {
                assertEquals(1_000L, withTimeout(10_000) { emissions.receive() })

                transactionRepository.update(
                    transactionRepository.getById(id)!!.copy(amountPaise = 2_500L),
                    now = now,
                )
                assertEquals(2_500L, withTimeout(10_000) { emissions.receive() })

                transactionRepository.delete(transactionRepository.getById(id)!!)
                assertEquals(0L, withTimeout(10_000) { emissions.receive() })
            } finally {
                collector.cancel()
            }
        }
    }

    // ------------------------------------------------------- categories over time

    @Test
    fun aRetiredCategoryStillNamesTheMoneySpentUnderIt() {
        runBlocking {
            val groceries = expenseCategory("Groceries")
            transactionRepository.add(TransactionType.EXPENSE, 12_000L, groceries, LocalDate.of(2026, 10, 3), now = now)

            categoryRepository.deactivate(groceries)

            // Retiring a category is how this app removes one, and it must not rewrite the past: the
            // report still names the category the money was filed under, or a household could not tell
            // what its own month was spent on.
            val breakdown = reports.observeCategoryTotals(TransactionType.EXPENSE, from, to).first()
            assertEquals(listOf("Groceries"), breakdown.map { it.categoryName })

            val stillReadable = reports.observeCategory(groceries).first()
            assertNotNull(stillReadable)
            assertFalse(stillReadable!!.isActive)
        }
    }

    @Test
    fun aCategoryWithAQuietMonthIsReadableButEmptyAndAnUnknownOneIsMissing() {
        runBlocking {
            val groceries = expenseCategory("Groceries")
            transactionRepository.add(TransactionType.EXPENSE, 12_000L, groceries, LocalDate.of(2026, 9, 3), now = now)

            // Last month's groceries: the drill-down has to be able to name the category it is about
            // even in a month that holds none of its entries - and to say so rather than fail.
            assertNotNull(reports.observeCategory(groceries).first())
            assertTrue(reports.observeForCategoryInDateRange(groceries, from, to).first().isEmpty())
            assertNull(reports.observeCategory(9_999L).first())
        }
    }

    // ----------------------------------------------------------------- helpers

    private fun recurringRepository(database: HomeFinanceDatabase): RecurringRepository = RecurringRepository(
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
        transactionRunner = RoomTransactionRunner(database),
    )

    private suspend fun expenseCategory(name: String): Long =
        categoryRepository.create(name, TransactionType.EXPENSE)

    private suspend fun incomeCategory(name: String = "Salary"): Long =
        categoryRepository.create(name, TransactionType.INCOME)

    /** A monthly rent due on the 5th from August, which has generated dates by the pinned October day. */
    private suspend fun scheduleMonthlyOnTheFifth(categoryId: Long): Long =
        recurringRepository.createSchedule(
            name = "Shop rent",
            categoryId = categoryId,
            amountPaise = 1_500_000L,
            frequency = RecurrenceFrequency.MONTHLY,
            intervalCount = 1,
            startDate = LocalDate.of(2026, 8, 5),
            endDate = null,
            today = today,
            now = now,
        )
}
