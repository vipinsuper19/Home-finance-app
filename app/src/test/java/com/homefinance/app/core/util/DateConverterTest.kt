package com.homefinance.app.core.util

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The calendar-date / epoch-millisecond conversion the date picker relies on.
 *
 * The failure this class exists to prevent is a date that shifts by a day, so the round trip is
 * asserted rather than assumed, and the millisecond value is pinned to UTC midnight.
 */
class DateConverterTest {

    @Test
    fun aDateConvertsToUtcMidnightOfThatSameDay() {
        // 15 October 2026, 00:00 UTC.
        val expected = 1_792_022_400_000L

        assertEquals(expected, DateConverter.toUtcEpochMillis(LocalDate.of(2026, 10, 15)))
    }

    @Test
    fun theConversionRoundTripsExactly() {
        // A spread of dates, including both ends of a year and a leap day.
        val dates = listOf(
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 10, 15),
            LocalDate.of(2026, 12, 31),
            LocalDate.of(2024, 2, 29),
            LocalDate.of(1970, 1, 1),
        )

        dates.forEach { date ->
            assertEquals(date, DateConverter.fromUtcEpochMillis(DateConverter.toUtcEpochMillis(date)))
        }
    }

    @Test
    fun theEpochItselfIsTheFirstOfJanuary1970() {
        assertEquals(LocalDate.of(1970, 1, 1), DateConverter.fromUtcEpochMillis(0L))
    }
}
