package com.homefinance.app.core.util

import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The three date layouts Settings offers, and the process-wide seam that applies one.
 *
 * The assertions are on exact strings because the whole point of the preference is that it changes
 * what the household reads: `5 Nov 2026`, `05/11/2026` and `2026-11-05` are three different answers
 * to the same value, and an off-by-one in the pattern is precisely the bug this setting could cause.
 */
class DateFormatStyleTest {

    private val date: LocalDate = LocalDate.of(2026, 11, 5)
    private val locale = Locale.ENGLISH

    @After
    fun restoreTheDefault() {
        // The style is process-wide, so a test that changes it must put it back for the next class.
        DateFormatter.applyStyle(DateFormatStyle.DAY_MONTH_YEAR)
    }

    @Test
    fun dayMonthYearDrawsTheMonthByName() {
        assertEquals(
            "5 Nov 2026",
            DateFormatter.format(date, DateFormatStyle.DAY_MONTH_YEAR, locale),
        )
    }

    @Test
    fun numericDrawsZeroPaddedDayFirst() {
        assertEquals(
            "05/11/2026",
            DateFormatter.format(date, DateFormatStyle.DAY_MONTH_NUMERIC, locale),
        )
    }

    @Test
    fun isoDrawsYearFirst() {
        assertEquals("2026-11-05", DateFormatter.format(date, DateFormatStyle.ISO, locale))
    }

    @Test
    fun applyingAStyleChangesTheCallsThatDoNotNameOne() {
        DateFormatter.applyStyle(DateFormatStyle.ISO)
        assertEquals("2026-11-05", DateFormatter.format(date, locale))

        DateFormatter.applyStyle(DateFormatStyle.DAY_MONTH_NUMERIC)
        assertEquals("05/11/2026", DateFormatter.format(date, locale))

        DateFormatter.applyStyle(DateFormatStyle.DAY_MONTH_YEAR)
        assertEquals("5 Nov 2026", DateFormatter.format(date, locale))
    }

    @Test
    fun aMonthFollowsTheChosenLayout() {
        val month = YearMonth.of(2026, 11)

        assertEquals("Nov 2026", DateFormatter.formatMonth(month, DateFormatStyle.DAY_MONTH_YEAR, locale))
        assertEquals("11/2026", DateFormatter.formatMonth(month, DateFormatStyle.DAY_MONTH_NUMERIC, locale))
        assertEquals("2026-11", DateFormatter.formatMonth(month, DateFormatStyle.ISO, locale))
    }

    @Test
    fun aDayAndMonthWithoutAYearFollowTheChosenLayout() {
        assertEquals("5 Nov", DateFormatter.formatDayMonth(date, locale))

        DateFormatter.applyStyle(DateFormatStyle.ISO)
        assertEquals("11-05", DateFormatter.formatDayMonth(date, locale))
    }

    @Test
    fun anAuditTimestampFollowsTheChosenLayout() {
        val instant = java.time.Instant.parse("2026-11-05T10:30:00Z")
        val zone = java.time.ZoneOffset.UTC

        assertEquals(
            "5 Nov 2026, 10:30 AM",
            DateFormatter.formatTimestamp(instant, zone, locale, DateFormatStyle.DAY_MONTH_YEAR),
        )
        assertEquals(
            "2026-11-05 10:30",
            DateFormatter.formatTimestamp(instant, zone, locale, DateFormatStyle.ISO),
        )
    }

    @Test
    fun anUnreadableStoredNameFallsBackToTheDefaultLayout() {
        assertEquals(
            DateFormatStyle.DAY_MONTH_YEAR,
            DateFormatStyle.fromStorage("A4"),
        )
        assertEquals(DateFormatStyle.ISO, DateFormatStyle.fromStorage("ISO"))
    }

    @Test
    fun everyStorageNameRoundTrips() {
        DateFormatStyle.entries.forEach { style ->
            assertEquals(style, DateFormatStyle.fromStorage(style.name))
        }
    }
}
