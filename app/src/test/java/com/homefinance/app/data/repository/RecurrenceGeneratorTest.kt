package com.homefinance.app.data.repository

import com.homefinance.app.data.local.entity.RecurringOccurrenceEntity
import com.homefinance.app.data.local.entity.RecurringScheduleEntity
import com.homefinance.app.data.model.RecurrenceFrequency
import com.homefinance.app.data.model.RecurringOccurrenceStatus
import com.homefinance.app.data.model.RecurringScheduleStatus
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generation engine: what a schedule's rule turns into, and - just as importantly - what it does
 * not turn into.
 *
 * The recurring rules that matter to a household are asserted here against the in-memory
 * [FakeRecurringDao]: only missing dates are written, a repeat run is a no-op, a schedule that is
 * paused, retired or already paid up produces nothing, and a long-missed schedule catches up over
 * several runs instead of producing an unbounded insert. That the *real* schema refuses a duplicate
 * `(schedule_id, due_date)` is a property of the database and is asserted in `RecurringDaoTest`.
 */
class RecurrenceGeneratorTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 15)

    /** The planning horizon is documented as 90 days, so every expectation below ends on 13 Jan 2027. */
    private val horizon: LocalDate = today.plusDays(90)

    private val now: Instant = Instant.parse("2026-10-15T09:00:00Z")

    @Test
    fun firstRunCreatesEveryOccurrenceUpToThePlanningHorizon() = runBlocking {
        val schedule = schedule(startDate = date(2026, 10, 5))
        val dao = dao(schedule)

        val created = RecurrenceGenerator(dao, DirectTransactionRunner).generateFor(schedule, today, now)

        assertEquals(4, created)
        assertEquals(
            listOf(date(2026, 10, 5), date(2026, 11, 5), date(2026, 12, 5), date(2027, 1, 5)),
            dao.storedOccurrences.map { it.dueDate }.sorted(),
        )
        assertEquals(date(2027, 1, 5), dao.generatedThroughWrites[schedule.id])
        assertEquals(horizon, today.plusDays(RecurrenceGenerator.PLANNING_HORIZON_DAYS))
    }

    @Test
    fun everyGeneratedOccurrenceStartsPendingAndUnowned() = runBlocking {
        val schedule = schedule(startDate = today)
        val dao = dao(schedule)

        RecurrenceGenerator(dao, DirectTransactionRunner).generateFor(schedule, today, now)

        val row = dao.storedOccurrences.single { it.dueDate == today }
        assertEquals(RecurringOccurrenceStatus.PENDING, row.status)
        assertEquals(1_500_000L, row.amountPaise)
        assertEquals(null, row.transactionId)
        assertEquals(now, row.createdAt)
    }

    @Test
    fun aSecondRunCreatesNothingAndLeavesTheProgressWhereItWas() = runBlocking {
        val schedule = schedule(startDate = date(2026, 10, 5))
        val dao = dao(schedule)
        val generator = RecurrenceGenerator(dao, DirectTransactionRunner)

        generator.generateFor(schedule, today, now)
        val second = generator.generateFor(schedule.copy(generatedThrough = date(2027, 1, 5)), today, now)

        assertEquals(0, second)
        assertEquals(4, dao.storedOccurrences.size)
    }

    @Test
    fun replayingAnAlreadyGeneratedRunInsertsNothingEvenWithoutProgress() = runBlocking {
        // What a crash between the insert and the progress write would look like: the rows are there,
        // the marker is not. The unique index is what makes the repeat harmless.
        val schedule = schedule(startDate = date(2026, 10, 5))
        val dao = dao(schedule)
        val generator = RecurrenceGenerator(dao, DirectTransactionRunner)

        generator.generateFor(schedule, today, now)
        val replay = generator.generateFor(schedule, today, now)

        assertEquals(0, replay)
        assertEquals(4, dao.storedOccurrences.size)
    }

    @Test
    fun generationContinuesFromTheProgressTheScheduleRecords() = runBlocking {
        val schedule = schedule(startDate = date(2026, 10, 5), generatedThrough = date(2026, 11, 5))
        val dao = dao(schedule)

        val created = RecurrenceGenerator(dao, DirectTransactionRunner).generateFor(schedule, today, now)

        assertEquals(2, created)
        assertEquals(
            listOf(date(2026, 12, 5), date(2027, 1, 5)),
            dao.storedOccurrences.map { it.dueDate }.sorted(),
        )
    }

    @Test
    fun anEndDateIsHonouredAndNothingIsGeneratedAfterIt() = runBlocking {
        val schedule = schedule(startDate = date(2026, 10, 5), endDate = date(2026, 11, 5))
        val dao = dao(schedule)
        val generator = RecurrenceGenerator(dao, DirectTransactionRunner)

        val created = generator.generateFor(schedule, today, now)

        assertEquals(2, created)
        assertEquals(
            listOf(date(2026, 10, 5), date(2026, 11, 5)),
            dao.storedOccurrences.map { it.dueDate }.sorted(),
        )
        assertEquals(date(2026, 11, 5), dao.generatedThroughWrites[schedule.id])
        assertEquals(0, generator.generateFor(schedule.copy(generatedThrough = date(2026, 11, 5)), today, now))
    }

    @Test
    fun anEndDateInThePastStillProducesTheMissedOccurrencesInsideIt() = runBlocking {
        val schedule = schedule(startDate = date(2026, 6, 5), endDate = date(2026, 8, 5))
        val dao = dao(schedule)

        val created = RecurrenceGenerator(dao, DirectTransactionRunner).generateFor(schedule, today, now)

        assertEquals(3, created)
        assertEquals(
            listOf(date(2026, 6, 5), date(2026, 7, 5), date(2026, 8, 5)),
            dao.storedOccurrences.map { it.dueDate }.sorted(),
        )
    }

    @Test
    fun aPausedScheduleGeneratesNothingAtAll() = runBlocking {
        val schedule = schedule(startDate = today, status = RecurringScheduleStatus.PAUSED)
        val dao = dao(schedule)

        val created = RecurrenceGenerator(dao, DirectTransactionRunner).generateFor(schedule, today, now)

        assertEquals(0, created)
        assertEquals(emptyList<RecurringOccurrenceEntity>(), dao.storedOccurrences)
        assertTrue(dao.generatedThroughWrites.isEmpty())
    }

    @Test
    fun aRetiredScheduleGeneratesNothingAtAll() = runBlocking {
        val schedule = schedule(startDate = today, status = RecurringScheduleStatus.RETIRED)
        val dao = dao(schedule)

        val created = RecurrenceGenerator(dao, DirectTransactionRunner).generateFor(schedule, today, now)

        assertEquals(0, created)
        assertEquals(emptyList<RecurringOccurrenceEntity>(), dao.storedOccurrences)
    }

    @Test
    fun aStartDateBeyondTheHorizonGeneratesNothingYet() = runBlocking {
        val schedule = schedule(startDate = horizon.plusDays(1))
        val dao = dao(schedule)

        val created = RecurrenceGenerator(dao, DirectTransactionRunner).generateFor(schedule, today, now)

        assertEquals(0, created)
        assertTrue(dao.generatedThroughWrites.isEmpty())
    }

    @Test
    fun aStartDateInsideTheHorizonGeneratesItAndNothingElse() = runBlocking {
        val start = horizon.minusDays(1)
        val schedule = schedule(startDate = start)
        val dao = dao(schedule)

        val created = RecurrenceGenerator(dao, DirectTransactionRunner).generateFor(schedule, today, now)

        assertEquals(1, created)
        assertEquals(listOf(start), dao.storedOccurrences.map { it.dueDate })
    }

    @Test
    fun aPaidOccurrenceIsNeverRecreatedAndItsAmountIsNotRewritten() = runBlocking {
        val schedule = schedule(startDate = date(2026, 10, 5))
        val dao = dao(
            schedule,
            occurrences = listOf(
                occurrence(
                    id = 7L,
                    dueDate = date(2026, 10, 5),
                    amountPaise = 1_400_000L,
                    status = RecurringOccurrenceStatus.PAID,
                    transactionId = 99L,
                ),
            ),
        )

        val created = RecurrenceGenerator(dao, DirectTransactionRunner).generateFor(schedule, today, now)

        assertEquals(3, created)
        val paid = dao.storedOccurrences.single { it.dueDate == date(2026, 10, 5) }
        assertEquals(RecurringOccurrenceStatus.PAID, paid.status)
        assertEquals(1_400_000L, paid.amountPaise)
        assertEquals(99L, paid.transactionId)
    }

    @Test
    fun aSkippedOccurrenceIsNeverRecreated() = runBlocking {
        val schedule = schedule(startDate = date(2026, 10, 5))
        val dao = dao(
            schedule,
            occurrences = listOf(
                occurrence(
                    id = 7L,
                    dueDate = date(2026, 10, 5),
                    status = RecurringOccurrenceStatus.SKIPPED,
                ),
            ),
        )

        val created = RecurrenceGenerator(dao, DirectTransactionRunner).generateFor(schedule, today, now)

        assertEquals(3, created)
        assertEquals(4, dao.storedOccurrences.size)
        assertEquals(
            RecurringOccurrenceStatus.SKIPPED,
            dao.storedOccurrences.single { it.dueDate == date(2026, 10, 5) }.status,
        )
    }

    @Test
    fun aLongMissedDailyScheduleIsCappedAndThenCatchesUpOverTheNextRun() = runBlocking {
        val start = today.minusDays(3 * 365L)
        val schedule = schedule(startDate = start, frequency = RecurrenceFrequency.DAILY)
        val dao = dao(schedule)
        val generator = RecurrenceGenerator(dao, DirectTransactionRunner)

        val firstRun = generator.generateFor(schedule, today, now)

        assertEquals(1000, firstRun)
        assertEquals(start.plusDays(999), dao.generatedThroughWrites[schedule.id])

        val secondRun = generator.generateFor(
            schedule.copy(generatedThrough = start.plusDays(999)),
            today,
            now,
        )

        val expectedTotal = ChronoUnit.DAYS.between(start, horizon) + 1
        assertEquals(expectedTotal, (firstRun + secondRun).toLong())
        assertEquals(expectedTotal, dao.storedOccurrences.size.toLong())
        assertEquals(horizon, dao.storedOccurrences.maxOf { it.dueDate })
    }

    @Test
    fun weeklyOccurrencesKeepTheirWeekday() = runBlocking {
        val schedule = schedule(
            startDate = date(2026, 10, 5),
            frequency = RecurrenceFrequency.WEEKLY,
            interval = 2,
        )
        val dao = dao(schedule)

        RecurrenceGenerator(dao, DirectTransactionRunner).generateFor(schedule, today, now)

        assertEquals(
            listOf(
                date(2026, 10, 5),
                date(2026, 10, 19),
                date(2026, 11, 2),
                date(2026, 11, 16),
                date(2026, 11, 30),
                date(2026, 12, 14),
                date(2026, 12, 28),
                date(2027, 1, 11),
            ),
            dao.storedOccurrences.map { it.dueDate }.sorted(),
        )
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * The in-memory DAO with [schedule] actually stored in it.
     *
     * Seeding matters: [FakeRecurringDao.setGeneratedThrough] mirrors the real
     * `UPDATE ... WHERE id = :id`, so it reports `0` and records nothing for a schedule that is not
     * there. Handing the generator a schedule the database has never heard of would be testing a
     * state the engine can never meet.
     */
    private fun dao(
        vararg schedules: RecurringScheduleEntity,
        occurrences: List<RecurringOccurrenceEntity> = emptyList(),
    ): FakeRecurringDao = FakeRecurringDao(
        categoryNames = mapOf(CATEGORY_ID to "Rent"),
        initialSchedules = schedules.toList(),
        initialOccurrences = occurrences,
    )

    private fun schedule(
        id: Long = SCHEDULE_ID,
        name: String = "Shop rent",
        description: String? = null,
        categoryId: Long = CATEGORY_ID,
        amountPaise: Long = 1_500_000L,
        frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
        interval: Int = 1,
        startDate: LocalDate,
        endDate: LocalDate? = null,
        status: RecurringScheduleStatus = RecurringScheduleStatus.ACTIVE,
        generatedThrough: LocalDate? = null,
    ): RecurringScheduleEntity = RecurringScheduleEntity(
        id = id,
        name = name,
        description = description,
        categoryId = categoryId,
        amountPaise = amountPaise,
        frequency = frequency,
        intervalCount = interval,
        startDate = startDate,
        endDate = endDate,
        status = status,
        generatedThrough = generatedThrough,
        createdAt = now,
        updatedAt = now,
    )

    private fun occurrence(
        id: Long = 1L,
        scheduleId: Long = SCHEDULE_ID,
        dueDate: LocalDate,
        amountPaise: Long = 1_500_000L,
        status: RecurringOccurrenceStatus = RecurringOccurrenceStatus.PENDING,
        transactionId: Long? = null,
    ): RecurringOccurrenceEntity = RecurringOccurrenceEntity(
        id = id,
        scheduleId = scheduleId,
        dueDate = dueDate,
        amountPaise = amountPaise,
        status = status,
        transactionId = transactionId,
        createdAt = now,
        updatedAt = now,
    )

    private fun date(year: Int, month: Int, day: Int): LocalDate = LocalDate.of(year, month, day)

    private companion object {
        const val SCHEDULE_ID = 1L
        const val CATEGORY_ID = 10L
    }
}
