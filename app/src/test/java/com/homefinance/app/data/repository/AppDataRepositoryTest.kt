package com.homefinance.app.data.repository

import com.homefinance.app.data.local.entity.AppSettingEntity
import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.data.local.entity.RecurringOccurrenceEntity
import com.homefinance.app.data.local.entity.RecurringScheduleEntity
import com.homefinance.app.data.local.entity.TransactionEntity
import com.homefinance.app.data.model.RecurrenceFrequency
import com.homefinance.app.data.model.RecurringOccurrenceStatus
import com.homefinance.app.data.model.RecurringScheduleStatus
import com.homefinance.app.data.model.SettingsKeys
import com.homefinance.app.data.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Clear app data" through the DAOs.
 *
 * The two things worth proving are that every table is emptied, and that the seeder's flag is the one
 * row written back - without it, the next start would treat the app as a fresh install and re-create
 * the starter categories, which is not what "clear everything" means.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppDataRepositoryTest {

    private val instant = Instant.parse("2026-11-05T10:15:30Z")

    @Test
    fun everyRowIsDeletedIncludingPreferences() = runTest {
        val categories = FakeCategoryDao(
            listOf(CategoryEntity.create("Groceries", TransactionType.EXPENSE).copy(id = 10L)),
        )
        val transactions = FakeTransactionDao(
            categoryNames = mapOf(10L to "Groceries"),
            initial = listOf(
                TransactionEntity(
                    id = 1L,
                    type = TransactionType.EXPENSE,
                    amountPaise = 1_000L,
                    categoryId = 10L,
                    transactionDate = LocalDate.of(2026, 11, 5),
                    createdAt = instant,
                    updatedAt = instant,
                ),
            ),
        )
        val recurring = FakeRecurringDao(
            categoryNames = mapOf(10L to "Groceries"),
            initialSchedules = listOf(
                RecurringScheduleEntity(
                    id = 2L,
                    name = "Rent",
                    categoryId = 10L,
                    amountPaise = 100_000L,
                    frequency = RecurrenceFrequency.MONTHLY,
                    intervalCount = 1,
                    startDate = LocalDate.of(2026, 11, 5),
                    status = RecurringScheduleStatus.ACTIVE,
                    createdAt = instant,
                    updatedAt = instant,
                ),
            ),
            initialOccurrences = listOf(
                RecurringOccurrenceEntity(
                    id = 3L,
                    scheduleId = 2L,
                    dueDate = LocalDate.of(2026, 11, 5),
                    amountPaise = 100_000L,
                    status = RecurringOccurrenceStatus.PENDING,
                    createdAt = instant,
                    updatedAt = instant,
                ),
            ),
        )
        val settings = FakeAppSettingDao(mapOf(SettingsKeys.THEME_MODE to "DARK"))

        val repository = AppDataRepository(
            categoryDao = categories,
            transactionDao = transactions,
            recurringDao = recurring,
            appSettingDao = settings,
            transactionRunner = DirectTransactionRunner,
        )

        repository.clearAll()

        assertTrue(categories.getAll().isEmpty())
        assertTrue(transactions.getAll().isEmpty())
        assertTrue(recurring.getAllSchedules().isEmpty())
        assertTrue(recurring.getAllOccurrences().isEmpty())
        assertEquals(emptySet<String>(), settings.stored.keys - CategorySeeder.SEEDED_KEY)
        assertEquals(CategorySeeder.SEEDED_VALUE, settings.stored[CategorySeeder.SEEDED_KEY])
    }

    @Test
    fun theFlagIsWrittenSoTheNextStartDoesNotRecreateTheStarterCategories() = runTest {
        val settings = FakeAppSettingDao()

        AppDataRepository(
            categoryDao = FakeCategoryDao(),
            transactionDao = FakeTransactionDao(),
            recurringDao = FakeRecurringDao(),
            appSettingDao = settings,
            transactionRunner = DirectTransactionRunner,
        ).clearAll()

        // CategorySeeder asks exactly this question before seeding.
        assertEquals(CategorySeeder.SEEDED_VALUE, settings.stored[CategorySeeder.SEEDED_KEY])
        assertTrue(settings.written.any { it.key == CategorySeeder.SEEDED_KEY })
    }

    @Test
    fun aFailedWipeDoesNotWriteTheSeederFlagEither() = runTest {
        val transactions = FakeTransactionDao()
        transactions.deleteFailure = IllegalStateException("disk full")
        val settings = FakeAppSettingDao()

        val repository = AppDataRepository(
            categoryDao = FakeCategoryDao(),
            transactionDao = transactions,
            recurringDao = FakeRecurringDao(),
            appSettingDao = settings,
            transactionRunner = DirectTransactionRunner,
        )

        val failure = runCatching { repository.clearAll() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(settings.stored.isEmpty())
    }
}
