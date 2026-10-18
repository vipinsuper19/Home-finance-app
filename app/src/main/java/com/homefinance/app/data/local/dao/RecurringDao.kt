package com.homefinance.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.homefinance.app.data.local.entity.RecurringOccurrenceEntity
import com.homefinance.app.data.local.entity.RecurringScheduleEntity
import com.homefinance.app.data.local.relation.RecurringOccurrenceDetail
import com.homefinance.app.data.local.relation.RecurringScheduleWithProgress
import com.homefinance.app.data.model.RecurringOccurrenceStatus
import com.homefinance.app.data.model.RecurringScheduleStatus
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes for [RecurringScheduleEntity] and [RecurringOccurrenceEntity].
 *
 * ### The two tables are one aggregate
 *
 * They share a DAO because they are never used apart: reading a schedule means reading when it next
 * falls due, and resolving an occurrence means reading the schedule's rule. Splitting them would
 * mean every caller holding two DAOs and combining their answers itself, which is the layering this
 * project keeps in the repositories instead - the *rules* live in
 * [com.homefinance.app.data.repository.RecurringRepository], and this interface stays a set of
 * statements with no behaviour of its own.
 *
 * ### State changes are conditional, single statements
 *
 * Every write that resolves something - paying an occurrence, skipping it, pausing a schedule,
 * retiring it - is one `UPDATE ... WHERE id = :id AND status = :expected` that reports how many rows
 * it changed. That is what makes a double tap, a retry, or two taps on two screens safe: the *second*
 * statement matches nothing and reports `0`, so a caller can never claim to have paid a bill twice.
 * Reading the row and then deciding in application code would be two statements, and between them
 * anything could happen.
 *
 * ### Status literals
 *
 * The projections below compare `status` against `'PENDING'` and `'ACTIVE'` as SQL text. That is the
 * enum's stored value - see [com.homefinance.app.data.model.RecurringOccurrenceStatus] and the
 * converters on [com.homefinance.app.data.local.HomeFinanceDatabase] - and it is only ever read in a
 * `WHERE` clause whose meaning is asserted against a real database in `RecurringDaoTest`.
 */
@Dao
interface RecurringDao {

    // ------------------------------------------------------------------ schedules

    /** Inserts a schedule and returns its new `id`. */
    @Insert
    suspend fun insertSchedule(schedule: RecurringScheduleEntity): Long

    /** Replaces the stored schedule with the same `id`. */
    @Update
    suspend fun updateSchedule(schedule: RecurringScheduleEntity)

    /** Returns the schedule with [id], or `null` if there is none. */
    @Query("SELECT * FROM recurring_schedules WHERE id = :id")
    suspend fun getScheduleById(id: Long): RecurringScheduleEntity?

    /**
     * Every schedule in [status], oldest first.
     *
     * This is what the generation engine walks. Oldest first, because ids are allocated in order and
     * a stable order is what makes a generation run reproducible.
     */
    @Query("SELECT * FROM recurring_schedules WHERE status = :status ORDER BY id ASC")
    suspend fun getSchedulesWithStatus(status: RecurringScheduleStatus): List<RecurringScheduleEntity>

    /**
     * Every schedule, with its category's name and the date it next falls due - the Recurring tab's
     * list.
     *
     * Ordered by how much attention each schedule wants: active ones first, then paused, then
     * retired; and within a group the soonest due date first, with schedules that have nothing
     * outstanding after those. `name` breaks ties so a list never reshuffles between two reads of
     * unchanged data.
     */
    @Query(
        """
        SELECT s.id                AS id,
               s.name              AS name,
               s.description       AS description,
               s.category_id       AS category_id,
               s.amount_paise      AS amount_paise,
               s.frequency         AS frequency,
               s.interval_count    AS interval_count,
               s.start_date        AS start_date,
               s.end_date          AS end_date,
               s.status            AS status,
               s.generated_through AS generated_through,
               s.created_at        AS created_at,
               s.updated_at        AS updated_at,
               c.name              AS category_name,
               c.icon_key          AS category_icon_key,
               (SELECT MIN(o.due_date)
                  FROM recurring_occurrences AS o
                 WHERE o.schedule_id = s.id
                   AND o.status = 'PENDING') AS next_due_date
        FROM recurring_schedules AS s
        INNER JOIN categories AS c ON c.id = s.category_id
        ORDER BY CASE s.status WHEN 'ACTIVE' THEN 0 WHEN 'PAUSED' THEN 1 ELSE 2 END ASC,
                 next_due_date IS NULL ASC,
                 next_due_date ASC,
                 s.name COLLATE NOCASE ASC
        """,
    )
    fun observeSchedulesWithProgress(): Flow<List<RecurringScheduleWithProgress>>

    /**
     * The single schedule with [id], with its category's name and the date it next falls due, or
     * emits `null` when there is no such schedule.
     *
     * A `Flow` rather than a one-shot read because the details screen must keep telling the truth
     * while the household acts on it: pausing, retiring or paying an occurrence re-runs this query,
     * so the state chip and the next due date change with the change rather than after a reopen.
     */
    @Query(
        """
        SELECT s.id                AS id,
               s.name              AS name,
               s.description       AS description,
               s.category_id       AS category_id,
               s.amount_paise      AS amount_paise,
               s.frequency         AS frequency,
               s.interval_count    AS interval_count,
               s.start_date        AS start_date,
               s.end_date          AS end_date,
               s.status            AS status,
               s.generated_through AS generated_through,
               s.created_at        AS created_at,
               s.updated_at        AS updated_at,
               c.name              AS category_name,
               c.icon_key          AS category_icon_key,
               (SELECT MIN(o.due_date)
                  FROM recurring_occurrences AS o
                 WHERE o.schedule_id = s.id
                   AND o.status = 'PENDING') AS next_due_date
        FROM recurring_schedules AS s
        INNER JOIN categories AS c ON c.id = s.category_id
        WHERE s.id = :id
        """,
    )
    fun observeScheduleWithProgress(id: Long): Flow<RecurringScheduleWithProgress?>

    /**
     * Pauses an active schedule. Returns `1` when it was active and is now paused, `0` when it was
     * not active any more - which is not a failure, but a truthful "nothing changed".
     */
    @Query(
        "UPDATE recurring_schedules SET status = :to, updated_at = :now " +
            "WHERE id = :id AND status = :from",
    )
    suspend fun pauseSchedule(
        id: Long,
        from: RecurringScheduleStatus,
        to: RecurringScheduleStatus,
        now: Instant,
    ): Int

    /**
     * Resumes a paused schedule and moves its generation progress to [generatedThrough].
     *
     * Both values change in one statement on purpose: a schedule that came back active without its
     * progress having moved would immediately back-fill every occurrence the pause was there to
     * stop. See [com.homefinance.app.data.repository.RecurringRepository.resume] for the policy.
     */
    @Query(
        "UPDATE recurring_schedules SET status = :to, generated_through = :generatedThrough, " +
            "updated_at = :now WHERE id = :id AND status = :from",
    )
    suspend fun resumeSchedule(
        id: Long,
        from: RecurringScheduleStatus,
        to: RecurringScheduleStatus,
        generatedThrough: LocalDate?,
        now: Instant,
    ): Int

    /** Retires a schedule that is still running or paused. Returns the rows changed, `0` or `1`. */
    @Query(
        "UPDATE recurring_schedules SET status = :to, updated_at = :now " +
            "WHERE id = :id AND status IN (:active, :paused)",
    )
    suspend fun retireSchedule(
        id: Long,
        active: RecurringScheduleStatus,
        paused: RecurringScheduleStatus,
        to: RecurringScheduleStatus,
        now: Instant,
    ): Int

    /**
     * Records how far generation has got for one schedule.
     *
     * [RecurringScheduleEntity.updatedAt] is deliberately *not* touched: it is the household's own
     * "last changed", and bookkeeping the generator did must not make a schedule look edited.
     */
    @Query("UPDATE recurring_schedules SET generated_through = :generatedThrough WHERE id = :id")
    suspend fun setGeneratedThrough(id: Long, generatedThrough: LocalDate): Int

    // --------------------------------------------------------------- occurrences

    /**
     * Inserts the generated occurrences, **ignoring** any whose `(schedule_id, due_date)` is already
     * there.
     *
     * [OnConflictStrategy.IGNORE] is the whole duplicate policy: the unique index is the authority,
     * and this strategy turns "already generated" from an error into a no-op. The returned list has
     * the new row ids, and `-1` in the positions that were already present, so a caller can tell how
     * much was actually created.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOccurrences(occurrences: List<RecurringOccurrenceEntity>): List<Long>

    /** Returns the occurrence with [id], or `null` if there is none. */
    @Query("SELECT * FROM recurring_occurrences WHERE id = :id")
    suspend fun getOccurrenceById(id: Long): RecurringOccurrenceEntity?

    /**
     * One schedule's occurrences, newest first, each carrying the schedule's name and category.
     *
     * Newest first for the same reason the ledger is: the household knows roughly when something
     * happened and is looking for the most recent one. The occurrence that actually wants attention -
     * the oldest still pending one, which is the one that is overdue - is what the schedule's own
     * details screen offers as "Mark as paid", so nothing has to be hunted for in this list.
     *
     * An `INNER JOIN` on both parents, because both foreign keys are non-null and neither parent is
     * ever deleted: there is no such thing as an occurrence without a schedule, and no schedule
     * without a category.
     */
    @Query(
        """
        SELECT o.id             AS id,
               o.schedule_id    AS schedule_id,
               o.due_date       AS due_date,
               o.amount_paise   AS amount_paise,
               o.status         AS status,
               o.transaction_id AS transaction_id,
               o.created_at     AS created_at,
               o.updated_at     AS updated_at,
               s.name           AS schedule_name,
               s.category_id    AS category_id,
               c.name           AS category_name,
               c.icon_key       AS category_icon_key
        FROM recurring_occurrences AS o
        INNER JOIN recurring_schedules AS s ON s.id = o.schedule_id
        INNER JOIN categories AS c ON c.id = s.category_id
        WHERE o.schedule_id = :scheduleId
        ORDER BY o.due_date DESC, o.id DESC
        """,
    )
    fun observeOccurrencesForSchedule(scheduleId: Long): Flow<List<RecurringOccurrenceDetail>>

    /**
     * Ties an occurrence to the actual expense it was paid as, and only while it is still pending.
     *
     * The `status = 'PENDING'` guard is what makes a repeated payment impossible even when two taps
     * arrive at once: the loser of the race changes `0` rows, and
     * [com.homefinance.app.data.repository.RecurringRepository] treats that as a signal to roll the
     * whole payment back rather than leaving an expense with nothing pointing at it.
     */
    @Query(
        "UPDATE recurring_occurrences SET status = :paid, transaction_id = :transactionId, " +
            "updated_at = :now WHERE id = :id AND status = :pending",
    )
    suspend fun markOccurrencePaid(
        id: Long,
        pending: RecurringOccurrenceStatus,
        paid: RecurringOccurrenceStatus,
        transactionId: Long,
        now: Instant,
    ): Int

    /**
     * Records that an occurrence was deliberately not paid, and only while it is still pending.
     *
     * No transaction is created and no total moves: a skip is the household saying a bill did not
     * happen. The row is kept so that it is never generated again.
     */
    @Query(
        "UPDATE recurring_occurrences SET status = :skipped, updated_at = :now " +
            "WHERE id = :id AND status = :pending",
    )
    suspend fun markOccurrenceSkipped(
        id: Long,
        pending: RecurringOccurrenceStatus,
        skipped: RecurringOccurrenceStatus,
        now: Instant,
    ): Int
}
