package com.homefinance.app.core.ui.theme

/**
 * Which palette the app draws with.
 *
 * [SYSTEM] is the only mode whose answer is not fixed: it follows the device's dark-mode setting,
 * which is why it is resolved in [HomeFinanceTheme] rather than stored as "the theme is dark".
 */
enum class ThemeMode {

    /** The white and emerald palette the app shipped with. */
    LIGHT,

    /** The same emerald brand on dark neutral surfaces. */
    DARK,

    /** Follow the device: dark when the system is in dark mode, light otherwise. */
    SYSTEM;

    companion object {

        /**
         * The mode stored under [stored], or [LIGHT] when it is missing or unrecognised.
         *
         * [LIGHT] rather than [SYSTEM] is the default on purpose: it is what every existing
         * installation already looks like, so an upgrade cannot silently restyle the app.
         */
        fun fromStorage(stored: String?): ThemeMode =
            entries.firstOrNull { it.name == stored } ?: LIGHT
    }
}
