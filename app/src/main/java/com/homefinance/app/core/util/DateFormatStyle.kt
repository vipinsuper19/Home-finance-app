package com.homefinance.app.core.util

/**
 * The date layouts the household can choose between in Settings.
 *
 * The three are the ones the design offers, and each is pinned rather than taken from the device's
 * locale: a household that picked "05/11/2026" must see that layout on every screen even if the
 * device language later changes, and the stored [name] is what survives a restart.
 *
 * This is a *display* preference only. Dates are still stored as an ISO epoch day, so changing the
 * layout can never change which day a transaction belongs to - see [DateFormatter].
 */
enum class DateFormatStyle {

    /** `5 Nov 2026` - the shipped default, and the layout every screen used before this option. */
    DAY_MONTH_YEAR,

    /** `05/11/2026` - day first, so October and November are never confused. */
    DAY_MONTH_NUMERIC,

    /** `2026-11-05` - the ISO order, for a household that reads it as unambiguous. */
    ISO;

    companion object {

        /**
         * The style stored under [stored], or [DAY_MONTH_YEAR] when it is missing or unrecognised.
         *
         * A value written by a newer build is deliberately not a crash: the app falls back to the
         * layout it has always used rather than refusing to open.
         */
        fun fromStorage(stored: String?): DateFormatStyle =
            entries.firstOrNull { it.name == stored } ?: DAY_MONTH_YEAR
    }
}
