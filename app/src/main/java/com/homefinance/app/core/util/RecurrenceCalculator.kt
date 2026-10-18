package com.homefinance.app.core.util

import com.homefinance.app.data.model.RecurrenceFrequency
import java.time.DateTimeException
import java.time.LocalDate
import java.time.YearMonth

/**
 * Works out *which calendar days* a recurring expense falls on.
 *
 * ### Why this is a class of its own
 *
 * Everything that decides a household's bills is here, in one pure function of
 * (frequency, interval, start date, end date, range) - no clock, no database, no Compose, no
 * `ZoneId`. That is what makes it testable without a device, and it is also what keeps
 * [com.homefinance.app.data.repository.RecurrenceGenerator] from having any date mathematics of
 * its own to get wrong.
 *
 * ### Never "every 30 days"
 *
 * A monthly bill is not a 30-day timer. [RecurrenceFrequency.DAILY] and
 * [RecurrenceFrequency.WEEKLY] step by whole days, but [RecurrenceFrequency.MONTHLY] and
 * [RecurrenceFrequency.YEARLY] are **calendar** steps: occurrence *n* is worked out from the
 * schedule's start date directly, never from occurrence *n-1*. Chaining steps would let a rounding
 * decision accumulate - a "monthly" bill pinned to the 31st would drift to the 28th and then stay
 * there, and a household's rent would move a few days every year for no reason anyone could see.
 * Every occurrence is therefore `startDate + (interval * n) months`, exactly.
 *
 * ### A day that does not exist
 *
 * When the anchor day does not exist in the target month the occurrence is **clamped to that
 * month's last day**, and the *next* occurrence is still computed from the original anchor day - the
 * clamp never becomes the new anchor. The rules, which are asserted by tests:
 *
 *  - 31 January monthly → 28 February (29 in a leap year) → **31** March → 30 April → **31** May;
 *  - 30 January monthly → 28 February → **30** March;
 *  - 29 February yearly (a leap day) → **28** February in a common year → **29** February in the
 *    next leap year.
 *
 * Clamping rather than skipping is deliberate: a bill dated "the 31st" that fell due in February
 * was still due in February, and a schedule that quietly skipped a month would leave a household
 * owing money the app never showed them.
 *
 * ### Bounded, always
 *
 * The result is capped at [limit] dates. A daily schedule left untouched for years would otherwise
 * produce an unbounded list in one call; the caller is expected to advance its own progress
 * afterwards and come back, which is what makes catching up safe to interrupt.
 */
object RecurrenceCalculator {

    /**
     * How many occurrences one call will ever return.
     *
     * Reached only by a schedule that has been missed for years (a daily schedule would need three
     * years of them). The generator advances its progress marker by whatever was produced, so
     * catching up continues on the next run rather than being lost.
     */
    const val MAX_OCCURRENCES_PER_RUN = 1_000

    /**
     * The largest interval a schedule may have: every second day, every hundredth month, and so on.
     *
     * Not a statement about arithmetic - the calendar itself bounds that - but about what a
     * household expense rhythm is. It also keeps the "interval" field a small, obvious number in the
     * form rather than a place a typo can hide.
     */
    const val MAX_INTERVAL = 1_000

    /**
     * The occurrence dates of a schedule, ascending, between [from] and [to] inclusive, never before
     * [startDate] and never after [endDate] when one is given.
     *
     * @param interval how many whole [frequency] units apart the occurrences are; must be at least 1.
     *   `interval = 2` with [RecurrenceFrequency.WEEKLY] is "every two weeks".
     * @param startDate the anchor: the first occurrence, and the day-of-month (or month-and-day, for
     *   a yearly schedule) every later one is derived from.
     * @param endDate the last date an occurrence may fall on, inclusive, or `null` for a schedule
     *   that runs until it is paused or retired. A date *before* the start date is not an error
     *   here - it simply yields no occurrences - because refusing it is a rule about editing a
     *   schedule, and the repository owns that rule.
     * @param from the earliest date the caller is interested in. Occurrences before it are not
     *   returned, which is how a generator asks for "everything since the last one I produced"
     *   without any date arithmetic of its own.
     * @param to the latest date the caller is interested in, inclusive.
     * @param limit the maximum number of dates to return; see [MAX_OCCURRENCES_PER_RUN].
     * @throws IllegalArgumentException if [interval] or [limit] is below 1.
     */
    fun occurrences(
        frequency: RecurrenceFrequency,
        interval: Int,
        startDate: LocalDate,
        endDate: LocalDate? = null,
        from: LocalDate = startDate,
        to: LocalDate,
        limit: Int = MAX_OCCURRENCES_PER_RUN,
    ): List<LocalDate> {
        require(interval >= 1) { "A recurrence interval must be at least 1, but got $interval" }
        require(limit >= 1) { "A limit must be at least 1, but got $limit" }

        val first = if (from.isAfter(startDate)) from else startDate
        val last = when {
            endDate == null -> to
            endDate.isBefore(to) -> endDate
            else -> to
        }
        if (last.isBefore(first)) return emptyList()

        val dates = ArrayList<LocalDate>()
        var step = firstStepAtOrAfter(frequency, interval, startDate, first)

        while (dates.size < limit) {
            val date = occurrenceAt(frequency, interval, startDate, step) ?: break
            if (date.isAfter(last)) break
            // The step that lands on or after `first` is the first one produced, but a clamped
            // month can put an earlier candidate between `first` and the next occurrence - the anchor
            // day is what decides, so a candidate before `first` is simply not this caller's.
            if (!date.isBefore(first)) dates.add(date)
            step++
        }

        return dates
    }

    /**
     * The index of the first occurrence that is not before [target], so the loop above never walks
     * thousands of steps it is going to discard.
     *
     * The estimate is exact for daily and weekly steps and an upper bound for calendar ones: a
     * monthly candidate carries the whole anchor month, so counting whole months is enough to find
     * the first candidate that can be at or after [target]. Anything it lands on that turns out to be
     * too early is filtered out by [occurrences] itself.
     */
    private fun firstStepAtOrAfter(
        frequency: RecurrenceFrequency,
        interval: Int,
        startDate: LocalDate,
        target: LocalDate,
    ): Long {
        if (!target.isAfter(startDate)) return 0L

        val steps = when (frequency) {
            RecurrenceFrequency.DAILY ->
                ceilDiv(target.toEpochDay() - startDate.toEpochDay(), interval.toLong())

            RecurrenceFrequency.WEEKLY ->
                ceilDiv(target.toEpochDay() - startDate.toEpochDay(), DAYS_PER_WEEK * interval)

            RecurrenceFrequency.MONTHLY ->
                ceilDiv(monthsBetween(startDate, target), interval.toLong())

            RecurrenceFrequency.YEARLY ->
                ceilDiv((target.year - startDate.year).toLong(), interval.toLong())
        }

        return if (steps > 0L) steps else 0L
    }

    /**
     * The occurrence at index [step], or `null` when the calendar has run out of dates.
     *
     * `null` is a real answer rather than a failure: it means the schedule reaches past the end of
     * what a [LocalDate] can express, which is to say it has no further occurrences.
     */
    private fun occurrenceAt(
        frequency: RecurrenceFrequency,
        interval: Int,
        startDate: LocalDate,
        step: Long,
    ): LocalDate? = try {
        when (frequency) {
            RecurrenceFrequency.DAILY -> startDate.plusDays(Math.multiplyExact(interval.toLong(), step))

            RecurrenceFrequency.WEEKLY ->
                startDate.plusWeeks(Math.multiplyExact(interval.toLong(), step))

            RecurrenceFrequency.MONTHLY -> {
                val months = Math.multiplyExact(interval.toLong(), step)
                clampedToMonth(YearMonth.from(startDate).plusMonths(months), startDate.dayOfMonth)
            }

            RecurrenceFrequency.YEARLY -> {
                val years = Math.multiplyExact(interval.toLong(), step)
                val year = Math.addExact(startDate.year.toLong(), years)
                require(year in MIN_YEAR..MAX_YEAR) { "Year $year is outside the calendar" }
                clampedToMonth(
                    YearMonth.of(year.toInt(), startDate.monthValue),
                    startDate.dayOfMonth,
                )
            }
        }
    } catch (_: ArithmeticException) {
        null
    } catch (_: DateTimeException) {
        null
    }

    /**
     * [month]'s [anchorDay], or its last day when that month is shorter - the one place the
     * month-end rule is written down.
     */
    private fun clampedToMonth(month: YearMonth, anchorDay: Int): LocalDate =
        month.atDay(if (anchorDay > month.lengthOfMonth()) month.lengthOfMonth() else anchorDay)

    /** `ceil(a / b)` for a non-negative `a` and a positive `b`. */
    private fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b

    /**
     * Whole months from [from]'s month to [to]'s month - `3` for January to April, `0` within one
     * month, and negative when [to] is earlier. Worked out from the year and month numbers rather
     * than from day counts, because a month is not a fixed number of days.
     */
    private fun monthsBetween(from: LocalDate, to: LocalDate): Long =
        (to.year.toLong() - from.year) * MONTHS_PER_YEAR +
            (to.monthValue - from.monthValue)

    private const val DAYS_PER_WEEK = 7L
    private const val MONTHS_PER_YEAR = 12L

    /** `LocalDate`'s own range: year -999999999 to 999999999. */
    private const val MIN_YEAR = -999_999_999L
    private const val MAX_YEAR = 999_999_999L
}
