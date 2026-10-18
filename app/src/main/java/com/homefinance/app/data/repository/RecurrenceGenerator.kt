package com.homefinance.app.data.repository

import com.homefinance.app.core.util.RecurrenceCalculator
import com.homefinance.app.data.local.TransactionRunner
import com.homefinance.app.data.local.dao.RecurringDao
import com.homefinance.app.data.local.entity.RecurringOccurrenceEntity
import com.homefinance.app.data.local.entity.RecurringScheduleEntity
import com.homefinance.app.data.model.RecurringOccurrenceStatus
import com.homefinance.app.data.model.RecurringScheduleStatus
import java.time.Instant
import java.time.LocalDate

/**
 * Turns a schedule's *rule* into the dated occurrences it produces.
 *
 * ### What it does, and what it refuses to do
 *
 * Given one schedule and today's date, it writes the occurrence rows that are missing between the
 * last date already generated and the planning horizon - and nothing else. It **never** writes a
 * transaction, never marks anything paid, and never deletes anything. A bill falling due is not a
 * payment, so a generation run can only ever make the app show what the household owes, never what
 * they have spent.
 *
 * ### Why running it twice is harmless
 *
 * Two independent guards, in this order:
 *
 *  1. the generated dates are worked out from [RecurringScheduleEntity.generatedThrough] forward, so
 *     a normal second run has nothing to do at all;
 *  2. the insert is `INSERT OR IGNORE` against the unique index on `(schedule_id, due_date)`, so even
 *     a run that recomputes dates that already exist cannot create a second copy. The database, not
 *     this class, is the authority on what "already exists" means.
 *
 * The progress marker is only moved inside the same transaction as the inserts, so an interrupted or
 * failed run leaves the schedule exactly where it was and the next run picks up the remainder. This
 * is also why the cap in [RecurrenceCalculator.MAX_OCCURRENCES_PER_RUN] is safe: a schedule that has
 * been missed for years catches up over several runs instead of producing an unbounded insert.
 *
 * ### The planning horizon
 *
 * Occurrences are generated **up to [PLANNING_HORIZON_DAYS] days after today** and no further. A
 * household does not need next year's rent dates, an unbounded future would fill the table with rows
 * nobody ever looks at, and the horizon is a constant rather than a setting because getting it wrong
 * has no upside. Anything inside the horizon can be paid early; anything beyond it is generated when
 * it comes inside the horizon.
 *
 * ### What it will not touch
 *
 * Paid and skipped occurrences are never regenerated, because generation only ever *inserts*: a
 * terminal row is already there, so the insert ignores it. Paused and retired schedules produce
 * nothing at all - checked here rather than only at the call site, because "a paused schedule
 * generates nothing" is the one rule that must not depend on a caller remembering it.
 */
class RecurrenceGenerator(
    private val recurringDao: RecurringDao,
    private val transactionRunner: TransactionRunner,
) {

    /**
     * Generates every occurrence of [schedule] that is missing between its own progress and the
     * planning horizon, and returns **how many rows were actually created**.
     *
     * @param today the household's current local calendar day. Passed in rather than read from the
     *   clock so the engine is deterministic in tests and never has to invent a timezone.
     * @return the number of occurrences inserted; `0` when there was nothing to do.
     */
    suspend fun generateFor(
        schedule: RecurringScheduleEntity,
        today: LocalDate,
        now: Instant = Instant.now(),
    ): Int {
        if (schedule.status != RecurringScheduleStatus.ACTIVE) return 0

        val through = generationLimit(schedule, today)
        val from = schedule.generatedThrough?.plusDays(1) ?: schedule.startDate
        if (from.isAfter(through)) return 0

        val dates = RecurrenceCalculator.occurrences(
            frequency = schedule.frequency,
            interval = schedule.intervalCount,
            startDate = schedule.startDate,
            endDate = schedule.endDate,
            from = from,
            to = through,
        )
        if (dates.isEmpty()) return 0

        val rows = dates.map { dueDate ->
            RecurringOccurrenceEntity(
                scheduleId = schedule.id,
                dueDate = dueDate,
                amountPaise = schedule.amountPaise,
                status = RecurringOccurrenceStatus.PENDING,
                transactionId = null,
                createdAt = now,
                updatedAt = now,
            )
        }

        return transactionRunner.inTransaction {
            // `-1` marks a row the unique index already had, which is a normal outcome of a repeated
            // run and not a failure - it is exactly what keeps a bill from being billed twice.
            val insertedIds = recurringDao.insertOccurrences(rows)
            recurringDao.setGeneratedThrough(schedule.id, dates.last())
            insertedIds.count { it != IGNORED_ROW }
        }
    }

    /**
     * The last date this run may generate: the earlier of the planning horizon and the schedule's own
     * end date, which is inclusive.
     *
     * An end date already in the past is not special-cased: the range simply ends there, the missed
     * occurrences inside it are generated, and nothing after it ever is.
     */
    private fun generationLimit(schedule: RecurringScheduleEntity, today: LocalDate): LocalDate {
        val horizon = today.plusDays(PLANNING_HORIZON_DAYS)
        val endDate = schedule.endDate ?: return horizon
        return if (endDate.isBefore(horizon)) endDate else horizon
    }

    companion object {

        /**
         * How far ahead occurrences are planned, in days.
         *
         * Ninety days: far enough that a monthly schedule always shows three or four dates to come,
         * which is what the household actually looks at, and short enough that a daily schedule does
         * not accumulate a year of rows nobody asked for.
         */
        const val PLANNING_HORIZON_DAYS: Long = 90L

        /** The row id `OnConflictStrategy.IGNORE` reports for a row it did not insert. */
        private const val IGNORED_ROW = -1L
    }
}
