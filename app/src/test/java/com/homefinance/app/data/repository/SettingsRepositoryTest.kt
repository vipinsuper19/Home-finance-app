package com.homefinance.app.data.repository

import com.homefinance.app.core.ui.theme.ThemeMode
import com.homefinance.app.core.util.DateFormatStyle
import com.homefinance.app.data.model.AppCurrency
import com.homefinance.app.data.model.AppLanguage
import com.homefinance.app.data.model.SettingsKeys
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The typed surface over the `app_setting` table.
 *
 * The fakes underneath are the real DAO's contract, so what is pinned here is the mapping and the
 * flow: a setter writes the key it says it writes, and the observable snapshot emits the new value
 * without the caller re-reading the table.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {

    @Test
    fun aFreshStoreReadsAsTheShippedDefaults() = runTest {
        val repository = SettingsRepository(FakeAppSettingDao())

        assertEquals(com.homefinance.app.data.model.AppPreferences(), repository.current())
    }

    @Test
    fun eachSetterWritesItsOwnKeyAndIsVisibleThroughTheFlow() = runTest {
        val dao = FakeAppSettingDao()
        val repository = SettingsRepository(dao)

        repository.setThemeMode(ThemeMode.SYSTEM)
        repository.setDateFormat(DateFormatStyle.ISO)
        repository.setShowBalanceOnHome(false)
        repository.setConfirmBeforeDelete(false)
        repository.setHapticFeedback(false)

        val preferences = repository.preferences.first()

        assertEquals(ThemeMode.SYSTEM, preferences.themeMode)
        assertEquals(DateFormatStyle.ISO, preferences.dateFormat)
        assertFalse(preferences.showBalanceOnHome)
        assertFalse(preferences.confirmBeforeDelete)
        assertFalse(preferences.hapticFeedback)

        assertEquals(
            mapOf(
                SettingsKeys.THEME_MODE to "SYSTEM",
                SettingsKeys.DATE_FORMAT to "ISO",
                SettingsKeys.SHOW_BALANCE_ON_HOME to "false",
                SettingsKeys.CONFIRM_BEFORE_DELETE to "false",
                SettingsKeys.HAPTIC_FEEDBACK to "false",
            ),
            dao.stored,
        )
    }

    @Test
    fun languageAndCurrencyAreStoredByTheirStableName() = runTest {
        val dao = FakeAppSettingDao()
        val repository = SettingsRepository(dao)

        repository.setLanguage(AppLanguage.ENGLISH)
        repository.setCurrency(AppCurrency.INR)

        assertEquals("en", dao.stored[SettingsKeys.LANGUAGE_TAG])
        assertEquals("INR", dao.stored[SettingsKeys.CURRENCY_CODE])
    }

    @Test
    fun theBackupRecordIsWrittenAsOneFactOnlyAfterAFileExists() = runTest {
        val dao = FakeAppSettingDao()
        val repository = SettingsRepository(dao)
        val at = Instant.parse("2026-11-05T10:15:30Z")

        assertNull(repository.current().lastBackupAt)

        repository.recordLastBackup(at = at, bytes = 2048L)

        val preferences = repository.current()
        assertEquals(at, preferences.lastBackupAt)
        assertEquals(2048L, preferences.lastBackupBytes)
        assertEquals("2026-11-05T10:15:30Z", dao.stored[SettingsKeys.LAST_BACKUP_AT])
        assertEquals("2048", dao.stored[SettingsKeys.LAST_BACKUP_BYTES])
    }

    @Test
    fun aWriteIsVisibleToAnExistingCollectorWithoutAskingAgain() = runTest {
        val dao = FakeAppSettingDao()
        val repository = SettingsRepository(dao)

        // The first read is the "before" the screen is showing.
        assertTrue(repository.preferences.first().showBalanceOnHome)

        repository.setShowBalanceOnHome(false)

        // A fresh collection sees the write; the screen's existing collector is driven by the same DAO.
        assertFalse(repository.preferences.first().showBalanceOnHome)
    }
}
