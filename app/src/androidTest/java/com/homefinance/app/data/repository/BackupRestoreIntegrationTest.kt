package com.homefinance.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homefinance.app.data.local.HomeFinanceDatabase
import com.homefinance.app.data.local.RoomTransactionRunner
import com.homefinance.app.data.local.category
import com.homefinance.app.data.local.inMemoryDatabase
import com.homefinance.app.data.local.occurrence
import com.homefinance.app.data.local.schedule
import com.homefinance.app.data.local.transaction
import com.homefinance.app.data.model.BackupDocument
import com.homefinance.app.data.model.RecurringOccurrenceStatus
import com.homefinance.app.data.model.SettingsKeys
import com.homefinance.app.data.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Backup and restore against real SQLite, with the real foreign keys switched on.
 *
 * The JVM tests prove the file's shape and the validator's rules. What only a real database can prove
 * is that the rows the file names can actually be written in the order the restore writes them, that
 * an occurrence still points at its schedule and payment afterwards, and that a failure part-way
 * through really does roll the whole restore back.
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreIntegrationTest {

    private lateinit var source: HomeFinanceDatabase
    private lateinit var target: HomeFinanceDatabase

    private val instant = Instant.parse("2026-11-05T10:15:30Z")

    @Before
    fun setUp() {
        source = inMemoryDatabase()
        target = inMemoryDatabase()
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
    }

    private fun repository(database: HomeFinanceDatabase): BackupRepository = BackupRepository(
        categoryDao = database.categoryDao(),
        transactionDao = database.transactionDao(),
        recurringDao = database.recurringDao(),
        appSettingDao = database.appSettingDao(),
        transactionRunner = RoomTransactionRunner(database),
        clock = { instant },
    )

    /**
     * Seeds a complete household: two categories, an expense and an income, one schedule, and one
     * paid occurrence linked to the expense.
     */
    private fun seed(database: HomeFinanceDatabase) = runBlocking {
        database.categoryDao().insertAll(
            listOf(
                category("Groceries", TransactionType.EXPENSE).copy(id = 10L),
                category("Salary", TransactionType.INCOME).copy(id = 11L),
            ),
        )
        database.transactionDao().insertAll(
            listOf(
                transaction(
                    categoryId = 10L,
                    amountPaise = 25_050L,
                    description = "Milk, bread",
                    transactionDate = LocalDate.of(2026, 11, 5),
                ).copy(id = 100L),
                transaction(
                    categoryId = 11L,
                    type = TransactionType.INCOME,
                    amountPaise = 5_000_000L,
                    transactionDate = LocalDate.of(2026, 11, 1),
                ).copy(id = 101L),
            ),
        )
        database.recurringDao().insertSchedules(
            listOf(schedule(categoryId = 10L).copy(id = 200L)),
        )
        database.recurringDao().insertOccurrences(
            listOf(
                occurrence(
                    scheduleId = 200L,
                    dueDate = LocalDate.of(2026, 11, 5),
                    status = RecurringOccurrenceStatus.PAID,
                    transactionId = 100L,
                ).copy(id = 300L),
            ),
        )
        database.appSettingDao().putAll(
            listOf(
                com.homefinance.app.data.local.entity.AppSettingEntity(SettingsKeys.THEME_MODE, "DARK"),
                com.homefinance.app.data.local.entity.AppSettingEntity(SettingsKeys.SHOW_BALANCE_ON_HOME, "false"),
                com.homefinance.app.data.local.entity.AppSettingEntity(
                    SettingsKeys.LAST_BACKUP_AT,
                    "2026-10-01T09:00:00Z",
                ),
            ),
        )
    }

    @Test
    fun aBackupRestoresIntoAnEmptyDatabaseWithEveryReferenceIntact() = runBlocking {
        seed(source)
        val json = repository(source).create(appVersion = "1.0 (1)")

        val inspection = repository(target).inspect(json)
        assertTrue(inspection is RestoreInspection.Valid)

        val outcome = repository(target).restore((inspection as RestoreInspection.Valid).document)
        assertTrue(outcome is RestoreOutcome.Restored)

        val categories = target.categoryDao().getAll()
        assertEquals(listOf(10L, 11L), categories.map { it.id }.sorted())

        val transactions = target.transactionDao().getAll()
        assertEquals(listOf(100L, 101L), transactions.map { it.id }.sorted())
        assertEquals(10L, transactions.first { it.id == 100L }.categoryId)
        assertEquals(25_050L, transactions.first { it.id == 100L }.amountPaise)

        val schedules = target.recurringDao().getAllSchedules()
        assertEquals(listOf(200L), schedules.map { it.id })

        val occurrences = target.recurringDao().getAllOccurrences()
        assertEquals(1, occurrences.size)
        assertEquals(200L, occurrences.single().scheduleId)
        assertEquals(100L, occurrences.single().transactionId)
        assertEquals(RecurringOccurrenceStatus.PAID, occurrences.single().status)

        // Preferences travel; this device's own backup history does not.
        assertEquals("DARK", target.appSettingDao().get(SettingsKeys.THEME_MODE))
        assertEquals("false", target.appSettingDao().get(SettingsKeys.SHOW_BALANCE_ON_HOME))
        assertEquals(null, target.appSettingDao().get(SettingsKeys.LAST_BACKUP_AT))
    }

    @Test
    fun restoringReplacesEverythingThatWasThere() = runBlocking {
        seed(source)
        target.categoryDao().insert(
            com.homefinance.app.data.local.entity.CategoryEntity
                .create("Old bucket", TransactionType.EXPENSE)
                .copy(id = 90L),
        )
        val json = repository(source).create(appVersion = "1.0 (1)")

        repository(target).restore(
            (repository(target).inspect(json) as RestoreInspection.Valid).document,
        )

        assertEquals(listOf(10L, 11L), target.categoryDao().getAll().map { it.id }.sorted())
        assertEquals(null, target.categoryDao().getById(90L))
    }

    @Test
    fun aWriteFailureHalfWayThroughRollsTheWholeRestoreBack() = runBlocking {
        seed(target)
        val beforeCategories = target.categoryDao().getAll()
        val beforeTransactions = target.transactionDao().getAll()

        val json = run {
            // A valid, empty-but-for-one-category document, so the failure is the write's, not the
            // validator's. It passes every check and then the categories insert is made to fail.
            val document = BackupDocument(
                createdAt = instant.toString(),
                appVersion = "1.0 (1)",
                categories = listOf(
                    com.homefinance.app.data.model.BackupCategory(
                        id = 10L,
                        name = "Groceries",
                        type = TransactionType.EXPENSE.name,
                        iconKey = null,
                        isActive = true,
                        createdAt = instant.toString(),
                    ),
                ),
            )
            BackupDocument.json.encodeToString(BackupDocument.serializer(), document)
        }

        val failing = BackupRepository(
            categoryDao = FailingInsertCategoryDao(target.categoryDao()),
            transactionDao = target.transactionDao(),
            recurringDao = target.recurringDao(),
            appSettingDao = target.appSettingDao(),
            transactionRunner = RoomTransactionRunner(target),
        )

        val outcome = failing.restore(
            (failing.inspect(json) as RestoreInspection.Valid).document,
        )

        assertTrue(outcome is RestoreOutcome.Rejected)
        // Room rolled the deletes back too: the household's data is exactly as it was.
        assertEquals(beforeCategories.map { it.id }.sorted(), target.categoryDao().getAll().map { it.id }.sorted())
        assertEquals(
            beforeTransactions.map { it.id }.sorted(),
            target.transactionDao().getAll().map { it.id }.sorted(),
        )
        assertNotNull(target.appSettingDao().get(SettingsKeys.THEME_MODE))
    }

    /** A category DAO whose batch insert always fails, to reach the rollback path. */
    private class FailingInsertCategoryDao(
        private val delegate: com.homefinance.app.data.local.dao.CategoryDao,
    ) : com.homefinance.app.data.local.dao.CategoryDao by delegate {
        override suspend fun insertAll(
            categories: List<com.homefinance.app.data.local.entity.CategoryEntity>,
        ): List<Long> = throw IllegalStateException("disk full")
    }
}
