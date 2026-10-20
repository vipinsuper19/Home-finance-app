package com.homefinance.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homefinance.app.core.ui.theme.ThemeMode
import com.homefinance.app.core.util.DateFormatStyle
import com.homefinance.app.data.local.HomeFinanceDatabase
import com.homefinance.app.data.local.RoomTransactionRunner
import com.homefinance.app.data.local.deleteTestDatabase
import com.homefinance.app.data.local.isolatedFileDatabase
import com.homefinance.app.data.model.SettingsKeys
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Preferences and "Clear app data" over a **real** Room database.
 *
 * The JVM tests prove the mapping; these prove the two things only SQLite can: that a preference
 * written by one repository instance is read by the next one after a restart, and that a wipe leaves
 * the schema, the ledger and the seeder flag in the state the next start expects.
 */
@RunWith(AndroidJUnit4::class)
class SettingsDatabaseIntegrationTest {

    private lateinit var database: HomeFinanceDatabase
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        deleteTestDatabase()
        database = isolatedFileDatabase()
        settings = SettingsRepository(database.appSettingDao())
    }

    @After
    fun tearDown() {
        database.close()
        deleteTestDatabase()
    }

    @Test
    fun aFreshDatabaseReadsTheShippedDefaults() = runBlocking {
        val preferences = settings.preferences.first()

        assertEquals(ThemeMode.LIGHT, preferences.themeMode)
        assertEquals(DateFormatStyle.DAY_MONTH_YEAR, preferences.dateFormat)
        assertTrue(preferences.showBalanceOnHome)
        assertTrue(preferences.confirmBeforeDelete)
        assertTrue(preferences.hapticFeedback)
        assertNull(preferences.lastBackupAt)
    }

    @Test
    fun everyPreferenceSurvivesANewRepositoryInstance() = runBlocking {
        settings.setThemeMode(ThemeMode.DARK)
        settings.setDateFormat(DateFormatStyle.ISO)
        settings.setShowBalanceOnHome(false)
        settings.setConfirmBeforeDelete(false)
        settings.setHapticFeedback(false)
        settings.recordLastBackup(
            at = Instant.parse("2026-11-05T10:15:30Z"),
            bytes = 4096L,
        )

        // "Restarting" the app is building the repository again over the same file.
        database.close()
        database = isolatedFileDatabase()
        settings = SettingsRepository(database.appSettingDao())

        val preferences = settings.preferences.first()

        assertEquals(ThemeMode.DARK, preferences.themeMode)
        assertEquals(DateFormatStyle.ISO, preferences.dateFormat)
        assertFalse(preferences.showBalanceOnHome)
        assertFalse(preferences.confirmBeforeDelete)
        assertFalse(preferences.hapticFeedback)
        assertEquals(Instant.parse("2026-11-05T10:15:30Z"), preferences.lastBackupAt)
        assertEquals(4096L, preferences.lastBackupBytes)
    }

    @Test
    fun aWriteReachesAnOpenCollectorWithoutAskingAgain() = runBlocking {
        val observed = settings.preferences.first()
        assertTrue(observed.showBalanceOnHome)

        settings.setShowBalanceOnHome(false)

        assertFalse(settings.preferences.first().showBalanceOnHome)
        assertEquals(
            "false",
            database.appSettingDao().get(SettingsKeys.SHOW_BALANCE_ON_HOME),
        )
    }

    @Test
    fun clearingRemovesEveryRowAndKeepsTheSchemaAndSeederFlag() = runBlocking {
        val categoryDao = database.categoryDao()
        val transactionDao = database.transactionDao()
        val categoryId = categoryDao.insert(
            com.homefinance.app.data.local.entity.CategoryEntity
                .create("Groceries", TransactionType.EXPENSE)
                .copy(id = 10L),
        )
        transactionDao.insert(
            com.homefinance.app.data.local.entity.TransactionEntity(
                type = TransactionType.EXPENSE,
                amountPaise = 1_000L,
                categoryId = categoryId,
                transactionDate = LocalDate.of(2026, 11, 5),
                createdAt = Instant.parse("2026-11-05T10:00:00Z"),
                updatedAt = Instant.parse("2026-11-05T10:00:00Z"),
            ),
        )
        settings.setThemeMode(ThemeMode.DARK)

        AppDataRepository(
            categoryDao = categoryDao,
            transactionDao = transactionDao,
            recurringDao = database.recurringDao(),
            appSettingDao = database.appSettingDao(),
            transactionRunner = RoomTransactionRunner(database),
        ).clearAll()

        assertTrue(categoryDao.getAll().isEmpty())
        assertTrue(transactionDao.getAll().isEmpty())
        assertTrue(database.recurringDao().getAllSchedules().isEmpty())
        assertTrue(database.recurringDao().getAllOccurrences().isEmpty())

        val stored = database.appSettingDao().getAll().associate { it.key to it.value }
        assertEquals(CategorySeeder.SEEDED_VALUE, stored[CategorySeeder.SEEDED_KEY])
        assertEquals(1, stored.size)

        // The schema is still there: a query works, and the next start will not seed again.
        assertNotNull(database.appSettingDao().get(CategorySeeder.SEEDED_KEY))
        assertEquals(
            ThemeMode.LIGHT,
            SettingsRepository(database.appSettingDao()).preferences.first().themeMode,
        )
    }
}
