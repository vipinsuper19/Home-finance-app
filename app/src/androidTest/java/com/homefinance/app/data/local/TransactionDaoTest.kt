package com.homefinance.app.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homefinance.app.data.local.dao.CategoryDao
import com.homefinance.app.data.local.dao.TransactionDao
import com.homefinance.app.data.model.TransactionType
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [TransactionDao] against a real in-memory Room database.
 *
 * The things worth proving here cannot be faked: that an amount really is stored as an exact
 * integer, that a transaction pointing at a category that does not exist is refused by the
 * foreign key rather than being silently accepted, and that the `transactions`/`categories` join
 * the transaction list reads really does hand back each row's own category name.
 */
@RunWith(AndroidJUnit4::class)
class TransactionDaoTest {

    private lateinit var database: HomeFinanceDatabase
    private lateinit var categoryDao: CategoryDao
    private lateinit var transactionDao: TransactionDao

    private var expenseCategoryId: Long = 0L
    private var incomeCategoryId: Long = 0L

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        categoryDao = database.categoryDao()
        transactionDao = database.transactionDao()

        runBlocking {
            expenseCategoryId = categoryDao.insert(category("Groceries", TransactionType.EXPENSE))
            incomeCategoryId = categoryDao.insert(category("Salary", TransactionType.INCOME))
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun anInsertedTransactionCanBeReadBackById() {
        runBlocking {
            val id = transactionDao.insert(
                transaction(expenseCategoryId, amountPaise = 12_345L),
            )

            val stored = transactionDao.getById(id)

            assertNotNull(stored)
            assertEquals(12_345L, stored!!.amountPaise)
            assertEquals(TransactionType.EXPENSE, stored.type)
            assertEquals(expenseCategoryId, stored.categoryId)
            assertEquals(TEST_DATE, stored.transactionDate)
            assertEquals(TEST_INSTANT, stored.createdAt)
        }
    }

    @Test
    fun anUnknownIdReturnsNull() {
        runBlocking {
            assertNull(transactionDao.getById(4242L))
        }
    }

    /**
     * 2^53 + 1 is the smallest integer a `Double` cannot represent, so if the amount ever went
     * through floating point this value would come back as 9 007 199 254 740 992 and the test
     * would fail. Money is stored as an exact `INTEGER`.
     */
    @Test
    fun anAmountIsStoredAsAnExactInteger() {
        runBlocking {
            val awkward = 9_007_199_254_740_993L
            val id = transactionDao.insert(transaction(expenseCategoryId, amountPaise = awkward))

            assertEquals(awkward, transactionDao.getById(id)!!.amountPaise)
        }
    }

    @Test
    fun observeAllReturnsTheNewestFirst() {
        runBlocking {
            transactionDao.insert(transaction(expenseCategoryId, transactionDate = TEST_DATE.minusDays(2)))
            transactionDao.insert(transaction(expenseCategoryId, transactionDate = TEST_DATE))
            transactionDao.insert(transaction(expenseCategoryId, transactionDate = TEST_DATE.minusDays(1)))

            val dates = transactionDao.observeAll().first().map { it.transactionDate }

            assertEquals(
                listOf(TEST_DATE, TEST_DATE.minusDays(1), TEST_DATE.minusDays(2)),
                dates,
            )
        }
    }

    /**
     * A household easily records several entries on one day, so the date alone would leave the
     * order undefined and the list could appear to reshuffle. Ids break the tie, newest first.
     */
    @Test
    fun transactionsOnTheSameDayAreOrderedNewestIdFirst() {
        runBlocking {
            val first = transactionDao.insert(transaction(expenseCategoryId, amountPaise = 100L))
            val second = transactionDao.insert(transaction(expenseCategoryId, amountPaise = 200L))
            val third = transactionDao.insert(transaction(expenseCategoryId, amountPaise = 300L))

            val ids = transactionDao.observeAll().first().map { it.id }

            assertEquals(listOf(third, second, first), ids)
        }
    }

    /**
     * The transaction list needs the category's *name*, which lives in another table. Joining it
     * in SQL is what keeps rendering the list to one query; this pins the behaviour a fake cannot
     * reproduce - the right name for each row, the converted enum and date, and the same
     * newest-first order [observeAll] uses.
     */
    @Test
    fun observeAllWithCategoryCarriesEachRowsOwnCategoryNameNewestFirst() {
        runBlocking {
            val groceriesOlder = transactionDao.insert(
                transaction(expenseCategoryId, amountPaise = 111L, transactionDate = TEST_DATE.minusDays(1)),
            )
            val salaryNewer = transactionDao.insert(
                transaction(incomeCategoryId, TransactionType.INCOME, amountPaise = 222L, transactionDate = TEST_DATE),
            )

            val rows = transactionDao.observeAllWithCategory().first()

            assertEquals(listOf(salaryNewer, groceriesOlder), rows.map { it.id })
            assertEquals(listOf("Salary", "Groceries"), rows.map { it.categoryName })
            assertEquals(listOf(TransactionType.INCOME, TransactionType.EXPENSE), rows.map { it.type })
            assertEquals(listOf(222L, 111L), rows.map { it.amountPaise })
            assertEquals(listOf(TEST_DATE, TEST_DATE.minusDays(1)), rows.map { it.transactionDate })
        }
    }

    @Test
    fun observeAllWithCategoryCarriesTheDescriptionAndCategoryIdThrough() {
        runBlocking {
            val id = transactionDao.insert(transaction(expenseCategoryId))
            transactionDao.update(transactionDao.getById(id)!!.copy(description = "August milk"))

            val row = transactionDao.observeAllWithCategory().first().single()

            assertEquals("August milk", row.description)
            assertEquals(expenseCategoryId, row.categoryId)
        }
    }

    @Test
    fun observeAllWithCategoryLeavesTheDescriptionNullWhenThereIsNone() {
        runBlocking {
            transactionDao.insert(transaction(expenseCategoryId))

            assertNull(transactionDao.observeAllWithCategory().first().single().description)
        }
    }

    /**
     * Retiring a category must not erase it from history: the ledger still has to say what the
     * money was spent on, which is exactly why categories are deactivated rather than deleted.
     */
    @Test
    fun observeAllWithCategoryStillNamesARetiredCategory() {
        runBlocking {
            transactionDao.insert(transaction(expenseCategoryId, amountPaise = 111L))
            categoryDao.deactivate(expenseCategoryId)

            assertEquals("Groceries", transactionDao.observeAllWithCategory().first().single().categoryName)
        }
    }

    /** The empty state depends on this: an empty ledger is an empty list, not a failure. */
    @Test
    fun observeAllWithCategoryIsEmptyWhenNothingHasBeenRecorded() {
        runBlocking {
            assertEquals(0, transactionDao.observeAllWithCategory().first().size)
        }
    }

    @Test
    fun theDateRangeQueryIsInclusiveOnBothEnds() {
        runBlocking {
            transactionDao.insert(transaction(expenseCategoryId, transactionDate = TEST_DATE.minusDays(1)))
            transactionDao.insert(transaction(expenseCategoryId, transactionDate = TEST_DATE))
            transactionDao.insert(transaction(expenseCategoryId, transactionDate = TEST_DATE.plusDays(1)))

            val inRange = transactionDao
                .observeInDateRange(TEST_DATE.minusDays(1), TEST_DATE)
                .first()

            assertEquals(2, inRange.size)
        }
    }

    @Test
    fun theDateRangeQueryExcludesEverythingOutsideIt() {
        runBlocking {
            transactionDao.insert(transaction(expenseCategoryId, transactionDate = TEST_DATE))
            transactionDao.insert(
                transaction(expenseCategoryId, transactionDate = LocalDate.of(2025, 1, 1)),
            )

            val inRange = transactionDao.observeInDateRange(TEST_DATE, TEST_DATE).first()

            assertEquals(1, inRange.size)
            assertEquals(TEST_DATE, inRange.single().transactionDate)
        }
    }

    @Test
    fun theTypeFilteredRangeQueryFiltersByBothTypeAndDate() {
        runBlocking {
            transactionDao.insert(transaction(expenseCategoryId, TransactionType.EXPENSE, transactionDate = TEST_DATE))
            transactionDao.insert(transaction(incomeCategoryId, TransactionType.INCOME, transactionDate = TEST_DATE))
            transactionDao.insert(
                transaction(incomeCategoryId, TransactionType.INCOME, transactionDate = TEST_DATE.minusDays(5)),
            )

            val expenses = transactionDao
                .observeByTypeInDateRange(TransactionType.EXPENSE, TEST_DATE, TEST_DATE)
                .first()

            assertEquals(1, expenses.size)
            assertEquals(TransactionType.EXPENSE, expenses.single().type)
        }
    }

    /** The foreign key backstop: a category id that does not exist is refused, not stored. */
    @Test
    fun aTransactionWithoutAMatchingCategoryIsRefused() {
        runBlocking {
            assertConstraintViolation {
                transactionDao.insert(transaction(categoryId = 9_999L))
            }

            assertEquals(0, transactionDao.observeAll().first().size)
        }
    }

    @Test
    fun deleteRemovesOnlyTheSelectedRow() {
        runBlocking {
            val kept = transactionDao.insert(transaction(expenseCategoryId, amountPaise = 100L))
            val removed = transactionDao.insert(transaction(expenseCategoryId, amountPaise = 200L))

            transactionDao.delete(transactionDao.getById(removed)!!)

            assertNull(transactionDao.getById(removed))
            assertNotNull(transactionDao.getById(kept))
            assertEquals(1, transactionDao.observeAll().first().size)
        }
    }

    @Test
    fun updateReplacesTheStoredRow() {
        runBlocking {
            val id = transactionDao.insert(transaction(expenseCategoryId, amountPaise = 100L))
            val stored = transactionDao.getById(id)!!

            transactionDao.update(
                stored.copy(amountPaise = 900L, description = "August milk", updatedAt = TEST_INSTANT.plusSeconds(60)),
            )

            val updated = transactionDao.getById(id)!!
            assertEquals(900L, updated.amountPaise)
            assertEquals("August milk", updated.description)
            assertEquals(TEST_INSTANT.plusSeconds(60), updated.updatedAt)
        }
    }
}
