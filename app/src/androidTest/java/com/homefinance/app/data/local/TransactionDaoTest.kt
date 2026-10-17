package com.homefinance.app.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homefinance.app.data.local.dao.CategoryDao
import com.homefinance.app.data.local.dao.TransactionDao
import com.homefinance.app.data.local.relation.TransactionWithCategory
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
     * The three columns the add form can fill in beyond the amount all survive a round trip through
     * SQLite. A quantity and a rate are not derived on read - they are the figures that were typed -
     * so what comes back has to be exactly what went in.
     */
    @Test
    fun notesAQuantityAndARateAllSurviveTheRoundTrip() {
        runBlocking {
            val id = transactionDao.insert(
                transaction(
                    categoryId = expenseCategoryId,
                    amountPaise = 9_000L,
                    description = "Milk",
                    notes = "Two bottles, delivered",
                    quantityThousandths = 1_500L,
                    ratePaise = 6_000L,
                ),
            )

            val stored = transactionDao.getById(id)!!

            assertEquals("Milk", stored.description)
            assertEquals("Two bottles, delivered", stored.notes)
            assertEquals(1_500L, stored.quantityThousandths)
            assertEquals(6_000L, stored.ratePaise)
        }
    }

    /**
     * An entry with no quantity and no rate must store nothing in those columns - not zeroes, which
     * would claim the household had recorded a rate of ₹0.00 - and an entry with no notes must
     * store nothing rather than an empty string.
     */
    @Test
    fun anEntryWithNoNotesQuantityOrRateStoresNullsRatherThanZeroes() {
        runBlocking {
            val id = transactionDao.insert(transaction(expenseCategoryId))

            val stored = transactionDao.getById(id)!!

            assertNull(stored.notes)
            assertNull(stored.quantityThousandths)
            assertNull(stored.ratePaise)
        }
    }

    /** The notes column is not part of the ledger projection, so reading a row by id still finds it. */
    @Test
    fun notesAreStoredOnTheRowEvenThoughTheLedgerListDoesNotShowThem() {
        runBlocking {
            val id = transactionDao.insert(
                transaction(expenseCategoryId, description = "Milk", notes = "Delivered"),
            )

            // The ledger list shows the description, not the notes, so the projection leaves notes
            // out deliberately - but the row itself still has them, which is what an edit screen
            // will read.
            assertEquals("Milk", transactionDao.observeAllWithCategory().first().single().description)
            assertEquals("Delivered", transactionDao.getById(id)!!.notes)
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

    // ------------------------------------------------- one entry's details

    /**
     * The details screen reads through this query, so it has to carry the *whole* row - not just the
     * four things a ledger row draws - and the name of its own category.
     */
    @Test
    fun observeByIdWithCategoryCarriesTheWholeRowAndItsOwnCategoryName() {
        runBlocking {
            val id = transactionDao.insert(
                transaction(
                    expenseCategoryId,
                    amountPaise = 9_000L,
                    transactionDate = TEST_DATE.minusDays(2),
                    description = "Milk",
                    notes = "Two bottles",
                    quantityThousandths = 1_500L,
                    ratePaise = 6_000L,
                ),
            )

            val row = transactionDao.observeByIdWithCategory(id).first()!!

            assertEquals(id, row.id)
            assertEquals(TransactionType.EXPENSE, row.type)
            assertEquals(9_000L, row.amountPaise)
            assertEquals(expenseCategoryId, row.categoryId)
            assertEquals("Milk", row.description)
            assertEquals("Two bottles", row.notes)
            assertEquals(1_500L, row.quantityThousandths)
            assertEquals(6_000L, row.ratePaise)
            assertEquals(TEST_DATE.minusDays(2), row.transactionDate)
            assertEquals(TEST_INSTANT, row.createdAt)
            assertEquals(TEST_INSTANT, row.updatedAt)
            assertEquals("Groceries", row.categoryName)
        }
    }

    @Test
    fun observeByIdWithCategoryEmitsNullForAnIdentifierThatIsNotThere() {
        runBlocking {
            assertNull(transactionDao.observeByIdWithCategory(9_999L).first())
        }
    }

    @Test
    fun observeByIdWithCategoryNamesARetiredCategoryToo() {
        runBlocking {
            val id = transactionDao.insert(transaction(expenseCategoryId))
            categoryDao.deactivate(expenseCategoryId)

            // An edited entry keeps the category it was filed under, and the screen has to be able to
            // say what that category was.
            assertEquals(
                "Groceries",
                transactionDao.observeByIdWithCategory(id).first()!!.categoryName,
            )
        }
    }

    /**
     * The details screen observes one row, so it must speak again when that row changes and when it
     * goes - which is what makes an edit show up on the screen it was opened from, and what makes a
     * confirmed deletion end the screen rather than leaving it showing a transaction that is no
     * longer there.
     *
     * The collector runs alongside the writes rather than being re-read after each one, because a
     * re-read would pass even if the flow never spoke again.
     */
    @Test
    fun observeByIdWithCategorySpeaksAgainWhenTheRowChangesAndWhenItGoes() {
        runBlocking {
            val id = transactionDao.insert(transaction(expenseCategoryId, amountPaise = 100L))
            val emissions = Channel<TransactionWithCategory?>(Channel.UNLIMITED)

            val collector = launch {
                transactionDao.observeByIdWithCategory(id).collect { emissions.send(it) }
            }

            try {
                assertEquals(100L, withTimeout(10_000) { emissions.receive() }!!.amountPaise)

                transactionDao.update(transactionDao.getById(id)!!.copy(amountPaise = 500L))
                assertEquals(500L, withTimeout(10_000) { emissions.receive() }!!.amountPaise)

                transactionDao.deleteById(id)
                assertNull(withTimeout(10_000) { emissions.receive() })
            } finally {
                collector.cancel()
            }
        }
    }

    // --------------------------------------------------- deleting by identifier

    @Test
    fun deleteByIdRemovesExactlyTheRowAndReportsTheCount() {
        runBlocking {
            val kept = transactionDao.insert(transaction(expenseCategoryId, amountPaise = 100L))
            val removed = transactionDao.insert(transaction(expenseCategoryId, amountPaise = 200L))

            assertEquals(1, transactionDao.deleteById(removed))

            assertNull(transactionDao.getById(removed))
            assertNotNull(transactionDao.getById(kept))
            assertEquals(1, transactionDao.observeAll().first().size)
        }
    }

    @Test
    fun deleteByIdReportsNothingRemovedWhenTheIdentifierIsAlreadyGone() {
        runBlocking {
            val id = transactionDao.insert(transaction(expenseCategoryId))
            transactionDao.deleteById(id)

            // `0` is the database's own answer to "was it still there?", and it is what lets the
            // screen refuse to report a deletion it did not perform.
            assertEquals(0, transactionDao.deleteById(id))
        }
    }

    @Test
    fun deleteByIdDoesNotTakeTheCategoryOrItsOtherTransactionsWithIt() {
        runBlocking {
            val other = transactionDao.insert(transaction(expenseCategoryId, amountPaise = 100L))
            val removed = transactionDao.insert(transaction(expenseCategoryId, amountPaise = 200L))

            transactionDao.deleteById(removed)

            assertEquals(1, transactionDao.observeAllWithCategory().first().size)
            assertEquals(other, transactionDao.observeAllWithCategory().first().single().id)
            // The category is untouched: a transaction going is not a category going.
            assertNotNull(categoryDao.getById(expenseCategoryId))
        }
    }
}
