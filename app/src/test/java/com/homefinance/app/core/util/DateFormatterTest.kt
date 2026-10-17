package com.homefinance.app.core.util

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class DateFormatterTest {

    private val date = LocalDate.of(2026, 10, 15)

    @Test
    fun formatsDayFirstWithAnAbbreviatedMonth() {
        assertEquals("15 Oct 2026", DateFormatter.format(date, Locale.ENGLISH))
    }

    @Test
    fun keepsSingleDigitDaysUnpadded() {
        assertEquals("1 Oct 2026", DateFormatter.format(LocalDate.of(2026, 10, 1), Locale.ENGLISH))
    }

    @Test
    fun doesNotShiftTheDayAcrossTimeZones() {
        // A transaction date is a plain calendar day. Formatting it must not route it through an
        // instant, or a row dated the 15th would show as the 14th or the 16th depending on where
        // the device happens to be. These two zones are the extremes of the UTC offset range.
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati")) // UTC+14
            val east = DateFormatter.format(date, Locale.ENGLISH)

            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Midway")) // UTC-11
            val west = DateFormatter.format(date, Locale.ENGLISH)

            assertEquals("15 Oct 2026", east)
            assertEquals(east, west)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun keepsDayFirstOrderInEveryLocale() {
        // The day-first layout is pinned rather than taken from the platform, so no locale can
        // render the ambiguous 10/15/26 or 15/10/26 that a household would have to decode.
        val formatted = DateFormatter.format(date, Locale.US)

        assertEquals("15 Oct 2026", formatted)
    }

    @Test
    fun formatsAMonthAsAnAbbreviatedNameAndAYear() {
        assertEquals("Oct 2026", DateFormatter.formatMonth(YearMonth.of(2026, 10), Locale.ENGLISH))
    }

    @Test
    fun theMonthNameIsStillLocalizedWhileTheOrderIsPinned() {
        // The month *name* is language and stays localized - French abbreviates October as "oct." -
        // while the month-then-year order does not move. Asserting the exact French rendering is
        // what keeps this from being a test that merely restates the English one.
        assertEquals("oct. 2026", DateFormatter.formatMonth(YearMonth.of(2026, 10), Locale.FRENCH))
    }

    @Test
    fun formatsEveryMonthWithItsOwnName() {
        assertEquals("Jan 2026", DateFormatter.formatMonth(YearMonth.of(2026, 1), Locale.ENGLISH))
        assertEquals("Dec 2026", DateFormatter.formatMonth(YearMonth.of(2026, 12), Locale.ENGLISH))
    }

    // ------------------------------------------------------------- timestamps

    @Test
    fun formatsAnAuditTimestampAsADayAndATime() {
        val instant = Instant.parse("2026-10-14T05:00:00Z")

        assertEquals(
            "14 Oct 2026, 10:30 AM",
            DateFormatter.formatTimestamp(instant, ZoneId.of("Asia/Kolkata"), Locale.ENGLISH),
        )
    }

    @Test
    fun anAuditTimestampIsShownInTheReadersOwnZone() {
        // Unlike the transaction date, an audit timestamp is an instant the device recorded and has
        // no calendar meaning of its own, so the two zones must genuinely disagree - which is the
        // opposite of what the transaction-date test asserts.
        val instant = Instant.parse("2026-10-14T18:30:00Z")

        assertEquals(
            "15 Oct 2026, 12:00 AM",
            DateFormatter.formatTimestamp(instant, ZoneId.of("Asia/Kolkata"), Locale.ENGLISH),
        )
        assertEquals(
            "14 Oct 2026, 11:30 AM",
            DateFormatter.formatTimestamp(instant, ZoneId.of("America/Los_Angeles"), Locale.ENGLISH),
        )
    }

    @Test
    fun keepsDayFirstOrderForTimestampsToo() {
        val instant = Instant.parse("2026-10-14T05:00:00Z")

        assertEquals(
            "14 Oct 2026, 10:30 AM",
            DateFormatter.formatTimestamp(instant, ZoneId.of("Asia/Kolkata"), Locale.US),
        )
    }

    @Test
    fun aTimestampJustAfterMidnightShowsTheNewDay() {
        val instant = Instant.parse("2026-10-13T18:31:00Z")

        assertEquals(
            "14 Oct 2026, 12:01 AM",
            DateFormatter.formatTimestamp(instant, ZoneId.of("Asia/Kolkata"), Locale.ENGLISH),
        )
    }
}
