package com.homefinance.app.data.repository

import com.homefinance.app.data.local.dao.AppSettingDao
import com.homefinance.app.data.local.entity.AppSettingEntity
import com.homefinance.app.data.model.AppCurrency
import com.homefinance.app.data.model.AppLanguage
import com.homefinance.app.data.model.AppPreferences
import com.homefinance.app.data.model.SettingsKeys
import com.homefinance.app.core.ui.theme.ThemeMode
import com.homefinance.app.core.util.DateFormatStyle
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The one way the rest of the app reads and writes preferences.
 *
 * ### Why this replaces a second storage mechanism
 *
 * The app already had an `app_setting` table for the "categories have been seeded" flag, so adding
 * a DataStore (or a shared-preferences file) for Settings would have meant two places a preference
 * can live, two things to clear, and two answers to "what is the theme?". This repository keeps the
 * one table and gives it a typed surface: screens ask for an [AppPreferences] snapshot, never for a
 * key, and every write goes through a named setter.
 *
 * ### Why the read is a `Flow`
 *
 * A preference is global: changing the theme on the Theme screen must restyle the app behind it, and
 * turning the balance off on the App Settings screen must change Home without a restart. The shell
 * collects [preferences] once and passes the result down, so a write reaches every screen through
 * ordinary recomposition.
 *
 * The DAO is injected as an interface rather than taken from the database, which is what keeps the
 * mapping testable on the JVM without a device.
 */
class SettingsRepository(private val appSettingDao: AppSettingDao) {

    /** The current preferences, re-emitted whenever any stored value changes. */
    val preferences: Flow<AppPreferences> =
        appSettingDao.observeAll().map(::toPreferences)

    /**
     * A one-shot read, for work that is not drawing a screen.
     *
     * A confirmed delete asks this question once, immediately before it acts; subscribing to the
     * whole preference flow just to read one boolean would keep a collector alive for the lifetime of
     * the screen for an answer that is used once.
     */
    suspend fun current(): AppPreferences = toPreferences(appSettingDao.getAll())

    suspend fun setThemeMode(mode: ThemeMode) = put(SettingsKeys.THEME_MODE, mode.name)

    suspend fun setLanguage(language: AppLanguage) =
        put(SettingsKeys.LANGUAGE_TAG, language.tag)

    suspend fun setCurrency(currency: AppCurrency) =
        put(SettingsKeys.CURRENCY_CODE, currency.code)

    suspend fun setDateFormat(style: DateFormatStyle) =
        put(SettingsKeys.DATE_FORMAT, style.name)

    suspend fun setShowBalanceOnHome(show: Boolean) =
        put(SettingsKeys.SHOW_BALANCE_ON_HOME, show.toString())

    suspend fun setConfirmBeforeDelete(confirm: Boolean) =
        put(SettingsKeys.CONFIRM_BEFORE_DELETE, confirm.toString())

    suspend fun setHapticFeedback(enabled: Boolean) =
        put(SettingsKeys.HAPTIC_FEEDBACK, enabled.toString())

    /**
     * Records that a backup of [bytes] was written at [at], **after** the file is safely on disk.
     *
     * The two are written together because they are one fact: a timestamp with no size would be a
     * half-remembered backup, and the screen would have to guess the other half.
     */
    suspend fun recordLastBackup(at: Instant, bytes: Long) {
        appSettingDao.putAll(
            listOf(
                AppSettingEntity(SettingsKeys.LAST_BACKUP_AT, at.toString()),
                AppSettingEntity(SettingsKeys.LAST_BACKUP_BYTES, bytes.toString()),
            ),
        )
    }

    private suspend fun put(key: String, value: String) {
        appSettingDao.put(AppSettingEntity(key = key, value = value))
    }

    private fun toPreferences(rows: List<AppSettingEntity>): AppPreferences =
        AppPreferences.from(rows.associate { it.key to it.value })
}
