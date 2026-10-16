package com.homefinance.app.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homefinance.app.data.local.HomeFinanceDatabase
import com.homefinance.app.data.local.deleteTestDatabase
import com.homefinance.app.data.local.isolatedFileDatabase
import com.homefinance.app.data.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
 * The repositories driven against a **real** Room database on disk, wired exactly the way
 * [com.homefinance.app.HomeFinanceApp] wires them: one database, and that database's own DAOs.
 *
 * `TransactionRepositoryTest` and `CategoryRepositoryTest` already cover the validation rules
 * against fakes, where they run in milliseconds. What that cannot show - and what this class adds
 * - is that the rules still hold when a real SQLite file, a real unique index, real type
 * converters and a real foreign key are involved, and that the two repositories genuinely share
 * one database.
 */
@RunWith(AndroidJUnit4::class)
class RepositoryDatabaseIntegrationTest {

    private lateinit var database: HomeFinanceDatabase
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var transactionRepository: TransactionRepository

    private val now = Instant.parse("2026-10-16T06:30:00Z")
    private val later = Instant.parse("2026-10-17T09:00:00Z")
    private val date = LocalDate.of(2026, 10, 15)

    @Before
    fun setUp() {
        deleteTestDatabase()
        database = isolatedFileDatabase()
        categoryRepository = CategoryRepository(database.categoryDao())
        transactionRepository = TransactionRepository(
            transactionDao = database.transactionDao(),
            categoryDao = database.categoryDao(),
        )
    }

    @After
    fun tearDown() {
        database.close()
        deleteTestDatabase()
    }

    // ------------------------------------------------------------ categories

    @Test
    fun aValidCategoryCanBeCreated() {
        runBlocking {
            val id = categoryRepository.create("  Groceries ", TransactionType.EXPENSE)

            val stored = categoryRepository.getById(id)
            assertNotNull(stored)
            assertEquals("Groceries", stored!!.name)
            assertTrue(stored.isActive)
        }
    }

    @Test
    fun aDuplicateCategoryNameIsRejectedByTheDatabase() {
        runBlocking {
            categoryRepository.create("Groceries", TransactionType.EXPENSE)

            // Different case and stray whitespace, same category as far as the unique index
            // and its NOCASE collation are concerned.
            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking { categoryRepository.create("  groceries", TransactionType.EXPENSE) }
            }

            assertEquals(1, categoryRepository.observeAll().first().size)
        }
    }

    @Test
    fun theSameNameIsAllowedForTheOtherType() {
        runBlocking {
            categoryRepository.create("Bonus", TransactionType.INCOME)
            categoryRepository.create("Bonus", TransactionType.EXPENSE)

            assertEquals(2, categoryRepository.observeAll().first().size)
        }
    }

    // ---------------------------------------------------------- transactions

    @Test
    fun aValidExpenseCanBeCreated() {
        runBlocking {
            val categoryId = expenseCategory()
            val id = transactionRepository.add(
                type = TransactionType.EXPENSE,
                amountPaise = 12_345L,
                categoryId = categoryId,
                transactionDate = date,
                description = "  August milk  ",
                now = now,
            )

            val stored = transactionRepository.getById(id)
            assertNotNull(stored)
            assertEquals(TransactionType.EXPENSE, stored!!.type)
            assertEquals(12_345L, stored.amountPaise)
            assertEquals("August milk", stored.description)
            assertEquals(date, stored.transactionDate)
            assertEquals(now, stored.createdAt)
        }
    }

    @Test
    fun aValidIncomeCanBeCreated() {
        runBlocking {
            val categoryId = incomeCategory()
            val id = transactionRepository.add(
                type = TransactionType.INCOME,
                amountPaise = 5_000_000L,
                categoryId = categoryId,
                transactionDate = date,
                now = now,
            )

            val stored = transactionRepository.getById(id)
            assertEquals(TransactionType.INCOME, stored!!.type)
            assertEquals(5_000_000L, stored.amountPaise)
        }
    }

    @Test
    fun aZeroAmountIsRejected() {
        runBlocking {
            val categoryId = expenseCategory()

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    transactionRepository.add(TransactionType.EXPENSE, 0L, categoryId, date, now = now)
                }
            }

            assertTrue(transactionRepository.observeAll().first().isEmpty())
        }
    }

    @Test
    fun aNegativeAmountIsRejected() {
        runBlocking {
            val categoryId = expenseCategory()

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    transactionRepository.add(TransactionType.EXPENSE, -500L, categoryId, date, now = now)
                }
            }

            assertTrue(transactionRepository.observeAll().first().isEmpty())
        }
    }

    @Test
    fun aTransactionForANonexistentCategoryIsRejected() {
        runBlocking {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    transactionRepository.add(TransactionType.EXPENSE, 500L, 9_999L, date, now = now)
                }
            }

            assertTrue(transactionRepository.observeAll().first().isEmpty())
        }
    }

    @Test
    fun aTransactionWhoseCategoryTypeDoesNotMatchIsRejected() {
        runBlocking {
            val incomeCategoryId = incomeCategory()

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    transactionRepository.add(
                        type = TransactionType.EXPENSE,
                        amountPaise = 500L,
                        categoryId = incomeCategoryId,
                        transactionDate = date,
                        now = now,
                    )
                }
            }

            assertTrue(transactionRepository.observeAll().first().isEmpty())
        }
    }

    // ---------------------------------------------------------------- update

    @Test
    fun anUpdateIsValidatedAndRestamped() {
        runBlocking {
            val categoryId = expenseCategory()
            val id = transactionRepository.add(TransactionType.EXPENSE, 500L, categoryId, date, now = now)

            transactionRepository.update(
                transactionRepository.getById(id)!!.copy(amountPaise = 900L),
                now = later,
            )

            val stored = transactionRepository.getById(id)!!
            assertEquals(900L, stored.amountPaise)
            assertEquals(now, stored.createdAt)
            assertEquals(later, stored.updatedAt)
        }
    }

    @Test
    fun anUpdateRejectsANonPositiveAmountAndLeavesTheRowAlone() {
        runBlocking {
            val categoryId = expenseCategory()
            val id = transactionRepository.add(TransactionType.EXPENSE, 500L, categoryId, date, now = now)
            val stored = transactionRepository.getById(id)!!

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { transactionRepository.update(stored.copy(amountPaise = 0L), now = later) }
            }

            assertEquals(500L, transactionRepository.getById(id)!!.amountPaise)
        }
    }

    @Test
    fun anUpdateRejectsAMismatchedCategoryAndLeavesTheRowAlone() {
        runBlocking {
            val categoryId = expenseCategory()
            val id = transactionRepository.add(TransactionType.EXPENSE, 500L, categoryId, date, now = now)
            val stored = transactionRepository.getById(id)!!

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    transactionRepository.update(stored.copy(type = TransactionType.INCOME), now = later)
                }
            }

            assertEquals(TransactionType.EXPENSE, transactionRepository.getById(id)!!.type)
        }
    }

    // ---------------------------------------------------------- deactivation

    @Test
    fun deactivatingACategoryPreservesItsHistoricalTransactions() {
        runBlocking {
            val categoryId = categoryRepository.create("Milk", TransactionType.EXPENSE)
            val transactionId = transactionRepository.add(
                TransactionType.EXPENSE,
                2_500L,
                categoryId,
                date,
                now = now,
            )

            categoryRepository.deactivate(categoryId)

            val category = categoryRepository.getById(categoryId)
            assertNotNull(category)
            assertFalse(category!!.isActive)

            // The entry is still on the ledger, still filed under the retired category.
            assertEquals(1, transactionRepository.observeAll().first().size)
            assertEquals(categoryId, transactionRepository.getById(transactionId)!!.categoryId)

            // And history stays correctable, which is why a retired category is still accepted.
            transactionRepository.update(
                transactionRepository.getById(transactionId)!!.copy(amountPaise = 2_600L),
                now = later,
            )
            assertEquals(2_600L, transactionRepository.getById(transactionId)!!.amountPaise)
        }
    }

    // ------------------------------------------------------ retrieval/delete

    @Test
    fun aTransactionCanBeRetrievedAndDeleted() {
        runBlocking {
            val categoryId = expenseCategory()
            val kept = transactionRepository.add(TransactionType.EXPENSE, 100L, categoryId, date, now = now)
            val removed = transactionRepository.add(TransactionType.EXPENSE, 200L, categoryId, date, now = now)

            assertNotNull(transactionRepository.getById(removed))

            transactionRepository.delete(transactionRepository.getById(removed)!!)

            assertNull(transactionRepository.getById(removed))
            assertNotNull(transactionRepository.getById(kept))
            assertEquals(1, transactionRepository.observeAll().first().size)
        }
    }

    // ------------------------------------------------------------- sharing

    /**
     * The behavioural half of "both repositories use the same database instance".
     *
     * A category created through [CategoryRepository] is what [TransactionRepository] then finds
     * and validates against; had the two been built on separate database instances, this insert
     * would fail with "no category" instead of succeeding. The row is then also visible through
     * the database's own DAO, so all three agree on one file.
     */
    @Test
    fun bothRepositoriesOperateOnTheSameDatabase() {
        runBlocking {
            val categoryId = categoryRepository.create("Groceries", TransactionType.EXPENSE)
            val transactionId = transactionRepository.add(
                TransactionType.EXPENSE,
                4_500L,
                categoryId,
                date,
                now = now,
            )

            assertEquals(categoryId, transactionRepository.getById(transactionId)!!.categoryId)
            assertEquals(1, database.categoryDao().observeAll().first().size)
            assertEquals(1, database.transactionDao().observeAll().first().size)
        }
    }

    private suspend fun expenseCategory(name: String = "Groceries"): Long =
        categoryRepository.create(name, TransactionType.EXPENSE)

    private suspend fun incomeCategory(name: String = "Salary"): Long =
        categoryRepository.create(name, TransactionType.INCOME)
}
