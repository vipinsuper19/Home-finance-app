package com.homefinance.app.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homefinance.app.data.local.dao.CategoryDao
import com.homefinance.app.data.local.dao.TransactionDao
import com.homefinance.app.data.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
 * [CategoryDao] against a real in-memory Room database.
 *
 * These are the assertions that a fake DAO cannot honestly make: that the unique index really
 * rejects duplicate names, that `NOCASE` really makes it case-insensitive, and that the foreign
 * key really refuses to delete a category out from under its transactions.
 */
@RunWith(AndroidJUnit4::class)
class CategoryDaoTest {

    private lateinit var database: HomeFinanceDatabase
    private lateinit var categoryDao: CategoryDao
    private lateinit var transactionDao: TransactionDao

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        categoryDao = database.categoryDao()
        transactionDao = database.transactionDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun anInsertedCategoryCanBeReadBackById() {
        runBlocking {
            val id = categoryDao.insert(category("Groceries", TransactionType.EXPENSE))

            val stored = categoryDao.getById(id)

            assertNotNull(stored)
            assertEquals("Groceries", stored!!.name)
            assertEquals(TransactionType.EXPENSE, stored.type)
            assertTrue(stored.isActive)
        }
    }

    @Test
    fun anUnknownIdReturnsNull() {
        runBlocking {
            assertNull(categoryDao.getById(4242L))
        }
    }

    @Test
    fun aDuplicateNameForTheSameTypeIsRejected() {
        runBlocking {
            categoryDao.insert(category("Groceries", TransactionType.EXPENSE))

            assertConstraintViolation {
                categoryDao.insert(category("Groceries", TransactionType.EXPENSE))
            }

            assertEquals(1, categoryDao.observeAll().first().size)
        }
    }

    @Test
    fun duplicateDetectionIgnoresCase() {
        runBlocking {
            categoryDao.insert(category("Groceries", TransactionType.EXPENSE))

            assertConstraintViolation {
                categoryDao.insert(category("groceries", TransactionType.EXPENSE))
            }
        }
    }

    @Test
    fun duplicateDetectionIgnoresSurroundingWhitespace() {
        runBlocking {
            categoryDao.insert(category("Groceries", TransactionType.EXPENSE))

            assertConstraintViolation {
                categoryDao.insert(category("   Groceries  ", TransactionType.EXPENSE))
            }
        }
    }

    @Test
    fun theSameNameIsAllowedForTheOtherType() {
        runBlocking {
            categoryDao.insert(category("Bonus", TransactionType.INCOME))

            val id = categoryDao.insert(category("Bonus", TransactionType.EXPENSE))

            assertNotNull(categoryDao.getById(id))
            assertEquals(2, categoryDao.observeAll().first().size)
        }
    }

    @Test
    fun observeAllListsActiveCategoriesBeforeRetiredOnes() {
        runBlocking {
            val retired = categoryDao.insert(category("Old Rent", TransactionType.EXPENSE))
            categoryDao.insert(category("Groceries", TransactionType.EXPENSE))
            categoryDao.deactivate(retired)

            val names = categoryDao.observeAll().first().map { it.name }

            assertEquals(listOf("Groceries", "Old Rent"), names)
        }
    }

    @Test
    fun observeAllOrdersAlphabeticallyRegardlessOfCase() {
        runBlocking {
            categoryDao.insert(category("milk", TransactionType.EXPENSE))
            categoryDao.insert(category("Electricity", TransactionType.EXPENSE))
            categoryDao.insert(category("Groceries", TransactionType.EXPENSE))

            val names = categoryDao.observeAll().first().map { it.name }

            assertEquals(listOf("Electricity", "Groceries", "milk"), names)
        }
    }

    @Test
    fun observeByTypeReturnsOnlyThatType() {
        runBlocking {
            categoryDao.insert(category("Groceries", TransactionType.EXPENSE))
            categoryDao.insert(category("Salary", TransactionType.INCOME))

            val expenses = categoryDao.observeByType(TransactionType.EXPENSE).first()

            assertEquals(listOf("Groceries"), expenses.map { it.name })
        }
    }

    @Test
    fun deactivatingACategoryKeepsBothTheCategoryAndItsTransactions() {
        runBlocking {
            val categoryId = categoryDao.insert(category("Milk", TransactionType.EXPENSE))
            transactionDao.insert(transaction(categoryId = categoryId))

            categoryDao.deactivate(categoryId)

            val stored = categoryDao.getById(categoryId)
            assertNotNull(stored)
            assertFalse(stored!!.isActive)

            // The historical entry is still there, still filed under the same category.
            val ledger = transactionDao.observeAll().first()
            assertEquals(1, ledger.size)
            assertEquals(categoryId, ledger.single().categoryId)
        }
    }

    /**
     * The foreign key's `ON DELETE RESTRICT`, asserted directly. Deleting a category the household
     * is still using has to fail rather than quietly take its transactions with it.
     */
    @Test
    fun deletingACategoryThatStillHasTransactionsIsRefused() {
        runBlocking {
            val categoryId = categoryDao.insert(category("Milk", TransactionType.EXPENSE))
            transactionDao.insert(transaction(categoryId = categoryId))

            assertConstraintViolation {
                database.openHelper.writableDatabase
                    .execSQL("DELETE FROM categories WHERE id = $categoryId")
            }

            assertNotNull(categoryDao.getById(categoryId))
            assertEquals(1, transactionDao.observeAll().first().size)
        }
    }

    @Test
    fun updateReplacesTheStoredRow() {
        runBlocking {
            val categoryId = categoryDao.insert(category("Milk", TransactionType.EXPENSE))

            categoryDao.update(
                categoryDao.getById(categoryId)!!.copy(name = "Dairy", isActive = false),
            )

            val stored = categoryDao.getById(categoryId)!!
            assertEquals("Dairy", stored.name)
            assertFalse(stored.isActive)
        }
    }
}
