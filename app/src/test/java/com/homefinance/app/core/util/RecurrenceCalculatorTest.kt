package com.homefinance.app.core.util

import com.homefinance.app.data.model.RecurrenceFrequency
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The calendar arithmetic behind a recurring expense.
 *
 * These run in milliseconds and need no device, which is the point of keeping the calculation out of
 * the database and out of the UI: the two things a household would notice immediately if they were
 * wrong - a rent date that drifts, a February bill that skips itself - are asserted here rather than
 * discovered on a phone.
 */
class RecurrenceCalculatorTest {

    // ------------------------------------------------------------------ daily

    @Test
    fun dailyStartsOnTheStartDateAndStepsWholeDays() {
        assertEquals(
            listOf(date(2026, 10, 5), date(2026, 10, 6), date(2026, 10, 7), date(2026, 10, 8)),
            dates(frequency = DAILY, start = date(2026, 10, 5), to = date(2026, 10, 8)),
        )
    }

    @Test
    fun dailyHonoursAnInterval() {
        assertEquals(
            listOf(date(2026, 10, 5), date(2026, 10, 7), date(2026, 10, 9)),
            dates(frequency = DAILY, interval = 2, start = date(2026, 10, 5), to = date(2026, 10, 9)),
        )
    }

    // ----------------------------------------------------------------- weekly

    @Test
    fun weeklyKeepsTheWeekdayOfTheStartDate() {
        // 5 October 2026 is a Monday; every occurrence is a Monday.
        assertEquals(
            listOf(date(2026, 10, 5), date(2026, 10, 12), date(2026, 10, 19), date(2026, 10, 26)),
            dates(frequency = WEEKLY, start = date(2026, 10, 5), to = date(2026, 10, 31)),
        )
    }

    @Test
    fun weeklyHonoursAnIntervalOfTwoWeeks() {
        assertEquals(
            listOf(date(2026, 10, 5), date(2026, 10, 19), date(2026, 11, 2)),
            dates(frequency = WEEKLY, interval = 2, start = date(2026, 10, 5), to = date(2026, 11, 5)),
        )
    }

    // ---------------------------------------------------------------- monthly

    @Test
    fun monthlyKeepsTheDayOfMonth() {
        assertEquals(
            listOf(date(2026, 10, 5), date(2026, 11, 5), date(2026, 12, 5), date(2027, 1, 5)),
            dates(frequency = MONTHLY, start = date(2026, 10, 5), to = date(2027, 1, 20)),
        )
    }

    @Test
    fun monthlyClampsToTheLastDayAndDoesNotDriftBack() {
        // The documented month-end rule: January 31 -> February 28 -> March 31 -> April 30 -> May 31.
        // The clamp is a property of the target month, never the new anchor.
        assertEquals(
            listOf(
                date(2026, 1, 31),
                date(2026, 2, 28),
                date(2026, 3, 31),
                date(2026, 4, 30),
                date(2026, 5, 31),
                date(2026, 6, 30),
            ),
            dates(frequency = MONTHLY, start = date(2026, 1, 31), to = date(2026, 6, 30)),
        )
    }

    @Test
    fun monthlyClampsToFebruaryTwentyNineInALeapYear() {
        assertEquals(
            listOf(date(2028, 1, 31), date(2028, 2, 29), date(2028, 3, 31)),
            dates(frequency = MONTHLY, start = date(2028, 1, 31), to = date(2028, 3, 31)),
        )
    }

    @Test
    fun monthlyClampsTheThirtiethOfJanuaryToFebruary() {
        assertEquals(
            listOf(date(2026, 1, 30), date(2026, 2, 28), date(2026, 3, 30)),
            dates(frequency = MONTHLY, start = date(2026, 1, 30), to = date(2026, 3, 31)),
        )
    }

    @Test
    fun monthlyHonoursAnIntervalOfTwoMonths() {
        assertEquals(
            listOf(date(2026, 10, 5), date(2026, 12, 5), date(2027, 2, 5)),
            dates(frequency = MONTHLY, interval = 2, start = date(2026, 10, 5), to = date(2027, 2, 28)),
        )
    }

    // ----------------------------------------------------------------- yearly

    @Test
    fun yearlyKeepsTheMonthAndDay() {
        assertEquals(
            listOf(date(2026, 6, 15), date(2027, 6, 15), date(2028, 6, 15)),
            dates(frequency = YEARLY, start = date(2026, 6, 15), to = date(2028, 7, 1)),
        )
    }

    @Test
    fun yearlyClampsALeapDayAndComesBackToItOnTheNextLeapYear() {
        // Documented leap-year rule: 29 February is kept where it exists, and clamped to 28 February
        // in a common year without becoming the new anchor.
        assertEquals(
            listOf(
                date(2024, 2, 29),
                date(2025, 2, 28),
                date(2026, 2, 28),
                date(2027, 2, 28),
                date(2028, 2, 29),
            ),
            dates(frequency = YEARLY, start = date(2024, 2, 29), to = date(2028, 3, 1)),
        )
    }

    @Test
    fun yearlyHonoursAnIntervalOfThreeYears() {
        assertEquals(
            listOf(date(2026, 4, 1), date(2029, 4, 1)),
            dates(frequency = YEARLY, interval = 3, start = date(2026, 4, 1), to = date(2029, 4, 1)),
        )
    }

    // ---------------------------------------------------------------- ranges

    @Test
    fun noOccurrenceIsEverBeforeTheStartDate() {
        assertEquals(
            listOf(date(2026, 10, 5), date(2026, 11, 5)),
            dates(
                frequency = MONTHLY,
                start = date(2026, 10, 5),
                from = date(2026, 1, 1),
                to = date(2026, 11, 30),
            ),
        )
    }

    @Test
    fun fromSkipsOccurrencesTheCallerAlreadyHasWithoutMovingTheAnchor() {
        assertEquals(
            listOf(date(2026, 11, 5), date(2026, 12, 5)),
            dates(
                frequency = MONTHLY,
                start = date(2026, 10, 5),
                from = date(2026, 10, 6),
                to = date(2027, 1, 1),
            ),
        )
    }

    @Test
    fun fromOnAnOccurrenceDateIncludesThatOccurrence() {
        assertEquals(
            listOf(date(2026, 11, 5)),
            dates(
                frequency = MONTHLY,
                start = date(2026, 10, 5),
                from = date(2026, 11, 5),
                to = date(2026, 11, 30),
            ),
        )
    }

    @Test
    fun endDateIsInclusiveAndNothingIsProducedAfterIt() {
        assertEquals(
            listOf(date(2026, 10, 5), date(2026, 11, 5)),
            dates(
                frequency = MONTHLY,
                start = date(2026, 10, 5),
                end = date(2026, 11, 5),
                to = date(2027, 5, 1),
            ),
        )
    }

    @Test
    fun aToThatIsBeforeTheStartDateProducesNothing() {
        assertEquals(
            emptyList<LocalDate>(),
            dates(frequency = DAILY, start = date(2026, 10, 5), to = date(2026, 10, 4)),
        )
    }

    @Test
    fun aFromThatIsAfterToProducesNothing() {
        assertEquals(
            emptyList<LocalDate>(),
            dates(
                frequency = DAILY,
                start = date(2026, 10, 5),
                from = date(2026, 11, 1),
                to = date(2026, 10, 31),
            ),
        )
    }

    @Test
    fun anEndDateBeforeTheStartDateProducesNothing() {
        assertEquals(
            emptyList<LocalDate>(),
            dates(
                frequency = MONTHLY,
                start = date(2026, 10, 5),
                end = date(2026, 10, 4),
                to = date(2027, 1, 1),
            ),
        )
    }

    @Test
    fun aSingleDayRangeProducesAtMostTheStartDate() {
        assertEquals(
            listOf(date(2026, 10, 5)),
            dates(frequency = MONTHLY, start = date(2026, 10, 5), to = date(2026, 10, 5)),
        )
    }

    // ------------------------------------------------------------------ bounds

    @Test
    fun aLongMissedDailyScheduleIsCappedRatherThanUnbounded() {
        val result = dates(
            frequency = DAILY,
            start = date(2020, 1, 1),
            to = date(2026, 12, 31),
        )

        assertEquals(RecurrenceCalculator.MAX_OCCURRENCES_PER_RUN, result.size)
        assertEquals(date(2020, 1, 1), result.first())
        // Ascending and strictly a day apart, so nothing was skipped to reach the cap.
        assertEquals(date(2020, 1, 1).plusDays(999), result.last())
    }

    @Test
    fun theCapCanBeLoweredButNotBelowOne() {
        assertEquals(
            listOf(date(2026, 10, 5), date(2026, 10, 6)),
            dates(frequency = DAILY, start = date(2026, 10, 5), to = date(2026, 10, 31), limit = 2),
        )

        assertThrows(IllegalArgumentException::class.java) {
            dates(frequency = DAILY, start = date(2026, 10, 5), to = date(2026, 10, 31), limit = 0)
        }
    }

    @Test
    fun anIntervalBelowOneIsRefusedRatherThanGuessedAt() {
        assertThrows(IllegalArgumentException::class.java) {
            dates(frequency = MONTHLY, interval = 0, start = date(2026, 10, 5), to = date(2027, 1, 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            dates(frequency = MONTHLY, interval = -3, start = date(2026, 10, 5), to = date(2027, 1, 1))
        }
    }

    @Test
    fun aDailyScheduleRunningPastTheCalendarEndsRatherThanThrowing() {
        // Anchored on the last day a LocalDate can express, so the *next* step leaves the calendar
        // entirely. The engine must answer "no further occurrences" rather than failing.
        assertEquals(
            listOf(LocalDate.MAX),
            dates(frequency = DAILY, start = LocalDate.MAX, to = LocalDate.MAX),
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun dates(
        frequency: RecurrenceFrequency,
        interval: Int = 1,
        start: LocalDate,
        end: LocalDate? = null,
        from: LocalDate = start,
        to: LocalDate,
        limit: Int = RecurrenceCalculator.MAX_OCCURRENCES_PER_RUN,
    ): List<LocalDate> = RecurrenceCalculator.occurrences(
        frequency = frequency,
        interval = interval,
        startDate = start,
        endDate = end,
        from = from,
        to = to,
        limit = limit,
    )

    private fun date(year: Int, month: Int, day: Int): LocalDate = LocalDate.of(year, month, day)

    private companion object {
        val DAILY = RecurrenceFrequency.DAILY
        val WEEKLY = RecurrenceFrequency.WEEKLY
        val MONTHLY = RecurrenceFrequency.MONTHLY
        val YEARLY = RecurrenceFrequency.YEARLY
    }
}
