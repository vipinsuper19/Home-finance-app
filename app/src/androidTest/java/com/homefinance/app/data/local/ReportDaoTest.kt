package com.homefinance.app.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homefinance.app.data.local.dao.CategoryDao
import com.homefinance.app.data.local.dao.TransactionDao
import com.homefinance.app.data.local.relation.TransactionTotals
import com.homefinance.app.data.model.TransactionType
import java.time.LocalDate
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The report's aggregate and listing reads against a **real** in-memory Room database.
 *
 * What a hand-written fake cannot promise is exactly what the report depends on here: that SQLite
 * really sums `INTEGER` paise without a rounding step, that `BETWEEN` really includes both ends, that
 * `GROUP BY` really returns one row per category and per day, that the collated `ORDER BY` really
 * puts the biggest category first, and that `LIMIT` really caps a listing at the database rather than
 * in the UI.
 */
@RunWith(AndroidJUnit4::class)
class ReportDaoTest {

    private lateinit var database: HomeFinanceDatabase
    private lateinit var categoryDao: CategoryDao
    private lateinit var transactionDao: TransactionDao

    private var groceriesId: Long = 0L
    private var rentId: Long = 0L
    private var salaryId: Long = 0L

    /** The period every test reads unless it says otherwise: October 2026, inclusive at both ends. */
    private val from: LocalDate = LocalDate.of(2026, 10, 1)
    private val to: LocalDate = LocalDate.of(2026, 10, 31)

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        categoryDao = database.categoryDao()
        transactionDao = database.transactionDao()

        runBlocking {
            groceriesId = categoryDao.insert(category("Groceries", TransactionType.EXPENSE, iconKey = "cart"))
            rentId = categoryDao.insert(category("Rent", TransactionType.EXPENSE, iconKey = "home"))
            salaryId = categoryDao.insert(category("Salary", TransactionType.INCOME, iconKey = "payments"))
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ------------------------------------------------------------------- totals

    @Test
    fun aPeriodWithNothingInItAddsUpToZeroRatherThanToNothing() {
        runBlocking {
            val totals = transactionDao.observeTotals(from, to).first()

            // One row of zeroes, not an empty result: the summary has to be able to say "nothing came
            // in and nothing went out" without the caller inventing the shape.
            assertEquals(TransactionTotals.None, totals)
        }
    }

    @Test
    fun incomeAndExpensesAreSummedApartWithTheirOwnCounts() {
        runBlocking {
            transactionDao.insert(transaction(salaryId, TransactionType.INCOME, amountPaise = 200_000L))
            transactionDao.insert(transaction(salaryId, TransactionType.INCOME, amountPaise = 50_000L))
            transactionDao.insert(transaction(groceriesId, amountPaise = 12_345L))
            transactionDao.insert(transaction(rentId, amountPaise = 60_000L))

            val totals = transactionDao.observeTotals(from, to).first()

            assertEquals(250_000L, totals.incomePaise)
            assertEquals(72_345L, totals.expensePaise)
            assertEquals(2L, totals.incomeCount)
            assertEquals(2L, totals.expenseCount)
        }
    }

    @Test
    fun bothEndsOfTheRangeAreCountedAndTheDaysOutsideItAreNot() {
        runBlocking {
            transactionDao.insert(transaction(rentId, amountPaise = 1L, transactionDate = from.minusDays(1L)))
            transactionDao.insert(transaction(rentId, amountPaise = 100L, transactionDate = from))
            transactionDao.insert(transaction(rentId, amountPaise = 1_000L, transactionDate = to))
            transactionDao.insert(transaction(rentId, amountPaise = 10_000L, transactionDate = to.plusDays(1L)))

            val totals = transactionDao.observeTotals(from, to).first()

            // The inclusive `BETWEEN` is the whole of the rule that decides which month an entry is in,
            // so a boundary that drifted by one day would move money between two months' figures.
            assertEquals(1_100L, totals.expensePaise)
            assertEquals(2L, totals.expenseCount)
        }
    }

    @Test
    fun anAmountTooLargeForAnIntIsStillSummedExactly() {
        runBlocking {
            // ₹2,147,483.65 twice, then once more: past `Int.MAX_VALUE` paise, so a 32-bit sum would
            // have wrapped into a negative figure the summary would have shown as income.
            repeat(3) { transactionDao.insert(transaction(groceriesId, amountPaise = 214_748_365L)) }

            val totals = transactionDao.observeTotals(from, to).first()

            assertEquals(644_245_095L, totals.expensePaise)
        }
    }

    // -------------------------------------------------------- category breakdown

    @Test
    fun aCategoryTotalCarriesItsNameItsMarkAndItsCount() {
        runBlocking {
            transactionDao.insert(transaction(groceriesId, amountPaise = 12_000L))
            transactionDao.insert(transaction(groceriesId, amountPaise = 3_000L))

            val total = transactionDao.observeCategoryTotals(TransactionType.EXPENSE, from, to).first().single()

            assertEquals(groceriesId, total.categoryId)
            assertEquals("Groceries", total.categoryName)
            assertEquals("cart", total.categoryIconKey)
            assertEquals(15_000L, total.totalPaise)
            assertEquals(2L, total.transactionCount)
        }
    }

    @Test
    fun theBiggestCategoryComesFirstAndEqualTotalsGoInNameOrder() {
        runBlocking {
            val abc = categoryDao.insert(category("abc", TransactionType.EXPENSE))
            val xyz = categoryDao.insert(category("XYZ", TransactionType.EXPENSE))

            transactionDao.insert(transaction(groceriesId, amountPaise = 30_000L))
            // Two categories on the same total, one named in lower case: the collation decides, and it
            // decides the same way whatever the household typed.
            transactionDao.insert(transaction(abc, amountPaise = 5_000L))
            transactionDao.insert(transaction(xyz, amountPaise = 5_000L))

            val names = transactionDao
                .observeCategoryTotals(TransactionType.EXPENSE, from, to)
                .first()
                .map { it.categoryName }

            assertEquals(listOf("Groceries", "abc", "XYZ"), names)
        }
    }

    @Test
    fun eachDirectionIsBrokenDownOnItsOwn() {
        runBlocking {
            transactionDao.insert(transaction(salaryId, TransactionType.INCOME, amountPaise = 90_000L))
            transactionDao.insert(transaction(groceriesId, amountPaise = 12_000L))

            val expenses = transactionDao.observeCategoryTotals(TransactionType.EXPENSE, from, to).first()
            val incomes = transactionDao.observeCategoryTotals(TransactionType.INCOME, from, to).first()

            assertEquals(listOf("Groceries"), expenses.map { it.categoryName })
            assertEquals(listOf("Salary"), incomes.map { it.categoryName })
        }
    }

    @Test
    fun aCategoryWithNothingInThePeriodIsAbsentRatherThanPresentAsZero() {
        runBlocking {
            // Rent was paid last month; the ring for this month must have no wedge for it.
            transactionDao.insert(
                transaction(rentId, amountPaise = 60_000L, transactionDate = from.minusDays(1L)),
            )

            assertTrue(transactionDao.observeCategoryTotals(TransactionType.EXPENSE, from, to).first().isEmpty())
        }
    }

    // --------------------------------------------------------------------- days

    @Test
    fun oneRowPerDayThatHasSomethingInItOldestFirst() {
        runBlocking {
            transactionDao.insert(transaction(groceriesId, amountPaise = 300L, transactionDate = LocalDate.of(2026, 10, 20)))
            transactionDao.insert(transaction(groceriesId, amountPaise = 100L, transactionDate = LocalDate.of(2026, 10, 3)))
            transactionDao.insert(transaction(groceriesId, amountPaise = 200L, transactionDate = LocalDate.of(2026, 10, 20)))

            val days = transactionDao.observeDailyTotals(from, to).first()

            assertEquals(
                listOf(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 20)),
                days.map { it.date },
            )
            assertEquals(listOf(100L, 500L), days.map { it.expensePaise })
        }
    }

    @Test
    fun aWeekWithNothingInItHasNoRowsAtAll() {
        runBlocking {
            transactionDao.insert(transaction(groceriesId, amountPaise = 100L, transactionDate = LocalDate.of(2026, 10, 1)))

            val days = transactionDao.observeDailyTotals(from, to).first()

            // "No row" and "a row worth nothing" are different facts; the report fills the quiet days
            // in from the period it knows, so the database must not invent them.
            assertEquals(1, days.size)
        }
    }

    @Test
    fun aDayCarriesBothDirections() {
        runBlocking {
            transactionDao.insert(transaction(salaryId, TransactionType.INCOME, amountPaise = 90_000L, transactionDate = TEST_DATE))
            transactionDao.insert(transaction(groceriesId, amountPaise = 12_000L, transactionDate = TEST_DATE))

            val day = transactionDao.observeDailyTotals(from, to).first().single()

            assertEquals(TEST_DATE, day.date)
            assertEquals(90_000L, day.incomePaise)
            assertEquals(12_000L, day.expensePaise)
        }
    }

    // ------------------------------------------------------- one category's rows

    @Test
    fun oneCategorysEntriesComeBackNewestFirstWithTheirNameAndMark() {
        runBlocking {
            val older = transactionDao.insert(
                transaction(groceriesId, amountPaise = 100L, transactionDate = LocalDate.of(2026, 10, 2), description = "Milk"),
            )
            val newer = transactionDao.insert(
                transaction(groceriesId, amountPaise = 500L, transactionDate = LocalDate.of(2026, 10, 9)),
            )
            transactionDao.insert(transaction(rentId, amountPaise = 999_999L, transactionDate = LocalDate.of(2026, 10, 9)))

            val rows = transactionDao.observeForCategoryInDateRange(groceriesId, from, to).first()

            assertEquals(listOf(newer, older), rows.map { it.id })
            assertEquals("Groceries", rows.first().categoryName)
            assertEquals("cart", rows.first().categoryIconKey)
            assertEquals("Milk", rows.last().description)
        }
    }

    @Test
    fun twoEntriesOnTheSameDayKeepTheNewerOneFirst() {
        runBlocking {
            val first = transactionDao.insert(transaction(groceriesId, amountPaise = 100L, transactionDate = TEST_DATE))
            val second = transactionDao.insert(transaction(groceriesId, amountPaise = 200L, transactionDate = TEST_DATE))

            val rows = transactionDao.observeForCategoryInDateRange(groceriesId, from, to).first()

            // The id breaks the tie, so the order the household sees cannot shuffle between reads.
            assertEquals(listOf(second, first), rows.map { it.id })
        }
    }

    @Test
    fun oneCategorysEntriesAreReadInclusivelyAtBothEndsOfThePeriod() {
        runBlocking {
            transactionDao.insert(transaction(groceriesId, amountPaise = 100L, transactionDate = from.minusDays(1L)))
            transactionDao.insert(transaction(groceriesId, amountPaise = 200L, transactionDate = from))
            transactionDao.insert(transaction(groceriesId, amountPaise = 300L, transactionDate = to))
            transactionDao.insert(transaction(groceriesId, amountPaise = 400L, transactionDate = to.plusDays(1L)))

            val rows = transactionDao.observeForCategoryInDateRange(groceriesId, from, to).first()

            assertEquals(listOf(300L, 200L), rows.map { it.amountPaise })
        }
    }

    @Test
    fun aRetiredCategoryStillNamesTheEntriesItHolds() {
        runBlocking {
            val id = transactionDao.insert(transaction(groceriesId, amountPaise = 12_000L))
            categoryDao.deactivate(groceriesId)

            val rows = transactionDao.observeForCategoryInDateRange(groceriesId, from, to).first()

            // Retiring a category does not rewrite history: the money really was spent under that name.
            assertEquals(1, rows.size)
            assertEquals("Groceries", rows.single().categoryName)
            assertNotNull(transactionDao.getById(id))
        }
    }

    // ------------------------------------------------------------- biggest first

    @Test
    fun theLargestEntriesComeBackBiggestFirstCappedByTheLimit() {
        runBlocking {
            repeat(8) { index ->
                transactionDao.insert(transaction(groceriesId, amountPaise = (index + 1) * 100L))
            }

            val rows = transactionDao.observeLargestInDateRange(from, to, limit = 5).first()

            assertEquals(listOf(800L, 700L, 600L, 500L, 400L), rows.map { it.amountPaise })
        }
    }

    @Test
    fun twoEntriesWorthTheSameKeepAStableOrder() {
        runBlocking {
            val first = transactionDao.insert(transaction(groceriesId, amountPaise = 500L))
            val second = transactionDao.insert(transaction(groceriesId, amountPaise = 500L))

            val rows = transactionDao.observeLargestInDateRange(from, to, limit = 5).first()

            assertEquals(listOf(second, first), rows.map { it.id })
        }
    }

    @Test
    fun theLargestEntriesSpanBothDirectionsAndCarryTheirType() {
        runBlocking {
            transactionDao.insert(transaction(salaryId, TransactionType.INCOME, amountPaise = 90_000L))
            transactionDao.insert(transaction(groceriesId, amountPaise = 12_000L))

            val rows = transactionDao.observeLargestInDateRange(from, to, limit = 5).first()

            assertEquals(listOf(TransactionType.INCOME, TransactionType.EXPENSE), rows.map { it.type })
        }
    }

    @Test
    fun theLargestEntriesAreConfinedToThePeriod() {
        runBlocking {
            transactionDao.insert(transaction(groceriesId, amountPaise = 999_999L, transactionDate = from.minusDays(1L)))
            transactionDao.insert(transaction(groceriesId, amountPaise = 100L))

            val rows = transactionDao.observeLargestInDateRange(from, to, limit = 5).first()

            assertEquals(listOf(100L), rows.map { it.amountPaise })
        }
    }

    // ----------------------------------------------------------------- reactivity

    /**
     * The reports are observed, so a figure on screen has to speak again when the ledger behind it is
     * written to. The collector runs alongside the writes: re-reading after each one would pass even
     * if the flow never spoke again.
     */
    @Test
    fun theTotalsSpeakAgainWhenAnEntryIsInsertedChangedAndRemoved() {
        runBlocking {
            transactionDao.insert(transaction(groceriesId, amountPaise = 100L))
            val emissions = Channel<Long>(Channel.UNLIMITED)

            val collector = launch {
                transactionDao.observeTotals(from, to).collect { emissions.send(it.expensePaise) }
            }

            try {
                assertEquals(100L, withTimeout(10_000) { emissions.receive() })

                val id = transactionDao.observeLargestInDateRange(from, to, 1).first().single().id
                assertEquals(id, transactionDao.observeForCategoryInDateRange(groceriesId, from, to).first().single().id)

                transactionDao.update(transactionDao.getById(id)!!.copy(amountPaise = 500L))
                assertEquals(500L, withTimeout(10_000) { emissions.receive() })

                transactionDao.deleteById(id)
                assertEquals(0L, withTimeout(10_000) { emissions.receive() })
            } finally {
                collector.cancel()
            }
        }
    }
}
