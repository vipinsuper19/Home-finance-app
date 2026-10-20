package com.homefinance.app.data.model

import com.homefinance.app.core.ui.theme.ThemeMode
import com.homefinance.app.core.util.DateFormatStyle
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preference snapshot and its storage keys.
 *
 * Everything here is about one question: what does a missing, unknown or corrupt row mean? The answer
 * has to be "the behaviour that shipped", never a crash and never an arbitrary value, because these
 * rows are written by the app itself and read on every start.
 */
class AppPreferencesTest {

    @Test
    fun anEmptyStoreIsTheBehaviourTheAppShippedWith() {
        val preferences = AppPreferences.from(emptyMap())

        assertEquals(ThemeMode.LIGHT, preferences.themeMode)
        assertEquals(AppLanguage.ENGLISH, preferences.language)
        assertEquals(AppCurrency.INR, preferences.currency)
        assertEquals(DateFormatStyle.DAY_MONTH_YEAR, preferences.dateFormat)
        assertTrue(preferences.showBalanceOnHome)
        assertTrue(preferences.confirmBeforeDelete)
        assertTrue(preferences.hapticFeedback)
        assertNull(preferences.lastBackupAt)
        assertNull(preferences.lastBackupBytes)
    }

    @Test
    fun everyStoredValueIsReadBack() {
        val stored = mapOf(
            SettingsKeys.THEME_MODE to "DARK",
            SettingsKeys.LANGUAGE_TAG to "en",
            SettingsKeys.CURRENCY_CODE to "INR",
            SettingsKeys.DATE_FORMAT to "ISO",
            SettingsKeys.SHOW_BALANCE_ON_HOME to "false",
            SettingsKeys.CONFIRM_BEFORE_DELETE to "false",
            SettingsKeys.HAPTIC_FEEDBACK to "false",
            SettingsKeys.LAST_BACKUP_AT to "2026-11-05T10:15:30Z",
            SettingsKeys.LAST_BACKUP_BYTES to "4096",
        )

        val preferences = AppPreferences.from(stored)

        assertEquals(ThemeMode.DARK, preferences.themeMode)
        assertEquals(DateFormatStyle.ISO, preferences.dateFormat)
        assertFalse(preferences.showBalanceOnHome)
        assertFalse(preferences.confirmBeforeDelete)
        assertFalse(preferences.hapticFeedback)
        assertEquals(Instant.parse("2026-11-05T10:15:30Z"), preferences.lastBackupAt)
        assertEquals(4096L, preferences.lastBackupBytes)
    }

    @Test
    fun unknownNamesKeepTheDefaultInsteadOfCrashing() {
        val preferences = AppPreferences.from(
            mapOf(
                SettingsKeys.THEME_MODE to "SOLARIZED",
                SettingsKeys.CURRENCY_CODE to "USD",
                SettingsKeys.DATE_FORMAT to "STARDATE",
                SettingsKeys.LANGUAGE_TAG to "xx",
            ),
        )

        assertEquals(ThemeMode.LIGHT, preferences.themeMode)
        assertEquals(AppCurrency.INR, preferences.currency)
        assertEquals(DateFormatStyle.DAY_MONTH_YEAR, preferences.dateFormat)
        assertEquals(AppLanguage.ENGLISH, preferences.language)
    }

    @Test
    fun aValueThatIsNotExactlyTrueOrFalseKeepsTheDefault() {
        val preferences = AppPreferences.from(
            mapOf(
                SettingsKeys.SHOW_BALANCE_ON_HOME to "yes",
                SettingsKeys.CONFIRM_BEFORE_DELETE to "0",
                SettingsKeys.HAPTIC_FEEDBACK to "TRUE",
            ),
        )

        assertTrue(preferences.showBalanceOnHome)
        assertTrue(preferences.confirmBeforeDelete)
        assertTrue(preferences.hapticFeedback)
    }

    @Test
    fun aTimestampOrSizeThatCannotBeReadIsTreatedAsNoBackup() {
        val preferences = AppPreferences.from(
            mapOf(
                SettingsKeys.LAST_BACKUP_AT to "not a timestamp",
                SettingsKeys.LAST_BACKUP_BYTES to "-1",
            ),
        )

        assertNull(preferences.lastBackupAt)
        assertNull(preferences.lastBackupBytes)
    }

    @Test
    fun theBackupMetadataKeysAreTheOnesExcludedFromAFile() {
        // The backup file is the household's finances, not this phone's backup history.
        assertEquals(
            setOf(SettingsKeys.LAST_BACKUP_AT, SettingsKeys.LAST_BACKUP_BYTES),
            BackupDocument.DEVICE_ONLY_SETTING_KEYS,
        )
    }
}
