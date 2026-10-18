package com.homefinance.app.data.repository

import com.homefinance.app.data.local.dao.RecurringDao
import com.homefinance.app.data.local.entity.RecurringOccurrenceEntity
import com.homefinance.app.data.local.entity.RecurringScheduleEntity
import com.homefinance.app.data.local.relation.RecurringOccurrenceDetail
import com.homefinance.app.data.local.relation.RecurringScheduleWithProgress
import com.homefinance.app.data.model.RecurringOccurrenceStatus
import com.homefinance.app.data.model.RecurringScheduleStatus
import com.homefinance.app.data.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.yield

/**
 * In-memory [RecurringDao] for JVM tests.
 *
 * It models the two properties of the real statements that the repository's rules actually depend on:
 *
 *  - **the unique index on `(schedule_id, due_date)`**, because [RecurringDao.insertOccurrences] is
 *    documented as ignoring a row it already has (`-1` in that position) and a fake that invented a
 *    duplicate would make the generator look correct when it was not. The constraint itself - that
 *    SQLite refuses the second row at all - is asserted against a real database in `RecurringDaoTest`;
 *    what is emulated here is only the *contract of the method*;
 *  - **the conditional updates**, which change `0` rows when the row is not in the expected state, so
 *    "pay the same bill twice" can be exercised without a device.
 *
 * Everything else is a plain map. Ordering is reproduced because the screens read it, but the real
 * `ORDER BY` - including the `CASE` that sorts active schedules first - is asserted against Room in
 * `RecurringDaoTest`, not believed here.
 *
 * @param categoryNames the names of the categories, standing in for the `categories` table. A
 *   schedule whose category is absent is **dropped** by the joins, which is what the real `INNER
 *   JOIN`s do.
 */
class FakeRecurringDao(
    private val categoryNames: Map<Long, String> = emptyMap(),
    initialSchedules: List<RecurringScheduleEntity> = emptyList(),
    initialOccurrences: List<RecurringOccurrenceEntity> = emptyList(),
    /**
     * The marks of those categories, standing in for `categories.icon_key`. Absent means "nobody
     * chose one", which is `null` in the projections - the same thing the real join returns for a
     * category with no stored key.
     */
    private val categoryIconKeys: Map<Long, String> = emptyMap(),
    /**
     * The types of those categories, standing in for `categories.type` - which is where a schedule's
     * direction comes from in the real query.
     *
     * Absent means a plain expense category. That is a convenience for the many tests that are about
     * amounts, dates and duplicate rows rather than about direction, and it is the honest default: every
     * schedule written before income schedules existed was an expense one. A test that is about income
     * has to say so here, because the projection has no other way to become an income schedule.
     */
    private val categoryTypes: Map<Long, TransactionType> = emptyMap(),
) : RecurringDao {

    private val schedules: MutableMap<Long, RecurringScheduleEntity> =
        initialSchedules.associateByTo(LinkedHashMap()) { it.id }

    private val occurrences: MutableMap<Long, RecurringOccurrenceEntity> =
        initialOccurrences.associateByTo(LinkedHashMap()) { it.id }

    private var nextScheduleId: Long = (initialSchedules.maxOfOrNull { it.id } ?: 0L) + 1L
    private var nextOccurrenceId: Long = (initialOccurrences.maxOfOrNull { it.id } ?: 0L) + 1L

    /** Every schedule passed to [insertSchedule], in call order, exactly as it arrived. */
    val insertedSchedules: MutableList<RecurringScheduleEntity> = mutableListOf()

    /** Every schedule passed to [updateSchedule], in call order, exactly as it arrived. */
    val updatedSchedules: MutableList<RecurringScheduleEntity> = mutableListOf()

    /** Every batch passed to [insertOccurrences], in call order. */
    val insertedOccurrenceBatches: MutableList<List<RecurringOccurrenceEntity>> = mutableListOf()

    /** The `generated_through` values written through [setGeneratedThrough], by schedule id. */
    val generatedThroughWrites: MutableMap<Long, LocalDate> = mutableMapOf()

    /** The schedules currently stored, for a test that wants to assert on the rows themselves. */
    val storedSchedules: List<RecurringScheduleEntity> get() = schedules.values.toList()

    /**
     * Makes every observation flow fail, the way [FakeTransactionDao.failure] does.
     *
     * The reads that only a real schema can break are asserted elsewhere; what this is for is the
     * screens' own handling of a failed read, which has to be reachable without corrupting a database.
     */
    var readFailure: Throwable? = null

    /**
     * Makes the generator's writes fail, so the screen's handling of a failed *run* can be exercised.
     *
     * Generation is the one write a screen performs on its own initiative, and it is exactly the write
     * whose failure must not take the household's schedules off screen - so it needs a way to fail.
     */
    var writeFailure: Throwable? = null

    /** The occurrences currently stored, for a test that wants to assert on the rows themselves. */
    val storedOccurrences: List<RecurringOccurrenceEntity> get() = occurrences.values.toList()

    override suspend fun insertSchedule(schedule: RecurringScheduleEntity): Long {
        val id = if (schedule.id != 0L) schedule.id else nextScheduleId++
        schedules[id] = schedule.copy(id = id)
        insertedSchedules += schedule
        return id
    }

    override suspend fun updateSchedule(schedule: RecurringScheduleEntity) {
        schedules[schedule.id] = schedule
        updatedSchedules += schedule
    }

    override suspend fun getScheduleById(id: Long): RecurringScheduleEntity? = schedules[id]

    override suspend fun getSchedulesWithStatus(
        status: RecurringScheduleStatus,
    ): List<RecurringScheduleEntity> =
        schedules.values.filter { it.status == status }.sortedBy { it.id }

    override fun observeSchedulesWithProgress(): Flow<List<RecurringScheduleWithProgress>> {
        readFailure?.let { failure -> return flow { throw failure } }

        return flowOf(
            schedules.values
                .sortedWith(
                    compareBy<RecurringScheduleEntity> { it.status.ordinal }
                        .thenBy { nextDueDate(it.id) ?: LocalDate.MAX }
                        .thenBy { it.name.lowercase() },
                )
                .mapNotNull { it.withProgress() },
        )
    }

    override fun observeScheduleWithProgress(id: Long): Flow<RecurringScheduleWithProgress?> {
        readFailure?.let { failure -> return flow { throw failure } }

        return flowOf(schedules[id]?.withProgress())
    }

    override suspend fun pauseSchedule(
        id: Long,
        from: RecurringScheduleStatus,
        to: RecurringScheduleStatus,
        now: Instant,
    ): Int = transition(id, from, to, now) { it }

    override suspend fun resumeSchedule(
        id: Long,
        from: RecurringScheduleStatus,
        to: RecurringScheduleStatus,
        generatedThrough: LocalDate?,
        now: Instant,
    ): Int = transition(id, from, to, now) { it.copy(generatedThrough = generatedThrough) }

    override suspend fun retireSchedule(
        id: Long,
        active: RecurringScheduleStatus,
        paused: RecurringScheduleStatus,
        to: RecurringScheduleStatus,
        now: Instant,
    ): Int {
        val schedule = schedules[id] ?: return 0
        if (schedule.status != active && schedule.status != paused) return 0

        schedules[id] = schedule.copy(status = to, updatedAt = now)
        return 1
    }

    override suspend fun setGeneratedThrough(id: Long, generatedThrough: LocalDate): Int {
        writeFailure?.let { throw it }

        val schedule = schedules[id] ?: return 0

        schedules[id] = schedule.copy(generatedThrough = generatedThrough)
        generatedThroughWrites[id] = generatedThrough
        return 1
    }

    override suspend fun insertOccurrences(
        occurrences: List<RecurringOccurrenceEntity>,
    ): List<Long> {
        insertedOccurrenceBatches += occurrences

        return occurrences.map { row ->
            val alreadyThere = this.occurrences.values.any {
                it.scheduleId == row.scheduleId && it.dueDate == row.dueDate
            }
            if (alreadyThere) {
                IGNORED_ROW
            } else {
                val id = nextOccurrenceId++
                this.occurrences[id] = row.copy(id = id)
                id
            }
        }
    }

    override suspend fun getOccurrenceById(id: Long): RecurringOccurrenceEntity? = occurrences[id]

    override fun observeOccurrencesForSchedule(
        scheduleId: Long,
    ): Flow<List<RecurringOccurrenceDetail>> {
        readFailure?.let { failure -> return flow { throw failure } }

        return flowOf(
            occurrences.values
                .filter { it.scheduleId == scheduleId }
                .sortedWith(
                    compareByDescending<RecurringOccurrenceEntity> { it.dueDate }
                        .thenByDescending { it.id },
                )
                .mapNotNull { it.withSchedule() },
        )
    }

    override suspend fun markOccurrencePaid(
        id: Long,
        pending: RecurringOccurrenceStatus,
        paid: RecurringOccurrenceStatus,
        transactionId: Long,
        now: Instant,
    ): Int = resolve(id, pending, now) {
        it.copy(status = paid, transactionId = transactionId)
    }

    override suspend fun markOccurrenceSkipped(
        id: Long,
        pending: RecurringOccurrenceStatus,
        skipped: RecurringOccurrenceStatus,
        now: Instant,
    ): Int = resolve(id, pending, now) { it.copy(status = skipped) }

    /** The shared shape of the two conditional occurrence updates. */
    private inline fun resolve(
        id: Long,
        expected: RecurringOccurrenceStatus,
        now: Instant,
        change: (RecurringOccurrenceEntity) -> RecurringOccurrenceEntity,
    ): Int {
        val occurrence = occurrences[id] ?: return 0
        if (occurrence.status != expected) return 0

        occurrences[id] = change(occurrence).copy(updatedAt = now)
        return 1
    }

    private inline fun transition(
        id: Long,
        from: RecurringScheduleStatus,
        to: RecurringScheduleStatus,
        now: Instant,
        change: (RecurringScheduleEntity) -> RecurringScheduleEntity,
    ): Int {
        val schedule = schedules[id] ?: return 0
        if (schedule.status != from) return 0

        schedules[id] = change(schedule).copy(status = to, updatedAt = now)
        return 1
    }

    /** The earliest still-pending due date of [scheduleId], or `null`. */
    private fun nextDueDate(scheduleId: Long): LocalDate? =
        occurrences.values            .filter {
                it.scheduleId == scheduleId && it.status == RecurringOccurrenceStatus.PENDING
            }
            .minOfOrNull { it.dueDate }

    /** Joins one schedule to its category's name the way the real query does, dropping it if absent. */
    private fun RecurringScheduleEntity.withProgress(): RecurringScheduleWithProgress? =
        categoryNames[categoryId]?.let { categoryName ->
            RecurringScheduleWithProgress(
                id = id,
                name = this.name,
                description = description,
                categoryId = categoryId,
                amountPaise = amountPaise,
                unit = unit,
                ratePaise = ratePaise,
                frequency = frequency,
                intervalCount = intervalCount,
                startDate = startDate,
                endDate = endDate,
                status = status,
                generatedThrough = generatedThrough,
                createdAt = createdAt,
                updatedAt = updatedAt,
                nextDueDate = nextDueDate(id),
                categoryName = categoryName,
                categoryType = categoryTypes[categoryId] ?: TransactionType.EXPENSE,
                categoryIconKey = categoryIconKeys[categoryId],
            )
        }

    private fun RecurringOccurrenceEntity.withSchedule(): RecurringOccurrenceDetail? {
        val schedule = schedules[scheduleId] ?: return null
        val name = categoryNames[schedule.categoryId] ?: return null

        return RecurringOccurrenceDetail(
            id = id,
            scheduleId = scheduleId,
            dueDate = dueDate,
            amountPaise = amountPaise,
            status = status,
            transactionId = transactionId,
            createdAt = createdAt,
            updatedAt = updatedAt,
            scheduleName = schedule.name,
            categoryId = schedule.categoryId,
            categoryName = name,
            categoryType = categoryTypes[schedule.categoryId] ?: TransactionType.EXPENSE,
            categoryIconKey = categoryIconKeys[schedule.categoryId],
        )
    }

    private companion object {

        /** The row id `OnConflictStrategy.IGNORE` reports for a row it did not insert. */
        const val IGNORED_ROW = -1L
    }
}

/**
 * A [com.homefinance.app.data.local.TransactionRunner] that just runs the block, for JVM tests.
 *
 * There is no transaction to be inside of: the fakes are already a single-threaded map and nothing
 * rolls anything back. What this lets a JVM test do is exercise the *sequence* of writes - which is
 * where the "already settled" guard lives - while the rollback itself, which only real SQLite can
 * demonstrate, is asserted against Room in `RecurringRepositoryDatabaseIntegrationTest`.
 */
object DirectTransactionRunner : com.homefinance.app.data.local.TransactionRunner {

    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
}

/**
 * The same, but it yields before running the block, so the block is provably *in flight* when the caller
 * comes back.
 *
 * It is what makes "two taps at once" reachable on the JVM: a double tap, a retry sent while the first is
 * still being written, or a second save during an in-progress one, are all cases where the refusal has to
 * come from the caller's own guard rather than from the fakes happening to be fast. Without a real
 * suspension point, a test asserting "only one was written" could pass because the first write was already
 * finished - which is a different, weaker claim.
 *
 * Like [DirectTransactionRunner] it rolls nothing back: the rollback itself is asserted against Room in
 * `RecurringRepositoryDatabaseIntegrationTest`.
 */
object YieldingTransactionRunner : com.homefinance.app.data.local.TransactionRunner {

    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        yield()
        return block()
    }
}
