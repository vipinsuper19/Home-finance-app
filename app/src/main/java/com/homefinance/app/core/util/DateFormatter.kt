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
 */
object DateFormatter {

    fun format(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern(PATTERN, locale).format(date)

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
        DateTimeFormatter.ofPattern(MONTH_PATTERN, locale).format(yearMonth)

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
        DateTimeFormatter.ofPattern(TIMESTAMP_PATTERN, locale)
            .format(instant.atZone(zone))
}
