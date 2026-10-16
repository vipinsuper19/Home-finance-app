package com.homefinance.app.data.repository

import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.data.model.TransactionType
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules a category has to satisfy before it reaches the database.
 *
 * Duplicate-name rejection is deliberately absent here: it is a property of the unique index on
 * the real schema, so it is asserted in `com.homefinance.app.data.local.CategoryDaoTest`. The fake
 * DAO used here does not pretend to enforce it, which is why it would be dishonest to assert it.
 */
class CategoryRepositoryTest {

    private val now = Instant.parse("2026-10-16T06:30:00Z")

    private val dao = FakeCategoryDao()
    private val repository = CategoryRepository(dao)

    @Test
    fun createNormalizesTheNameBeforeStoringIt() {
        runBlocking {
            repository.create("   House   Rent ", TransactionType.EXPENSE)

            val stored = dao.inserted.single()
            assertEquals("House Rent", stored.name)
            assertEquals(TransactionType.EXPENSE, stored.type)
            assertTrue(stored.isActive)
        }
    }

    @Test
    fun createReturnsTheNewId() {
        runBlocking {
            val id = repository.create("Groceries", TransactionType.EXPENSE)

            assertNotNull(repository.getById(id))
        }
    }

    @Test
    fun createRejectsABlankNameAndWritesNothing() {
        runBlocking {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.create("   ", TransactionType.EXPENSE) }
            }

            assertTrue(dao.inserted.isEmpty())
        }
    }

    @Test
    fun renameNormalizesTheNewName() {
        runBlocking {
            val id = repository.create("Milk", TransactionType.EXPENSE)

            assertTrue(repository.rename(id, "  Milk  &  Curd "))

            assertEquals("Milk & Curd", repository.getById(id)!!.name)
        }
    }

    @Test
    fun renameLeavesTheTypeAndCreationTimeAlone() {
        runBlocking {
            val id = repository.create("Milk", TransactionType.EXPENSE)

            repository.rename(id, "Dairy")

            val stored = repository.getById(id)!!
            assertEquals(TransactionType.EXPENSE, stored.type)
            assertEquals(dao.inserted.single().createdAt, stored.createdAt)
        }
    }

    @Test
    fun renameReportsAnUnknownCategoryInsteadOfFailingSilently() {
        runBlocking {
            assertFalse(repository.rename(9_999L, "Anything"))
        }
    }

    @Test
    fun renameRejectsABlankNameAndLeavesTheRowUntouched() {
        runBlocking {
            val id = repository.create("Milk", TransactionType.EXPENSE)

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.rename(id, "  ") }
            }

            assertEquals("Milk", repository.getById(id)!!.name)
        }
    }

    @Test
    fun deactivateRetiresTheCategoryWithoutRemovingIt() {
        runBlocking {
            val id = repository.create("Old Rent", TransactionType.EXPENSE)

            repository.deactivate(id)

            val stored = repository.getById(id)
            assertNotNull(stored)
            assertFalse(stored!!.isActive)
        }
    }

    @Test
    fun observeAllExposesEveryCategoryIncludingRetiredOnes() {
        runBlocking {
            repository.create("Milk", TransactionType.EXPENSE)
            val retired = repository.create("Old Rent", TransactionType.EXPENSE)
            repository.deactivate(retired)

            assertEquals(2, repository.observeAll().first().size)
        }
    }

    @Test
    fun observeByTypeExposesOnlyThatType() {
        runBlocking {
            repository.create("Milk", TransactionType.EXPENSE)
            repository.create("Salary", TransactionType.INCOME)

            val expenses: List<CategoryEntity> =
                repository.observeByType(TransactionType.EXPENSE).first()

            assertEquals(listOf("Milk"), expenses.map { it.name })
        }
    }
}
