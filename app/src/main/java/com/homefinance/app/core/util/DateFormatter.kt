package com.homefinance.app.core.util

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Day, abbreviated month name, year - for example `15 Oct 2026`. */
private const val PATTERN = "d MMM yyyy"

/** Abbreviated month name, year - for example `Oct 2026`. */
private const val MONTH_PATTERN = "MMM yyyy"

/** Day, month, year, then the time on a 12-hour clock - for example `14 Oct 2026, 10:30 AM`. */
private const val TIMESTAMP_PATTERN = "d MMM yyyy, h:mm a"

/** The abbreviated month name alone - for example `Oct`. */
private const val MONTH_NAME_PATTERN = "MMM"

/** Day then abbreviated month, with no year - for example `1 Nov`. */
private const val DAY_MONTH_PATTERN = "d MMM"

/** The weekday alone - for example `Thursday`. */
private const val WEEKDAY_PATTERN = "EEEE"

/** The numeric date layout - day first, zero-padded, for example `05/11/2026`. */
private const val NUMERIC_PATTERN = "dd/MM/yyyy"

/** The numeric month layout, for example `11/2026`. */
private const val NUMERIC_MONTH_PATTERN = "MM/yyyy"

/** Day and month without a year, in the numeric layout - for example `05/11`. */
private const val NUMERIC_DAY_MONTH_PATTERN = "dd/MM"

/** The numeric timestamp layout - day first, with a 24-hour clock. */
private const val NUMERIC_TIMESTAMP_PATTERN = "dd/MM/yyyy, HH:mm"

/** The ISO date layout, for example `2026-11-05`. */
private const val ISO_PATTERN = "yyyy-MM-dd"

/** The ISO month layout, for example `2026-11`. */
private const val ISO_MONTH_PATTERN = "yyyy-MM"

/** Month and day without a year, in the ISO layout - for example `11-05`. */
private const val ISO_DAY_MONTH_PATTERN = "MM-dd"

/** The ISO timestamp layout, with a 24-hour clock. */
private const val ISO_TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm"

/**
 * Formats the calendar dates a household reads: the day a transaction's money moved.
 *
 * Three deliberate choices:
 *
 *  - **`LocalDate` in, `String` out - no `Instant`, no `Date`, no `ZoneId`.** A transaction date
 *    is a plain calendar day, and it is stored as one (an ISO epoch day). Formatting it directly
 *    means no timezone or DST offset can ever be applied to it, so a row dated 15 August can never
 *    be displayed as 14 or 16 August on a device in another timezone. Reaching for an
 *    `Instant`-based formatter would reintroduce exactly that bug.
 *  - **The pattern is pinned here rather than taken from the platform.** Like
 *    [CurrencyFormatter]'s Indian digit grouping, a day-first layout is chosen on purpose instead
 *    of deferring to the device's localized short-date format, which in some locales renders
 *    `10/15/26`. A household should never have to guess whether `10/11` is October or November.
 *  - **The month name is still localized**, because that part is genuinely language. Only the
 *    ordering is fixed.
 *
 * The formatter is built per call rather than cached in a field. A cached one would capture
 * whatever the default locale was when it was first touched and then go stale if the household
 * changed the device language; building it is cheap, and there is no shared mutable state to
 * synchronise.
 *
 * ### The one piece of shared state: the chosen layout
 *
 * The household can choose between three date layouts in Settings ([DateFormatStyle]). Applying
 * that choice to the whole app through a dozen view models would mean threading the preference
 * through every read, and one screen left out would print a date in a different layout from the row
 * above it. Instead the active style is held here, once, and written by the application shell as the
 * preference changes. Every existing `format(...)` call then honours it without knowing it exists.
 *
 * It is a single `@Volatile` reference rather than a `StateFlow` because formatting is synchronous:
 * a composable asking for a date needs an answer in that frame. The shell writes it before drawing
 * the content, and a change recomposes the whole tree from the shell downwards, so the next frame
 * draws every date in the new layout. The style is deliberately process-wide and not persisted
 * here - persistence lives in the settings table, which is the source of truth across restarts.
 */
object DateFormatter {

    @Volatile
    private var activeStyle: DateFormatStyle = DateFormatStyle.DAY_MONTH_YEAR

    /** The layout every call without an explicit [style] currently uses. */
    val style: DateFormatStyle get() = activeStyle

    /**
     * Makes [style] the layout every subsequent call uses.
     *
     * Called by the application shell when the stored preference changes. It is idempotent, so it is
     * safe to apply on every recomposition, which is what keeps the process-wide style in step with
     * the database without a one-shot initialisation race.
     */
    fun applyStyle(style: DateFormatStyle) {
        activeStyle = style
    }

    fun format(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        format(date, activeStyle, locale)

    /** Formats [date] in an explicit [style], for a preview or a test that must not depend on the app's setting. */
    fun format(
        date: LocalDate,
        style: DateFormatStyle,
        locale: Locale = Locale.getDefault(),
    ): String = DateTimeFormatter.ofPattern(datePattern(style), locale).format(date)

    /**
     * Formats the month a filter is scoped to - for example `Oct 2026`.
     *
     * A [YearMonth] has no day at all, so this shares the same guarantee as [format]: there is no
     * instant and no zone for a timezone offset to shift, and a month can never be displayed as the
     * one before or after it. The two-character month is deliberately never abbreviated to a
     * number, for the same reason the day comes first: `10/2026` is unambiguous only to someone who
     * already knows the answer.
     */
    fun formatMonth(yearMonth: YearMonth, locale: Locale = Locale.getDefault()): String =
        formatMonth(yearMonth, activeStyle, locale)

    /** Formats [yearMonth] in an explicit [style]. */
    fun formatMonth(
        yearMonth: YearMonth,
        style: DateFormatStyle,
        locale: Locale = Locale.getDefault(),
    ): String = DateTimeFormatter.ofPattern(monthPattern(style), locale).format(yearMonth)

    /**
     * Formats the abbreviated month a date falls in - for example `Oct`.
     *
     * The month on its own, for a badge that prints the day above it and the month below: the two are
     * read together, so the year would be one fact too many and the day is the one being looked for.
     * Localized, and pinned to a name rather than a number, for the same reasons [format] gives.
     */
    fun formatShortMonth(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern(MONTH_NAME_PATTERN, locale).format(date)

    /**
     * Formats the day and abbreviated month, with no year - for example `1 Nov`.
     *
     * For the label under one bar of a chart, where the year is already stated by the heading above
     * and printing it again under every bar would be the same fact a dozen times. Day-first and
     * month-by-name, for exactly the reasons [format] gives: `11/1` is unambiguous only to somebody
     * who already knows the answer.
     */
    fun formatDayMonth(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern(dayMonthPattern(activeStyle), locale).format(date)

    /**
     * Formats the day of the week a date falls on - for example `Thursday`.
     *
     * Spelled out rather than abbreviated: the household is checking a due date against the week they
     * have in front of them, and "Thu" has to be expanded before it can be compared with anything.
     */
    fun formatWeekday(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern(WEEKDAY_PATTERN, locale).format(date)

    /**
     * Formats an **audit** timestamp - when a row was first written or last changed - for example
     * `14 Oct 2026, 10:30 AM`.
     *
     * This is the one place an [Instant] is formatted, and it does not contradict [format]'s rule
     * that the *transaction date* is never run through a zone: the two are different facts. The
     * transaction date is the calendar day the household says the money moved, which is the same day
     * in every timezone and is therefore stored as a plain day. `createdAt` and `updatedAt` are
     * instants the device recorded, they have no calendar meaning of their own, and the only truthful
     * way to show one is in the zone the reader is in - so this is the conversion that *should* be
     * zone-dependent, and it says so.
     *
     * The layout is pinned here for the same reason [PATTERN] is: `h:mm a` with the day first reads
     * the same on every device, where a platform-supplied date-time order would put the month,
     * day and year in whichever order the locale prefers.
     */
    fun formatTimestamp(
        instant: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String =
        DateTimeFormatter.ofPattern(timestampPattern(activeStyle), locale)
            .format(instant.atZone(zone))

    /** Formats an audit timestamp in an explicit [style], for a preview or a test. */
    fun formatTimestamp(
        instant: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        style: DateFormatStyle,
    ): String =
        DateTimeFormatter.ofPattern(timestampPattern(style), locale)
            .format(instant.atZone(zone))

    private fun datePattern(style: DateFormatStyle): String = when (style) {
        DateFormatStyle.DAY_MONTH_YEAR -> PATTERN
        DateFormatStyle.DAY_MONTH_NUMERIC -> NUMERIC_PATTERN
        DateFormatStyle.ISO -> ISO_PATTERN
    }

    private fun monthPattern(style: DateFormatStyle): String = when (style) {
        DateFormatStyle.DAY_MONTH_YEAR -> MONTH_PATTERN
        DateFormatStyle.DAY_MONTH_NUMERIC -> NUMERIC_MONTH_PATTERN
        DateFormatStyle.ISO -> ISO_MONTH_PATTERN
    }

    private fun dayMonthPattern(style: DateFormatStyle): String = when (style) {
        DateFormatStyle.DAY_MONTH_YEAR -> DAY_MONTH_PATTERN
        DateFormatStyle.DAY_MONTH_NUMERIC -> NUMERIC_DAY_MONTH_PATTERN
        DateFormatStyle.ISO -> ISO_DAY_MONTH_PATTERN
    }

    private fun timestampPattern(style: DateFormatStyle): String = when (style) {
        DateFormatStyle.DAY_MONTH_YEAR -> TIMESTAMP_PATTERN
        DateFormatStyle.DAY_MONTH_NUMERIC -> NUMERIC_TIMESTAMP_PATTERN
        DateFormatStyle.ISO -> ISO_TIMESTAMP_PATTERN
    }
}
