package com.homefinance.app.data.model

import com.homefinance.app.core.ui.theme.ThemeMode
import com.homefinance.app.core.util.DateFormatStyle
import java.time.Instant

/**
 * The languages the app genuinely ships in.
 *
 * The list is the contract with the translations that actually exist: one entry per `values-*`
 * directory, so the Language screen can never offer a language whose strings are missing. Only
 * English has resources today; adding `values-hi` and a [HINDI] entry here is the whole change the
 * screen needs, and until then the screen says so rather than pretending.
 */
enum class AppLanguage(
    val tag: String,
    /** The language's name in English, which is what the list is sorted and labelled by. */
    val displayName: String,
) {
    ENGLISH(tag = "en", displayName = "English");

    companion object {
        fun fromTag(stored: String?): AppLanguage =
            entries.firstOrNull { it.tag == stored } ?: ENGLISH
    }
}

/**
 * The currencies the ledger can actually be recorded in.
 *
 * This is deliberately a one-entry list. Every amount in the database is an integer number of
 * **paise** - one hundredth of a rupee - and the entry form, the Indian digit grouping and every
 * figure on screen are built around that unit. Offering a second currency as a *display* option
 * would relabel a household's rupees as dollars without converting anything, which is precisely the
 * misleading behaviour the currency setting must not have. A second entry here is only honest once
 * amounts can be recorded in that currency, and then the formatter and the input form change with
 * it.
 */
enum class AppCurrency(
    val code: String,
    val symbol: String,
    val displayName: String,
) {
    INR(code = "INR", symbol = "\u20B9", displayName = "Indian Rupee");

    companion object {
        fun fromCode(stored: String?): AppCurrency =
            entries.firstOrNull { it.code == stored } ?: INR
    }
}

/**
 * Every preference the app stores, as one immutable snapshot.
 *
 * It is built from [com.homefinance.app.data.local.entity.AppSettingEntity] rows by
 * [com.homefinance.app.data.repository.SettingsRepository], and it is the *only* shape the rest of
 * the app reads preferences in - a screen never asks the table for a key.
 *
 * The defaults are the behaviour that shipped before Settings existed: light theme, English, rupees,
 * `5 Nov 2026`, a visible balance, a delete confirmation. An upgrade therefore changes nothing until
 * the household changes something, and a key that is missing (or was written by a newer build)
 * falls back to that behaviour rather than to an arbitrary zero value.
 */
data class AppPreferences(
    val themeMode: ThemeMode = ThemeMode.LIGHT,
    val language: AppLanguage = AppLanguage.ENGLISH,
    val currency: AppCurrency = AppCurrency.INR,
    val dateFormat: DateFormatStyle = DateFormatStyle.DAY_MONTH_YEAR,
    /** Whether the Home dashboard draws the household's balance. */
    val showBalanceOnHome: Boolean = true,
    /** Whether deleting a transaction asks first. Full-data operations always ask. */
    val confirmBeforeDelete: Boolean = true,
    /** Whether selection controls and confirmed deletes give a tactile tick. */
    val hapticFeedback: Boolean = true,
    /** When this device last wrote a backup successfully, or `null` if it never has. */
    val lastBackupAt: Instant? = null,
    /** How many bytes that backup was, or `null` when no backup has been written on this device. */
    val lastBackupBytes: Long? = null,
) {

    companion object {

        /**
         * Reads a snapshot out of the stored key/value rows, falling back to the shipped defaults.
         *
         * Every value is parsed defensively: a boolean is only `false` when it is literally `false`,
         * an instant is only used when it parses, and an unknown enum name keeps the default. A
         * corrupt row is therefore a preference that stays at its default, never a crash on start.
         */
        fun from(values: Map<String, String>): AppPreferences = AppPreferences(
            themeMode = ThemeMode.fromStorage(values[SettingsKeys.THEME_MODE]),
            language = AppLanguage.fromTag(values[SettingsKeys.LANGUAGE_TAG]),
            currency = AppCurrency.fromCode(values[SettingsKeys.CURRENCY_CODE]),
            dateFormat = DateFormatStyle.fromStorage(values[SettingsKeys.DATE_FORMAT]),
            showBalanceOnHome = values[SettingsKeys.SHOW_BALANCE_ON_HOME].toBooleanOr(default = true),
            confirmBeforeDelete = values[SettingsKeys.CONFIRM_BEFORE_DELETE].toBooleanOr(default = true),
            hapticFeedback = values[SettingsKeys.HAPTIC_FEEDBACK].toBooleanOr(default = true),
            lastBackupAt = values[SettingsKeys.LAST_BACKUP_AT]?.let { stored ->
                runCatching { Instant.parse(stored) }.getOrNull()
            },
            lastBackupBytes = values[SettingsKeys.LAST_BACKUP_BYTES]?.toLongOrNull()?.takeIf { it >= 0L },
        )

        /** Only the exact text `true` or `false` is accepted; anything else keeps [default]. */
        private fun String?.toBooleanOr(default: Boolean): Boolean = when (this) {
            "true" -> true
            "false" -> false
            else -> default
        }
    }
}

/**
 * The `app_setting` keys a preference is stored under.
 *
 * They are namespaced by feature rather than by screen, and they are never reused for a different
 * meaning: a key that changes meaning is a new key, so a value written by an older build can only be
 * ignored, never misread.
 */
object SettingsKeys {
    const val THEME_MODE = "settings_theme_mode"
    const val LANGUAGE_TAG = "settings_language_tag"
    const val CURRENCY_CODE = "settings_currency_code"
    const val DATE_FORMAT = "settings_date_format"
    const val SHOW_BALANCE_ON_HOME = "settings_show_balance_on_home"
    const val CONFIRM_BEFORE_DELETE = "settings_confirm_before_delete"
    const val HAPTIC_FEEDBACK = "settings_haptic_feedback"
    const val LAST_BACKUP_AT = "settings_last_backup_at"
    const val LAST_BACKUP_BYTES = "settings_last_backup_bytes"
}
