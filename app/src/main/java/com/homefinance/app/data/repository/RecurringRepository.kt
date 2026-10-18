package com.homefinance.app.data.repository

import com.homefinance.app.core.util.RecurrenceCalculator
import com.homefinance.app.data.local.TransactionRunner
import com.homefinance.app.data.local.dao.RecurringDao
import com.homefinance.app.data.local.entity.RecurringScheduleEntity
import com.homefinance.app.data.local.relation.RecurringOccurrenceDetail
import com.homefinance.app.data.local.relation.RecurringScheduleWithProgress
import com.homefinance.app.data.model.RecurrenceFrequency
import com.homefinance.app.data.model.RecurringOccurrenceStatus
import com.homefinance.app.data.model.RecurringScheduleStatus
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/**
 * The sanctioned way for the rest of the app to read and write recurring schedules and their
 * occurrences, and to settle an occurrence.
 *
 * ### The three concepts this class keeps apart
 *
 *  - A **schedule** is a plan: "rent, ₹15,000, monthly from the 5th". Creating one writes no ledger
 *    entry and moves no total, and neither does letting a due date arrive.
 *  - A **scheduled occurrence** is one date that plan produces. Generating it is not spending, and
 *    arriving at its due date is not paying: nothing here ever marks an occurrence paid because time
 *    passed.
 *  - A **transaction** is the money actually moving, and it exists only because the household said so
 *    - through [markOccurrencePaid], which is the single place in the app where a plan becomes
 *    spending.
 *
 * A schedule's *direction* is its category's own type, and there is deliberately no second copy of
 * it: an expense category makes an expense schedule and an income category makes an income one, and
 * the entry [markOccurrencePaid] writes is filed under that same category. So a salary can be planned
 * exactly as rent is, and paying one of its occurrences records income rather than an expense.
 *
 * That is why [generateDueOccurrences] can run on every visit to the Recurring tab without any risk
 * to the household's totals: it can only ever create expectations.
 *
 * ### What the database cannot enforce, and this class does
 *
 * Room cannot express "the amount is positive", "the interval is a sane whole number", "the end date
 * is not before the start", "the unit price is positive and complete" or "the category exists and is
 * active", so all of them are checked here, immediately before every write (see
 * [requireValidSchedule]). The rest is left to the schema, which is stronger: the unique index on
 * `(schedule_id, due_date)` is the authority on duplicates, and the conditional `UPDATE`s on the DAO
 * are the authority on "was this already settled".
 *
 * ### Why paying is one transaction, and why it is here
 *
 * [markOccurrencePaid] inserts the ledger entry and links the occurrence to it, and it does both
 * inside [TransactionRunner]. Two separate writes could leave an expense nothing points at, or a
 * schedule claiming a payment that no money backs; neither is reachable through this method, and the
 * `WHERE status = 'PENDING'` guard on the link means two taps - even two taps arriving at the same
 * moment - can produce at most one expense. The rules for the entry itself (a positive amount, an
 * existing category of the matching type) are *not* reimplemented here: the write goes through
 * [TransactionRepository], so the ledger is only ever written through the one gate that owns its
 * invariants.
 *
 * ### Editing a schedule does not rewrite its history
 *
 * Changing the name, category, amount, unit price, frequency or dates of a schedule:
 *
 *  - **never touches a paid or a skipped occurrence**, and never touches the transactions they link
 *    to;
 *  - **never rewrites the expected amount of an occurrence that already exists** - including one that
 *    is overdue and unpaid. An occurrence records what was expected on the day it fell due, and the
 *    household's history must not be edited out from under it;
 *  - only affects what is generated **next**, from the new rule. This is the "retain what exists,
 *    generate only the missing" policy, and the one exception is a start date moved *earlier*, where
 *    the progress marker is rewound so the back-dated period is filled in - still only with rows that
 *    are missing, because the unique index decides.
 *
 * The alternative - deleting the occurrences that no longer match the rule - was rejected: it would
 * throw away the record of what the household was already expecting, and it would do it silently.
 */
class RecurringRepository(
    private val recurringDao: RecurringDao,
    private val transactions: TransactionRepository,
    private val categories: CategoryRepository,
    private val generator: RecurrenceGenerator,
    private val transactionRunner: TransactionRunner,
) {

    // ------------------------------------------------------------------ reading

    /**
     * Every schedule, ordered by how much attention each wants: active first, then paused, then
     * retired; soonest due date first within a group.
     */
    fun observeSchedules(): Flow<List<RecurringScheduleWithProgress>> =
        recurringDao.observeSchedulesWithProgress()

    /**
     * One schedule with its category's name and its next due date, or `null` once it is gone.
     *
     * `null` is a value rather than an error for the same reason it is on the transaction details
     * screen: a schedule this screen was opened for not being there any more is an ordinary thing,
     * answered with a sentence.
     */
    fun observeSchedule(scheduleId: Long): Flow<RecurringScheduleWithProgress?> =
        recurringDao.observeScheduleWithProgress(scheduleId)

    /** One schedule's occurrences, newest first, each carrying the schedule's name and category. */
    fun observeOccurrences(scheduleId: Long): Flow<List<RecurringOccurrenceDetail>> =
        recurringDao.observeOccurrencesForSchedule(scheduleId)

    /**
     * One occurrence as it stands, for the rare caller that needs a one-shot read.
     *
     * The screens read through [observeOccurrences] instead: they must keep telling the truth while
     * the household acts on what they are showing.
     */
    suspend fun getOccurrence(occurrenceId: Long) = recurringDao.getOccurrenceById(occurrenceId)

    // -------------------------------------------------------- writing schedules

    /**
     * Creates a schedule and generates the occurrences it already implies, up to the planning
     * horizon, and returns its new `id`.
     *
     * Generating at creation is what makes a brand-new schedule show a next due date immediately
     * rather than only after the Recurring tab is next opened. It creates **expectations**, not
     * expenses: no transaction is written, and the household's totals do not move.
     *
     * @param amountPaise the expected amount, positive, in paise.
     * @param description optional free text; trimmed, and stored as `null` when blank.
     * @param unit what one unit is called ("litre"), or `null` when the household did not price it by
     *   unit. Trimmed, and stored as `null` when blank, exactly as [description] is.
     * @param ratePaise the price of one [unit], positive, in paise, or `null`. Given with [unit] or
     *   not at all.
     * @param today the household's current local calendar day, used to decide how far ahead to
     *   generate.
     * @throws IllegalArgumentException if the name is blank, the amount is not positive, the interval
     *   is outside `1..`[RecurrenceCalculator.MAX_INTERVAL], the end date is before the start date,
     *   the category does not exist or has been retired, or exactly one of [unit] and [ratePaise] was
     *   given, or the rate is not positive.
     */
    suspend fun createSchedule(
        name: String,
        categoryId: Long,
        amountPaise: Long,
        frequency: RecurrenceFrequency,
        intervalCount: Int,
        startDate: LocalDate,
        endDate: LocalDate?,
        today: LocalDate,
        description: String? = null,
        unit: String? = null,
        ratePaise: Long? = null,
        now: Instant = Instant.now(),
    ): Long {
        val normalizedUnit = unit.normalizedOrNull()

        requireValidSchedule(
            categoryId = categoryId,
            amountPaise = amountPaise,
            intervalCount = intervalCount,
            startDate = startDate,
            endDate = endDate,
            unit = normalizedUnit,
            ratePaise = ratePaise,
        )

        val schedule = RecurringScheduleEntity(
            name = RecurringScheduleEntity.requireValidName(name),
            description = description.normalizedOrNull(),
            categoryId = categoryId,
            amountPaise = amountPaise,
            unit = normalizedUnit,
            ratePaise = ratePaise,
            frequency = frequency,
            intervalCount = intervalCount,
            startDate = startDate,
            endDate = endDate,
            status = RecurringScheduleStatus.ACTIVE,
            generatedThrough = null,
            createdAt = now,
            updatedAt = now,
        )

        val id = recurringDao.insertSchedule(schedule)
        generator.generateFor(schedule.copy(id = id), today, now)
        return id
    }

    /**
     * Rewrites the schedule with [id] from the figures the household just edited, and reports whether
     * there was still such a schedule to rewrite.
     *
     * `id`, `createdAt`, `status` and the paid history are not among what can change: an edit cannot
     * move a schedule to a new identity, backdate when it was first recorded, silently un-pause a
     * paused schedule or un-retire a stopped one, and it can never rewrite an occurrence that has
     * already been settled. See the class documentation for the full policy.
     *
     * @return `false` when no schedule has [id] - it was retired from another screen, or this is a
     *   stale form - in which case nothing at all is written. No replacement is inserted.
     * @throws IllegalArgumentException on exactly the grounds [createSchedule] documents.
     */
    suspend fun updateSchedule(
        id: Long,
        name: String,
        categoryId: Long,
        amountPaise: Long,
        frequency: RecurrenceFrequency,
        intervalCount: Int,
        startDate: LocalDate,
        endDate: LocalDate?,
        today: LocalDate,
        description: String? = null,
        unit: String? = null,
        ratePaise: Long? = null,
        now: Instant = Instant.now(),
    ): Boolean {
        val existing = recurringDao.getScheduleById(id) ?: return false
        val normalizedUnit = unit.normalizedOrNull()

        requireValidSchedule(
            categoryId = categoryId,
            amountPaise = amountPaise,
            intervalCount = intervalCount,
            startDate = startDate,
            endDate = endDate,
            unit = normalizedUnit,
            ratePaise = ratePaise,
            // A category the schedule is already filed under may keep it even if it has since been
            // retired: the picker does not offer retired categories, but a schedule must not become
            // uneditable because a category was turned off after it was written. Any *other*
            // category has to be an active one, which is what the picker can actually offer.
            allowRetiredCategory = categoryId == existing.categoryId,
        )

        val updated = existing.copy(
            name = RecurringScheduleEntity.requireValidName(name),
            description = description.normalizedOrNull(),
            categoryId = categoryId,
            amountPaise = amountPaise,
            unit = normalizedUnit,
            ratePaise = ratePaise,
            frequency = frequency,
            intervalCount = intervalCount,
            startDate = startDate,
            endDate = endDate,
            generatedThrough = rewindProgressFor(existing, startDate),
            updatedAt = now,
        )

        recurringDao.updateSchedule(updated)
        // Generation is idempotent and only inserts what is missing, so running it after an edit
        // fills in whatever the new rule implies beyond what the old one had already produced.
        generator.generateFor(updated, today, now)
        return true
    }

    /**
     * Stops a schedule generating anything new, keeping the schedule and everything already
     * generated.
     *
     * Occurrences already generated stay exactly as they were - including the unpaid ones, which the
     * household may still want to pay or skip. Pausing is not a deletion and not an edit.
     *
     * @return `false` when the schedule was not active (already paused, stopped, or gone), which is
     *   reported as "nothing changed" rather than as a failure.
     */
    suspend fun pause(scheduleId: Long, now: Instant = Instant.now()): Boolean =
        recurringDao.pauseSchedule(
            id = scheduleId,
            from = RecurringScheduleStatus.ACTIVE,
            to = RecurringScheduleStatus.PAUSED,
            now = now,
        ) == CHANGED

    /**
     * Starts a paused schedule generating again, and fills in what the new rule implies from today
     * onward.
     *
     * ### The catch-up policy
     *
     * Nothing is ever generated for the period the schedule was **paused**: pausing means "stop
     * expecting this", so the dates that fell inside the pause are not back-filled, and the progress
     * marker is moved to yesterday before generating. A monthly schedule on the 5th that was paused
     * through October and resumed on 20 October therefore comes back with 5 November next, not with a
     * suddenly overdue 5 October.
     *
     * Calling this twice is harmless: the second call finds the schedule already active, changes
     * nothing, and generates nothing.
     *
     * @return `false` when the schedule was not paused.
     */
    suspend fun resume(
        scheduleId: Long,
        today: LocalDate,
        now: Instant = Instant.now(),
    ): Boolean {
        val resumed = recurringDao.resumeSchedule(
            id = scheduleId,
            from = RecurringScheduleStatus.PAUSED,
            to = RecurringScheduleStatus.ACTIVE,
            generatedThrough = today.minusDays(1),
            now = now,
        )
        if (resumed != CHANGED) return false

        recurringDao.getScheduleById(scheduleId)?.let { generator.generateFor(it, today, now) }
        return true
    }

    /**
     * Stops a schedule for good: no further occurrences, ever.
     *
     * The row and its occurrences are kept - the ledger entries already paid under it must keep
     * something truthful to point at - and this is one-way by design. Re-starting a finished schedule
     * would be a new schedule, with its own start date and its own history.
     *
     * @return `false` when the schedule was already retired or gone.
     */
    suspend fun retire(scheduleId: Long, now: Instant = Instant.now()): Boolean =
        recurringDao.retireSchedule(
            id = scheduleId,
            active = RecurringScheduleStatus.ACTIVE,
            paused = RecurringScheduleStatus.PAUSED,
            to = RecurringScheduleStatus.RETIRED,
            now = now,
        ) == CHANGED

    // ------------------------------------------------------ writing occurrences

    /**
     * Brings every **active** schedule's occurrences up to date, returning how many were created.
     *
     * This is the whole trigger for the engine: the Recurring screen calls it once when it opens, and
     * everything the household then sees arrives through the database's own `Flow`s. It is a small,
     * bounded, offline write that creates only expectations - never a transaction - and running it
     * repeatedly is a no-op, so no scheduler and no background service is needed to keep a household's
     * due dates current.
     */
    suspend fun generateDueOccurrences(today: LocalDate, now: Instant = Instant.now()): Int {
        var created = 0
        for (schedule in recurringDao.getSchedulesWithStatus(RecurringScheduleStatus.ACTIVE)) {
            created += generator.generateFor(schedule, today, now)
        }
        return created
    }

    /**
     * Resolves an occurrence as **not paid**: no transaction is created, no total moves, and the
     * occurrence is kept so that it is never generated again.
     *
     * One conditional `UPDATE` decides the outcome, so skipping the same occurrence twice reports the
     * second attempt honestly rather than silently succeeding twice.
     */
    suspend fun skipOccurrence(
        occurrenceId: Long,
        now: Instant = Instant.now(),
    ): SkipOutcome {
        val changed = recurringDao.markOccurrenceSkipped(
            id = occurrenceId,
            pending = RecurringOccurrenceStatus.PENDING,
            skipped = RecurringOccurrenceStatus.SKIPPED,
            now = now,
        )
        if (changed == CHANGED) return SkipOutcome.Skipped

        // The update changed nothing, so the occurrence was either already settled or never existed.
        // Which of the two is settled by one read, on a path that only runs when nothing was written.
        return if (recurringDao.getOccurrenceById(occurrenceId) == null) {
            SkipOutcome.OccurrenceNotFound
        } else {
            SkipOutcome.AlreadySettled
        }
    }

    /**
     * Records the household's payment of one occurrence: writes the actual entry and links the two
     * together, or writes nothing at all.
     *
     * ### What it writes
     *
     * An entry for [amountPaise], filed under the schedule's category and therefore of **that
     * category's own type** - income for an income schedule, an expense for an expense one - dated
     * the occurrence's own due date, described with the schedule's name. The rules for that row - a
     * positive amount, a category that exists and is of the entry's type - are enforced by
     * [TransactionRepository.add], which this deliberately goes through rather than around.
     *
     * ### Why the amount is an argument
     *
     * The household may have paid something slightly different from what was expected, so the caller
     * passes what was actually paid. The screens default it to the occurrence's expected amount, and
     * validate it with the same exact-parsing rule the add form uses.
     *
     * ### Why the unit price is *not* carried into the entry
     *
     * The schedule's [RecurringScheduleEntity.unit] and [RecurringScheduleEntity.ratePaise] describe
     * what the household expects to buy and at what price; the ledger records what was actually paid,
     * which may be a different quantity of it. Copying a rate across without the quantity that made
     * the amount would put two figures on the entry that do not multiply out - which is exactly what
     * [TransactionRepository] refuses to store - so the rate stays where the household is going to
     * read it, on the plan.
     *
     * ### Idempotence, in three layers
     *
     *  1. the occurrence is re-read inside the transaction and a non-pending one is refused before
     *     anything is written;
     *  2. the link is a conditional `UPDATE ... WHERE status = 'PENDING'`, so if two payments race,
     *     the loser changes `0` rows;
     *  3. when that happens the whole transaction is rolled back - the loser's entry is undone -
     *     and the caller is told the occurrence was already settled rather than shown a second
     *     payment. That is the one place this class deliberately throws to abort a write.
     *
     * @throws IllegalArgumentException if [amountPaise] is not positive, or the schedule's category
     *   has since been deleted. Neither can be produced by the app's own screens.
     */
    suspend fun markOccurrencePaid(
        occurrenceId: Long,
        amountPaise: Long,
        now: Instant = Instant.now(),
    ): MarkPaidOutcome = try {
        transactionRunner.inTransaction {
            val occurrence = recurringDao.getOccurrenceById(occurrenceId)
                ?: return@inTransaction MarkPaidOutcome.OccurrenceNotFound

            if (occurrence.status != RecurringOccurrenceStatus.PENDING) {
                return@inTransaction MarkPaidOutcome.AlreadySettled
            }

            // Impossible while the occurrence exists - its schedule is a non-null foreign key and
            // schedules are retired rather than deleted - so this is a net, not a path.
            val schedule = recurringDao.getScheduleById(occurrence.scheduleId)
                ?: return@inTransaction MarkPaidOutcome.OccurrenceNotFound

            // Also a net: the schedule's category is a non-null foreign key to a table whose rows are
            // retired rather than deleted. It is read here because the category's *type* is the
            // direction of the money, and the entry being written has to match it.
            val category = categories.getById(schedule.categoryId)
                ?: throw IllegalArgumentException(
                    "No category with id ${schedule.categoryId}, so occurrence $occurrenceId cannot " +
                        "be paid",
                )

            val transactionId = transactions.add(
                type = category.type,
                amountPaise = amountPaise,
                categoryId = schedule.categoryId,
                transactionDate = occurrence.dueDate,
                description = schedule.name,
                now = now,
            )

            val linked = recurringDao.markOccurrencePaid(
                id = occurrenceId,
                pending = RecurringOccurrenceStatus.PENDING,
                paid = RecurringOccurrenceStatus.PAID,
                transactionId = transactionId,
                now = now,
            )
            if (linked != CHANGED) throw OccurrenceSettledWhilePaying(occurrenceId)

            MarkPaidOutcome.Paid(transactionId)
        }
    } catch (_: OccurrenceSettledWhilePaying) {
        // The rollback has already undone this attempt's expense, so there is exactly one payment in
        // the database - the one that won the race - and the household is told so.
        MarkPaidOutcome.AlreadySettled
    }

    // ----------------------------------------------------------------- rules

    /**
     * Everything a schedule must satisfy that the schema cannot express, checked immediately before
     * the write - the same divide [TransactionRepository] documents: the repository holds the rules
     * that span more than one row, the schema holds everything a single row can state.
     *
     * The name is validated by [RecurringScheduleEntity.requireValidName] by the callers, so that the
     * normalised value it returns is the one that gets stored rather than a second normalisation - and
     * the same goes for [unit], which the callers have already trimmed and blanked.
     *
     * ### Why the category's *type* is not checked here
     *
     * Because there is nothing to check: an expense category makes an expense schedule and an income
     * category makes an income one, and both are legitimate. The direction is read from the category
     * wherever it is needed - the list, the details screen, and the entry [markOccurrencePaid] writes -
     * so a schedule cannot be filed under a category of the "wrong" type: it simply *is* that type.
     *
     * @param unit the already-normalised unit label, or `null`.
     * @param ratePaise the already-parsed price of one unit, or `null`.
     * @param allowRetiredCategory see [updateSchedule]: a schedule may keep the category it is already
     *   filed under even after that category was retired.
     */
    private suspend fun requireValidSchedule(
        categoryId: Long,
        amountPaise: Long,
        intervalCount: Int,
        startDate: LocalDate,
        endDate: LocalDate?,
        unit: String? = null,
        ratePaise: Long? = null,
        allowRetiredCategory: Boolean = false,
    ) {
        require(amountPaise > 0L) {
            "A recurring schedule must expect a positive amount; the direction of the money is " +
                "carried by the category it is filed under, not by a sign, so $amountPaise paise is " +
                "always a bug"
        }
        require(intervalCount in 1..RecurrenceCalculator.MAX_INTERVAL) {
            "A recurrence interval must be between 1 and ${RecurrenceCalculator.MAX_INTERVAL}, " +
                "but got $intervalCount"
        }
        require(endDate == null || !endDate.isBefore(startDate)) {
            "A schedule cannot end before it starts: start $startDate, end $endDate"
        }
        require((unit == null) == (ratePaise == null)) {
            "A unit price is a rate and what it is per unit, so they are recorded together or not " +
                "at all: got unit=$unit, ratePaise=$ratePaise"
        }
        require(ratePaise == null || ratePaise > 0L) {
            "A unit price must be positive; $ratePaise paise for one $unit is always a bug"
        }

        val category = categories.getById(categoryId)
            ?: throw IllegalArgumentException(
                "No category with id $categoryId; a recurring schedule must be filed under an " +
                    "existing category",
            )

        require(allowRetiredCategory || category.isActive) {
            "Category '${category.name}' has been retired, so it cannot be chosen for new recurring " +
                "schedules"
        }
    }

    /**
     * The progress marker a changed rule should continue from.
     *
     * Held where it was, unless the start date moved **earlier** than it used to be: then it is wound
     * back to the day before the new start date, so the occurrences the back-dated period implies are
     * filled in. Rows that already exist are ignored by the insert either way, so winding back can
     * only ever add what was genuinely missing.
     *
     * Comparing against the *old start date* rather than against the marker is what keeps an edit that
     * does not move the start date - which is most edits - from regenerating anything at all. A
     * schedule nothing has been generated for yet has no marker to wind back, and generation will fill
     * in from its start date anyway.
     */
    private fun rewindProgressFor(
        existing: RecurringScheduleEntity,
        newStartDate: LocalDate,
    ): LocalDate? {
        if (!newStartDate.isBefore(existing.startDate)) return existing.generatedThrough
        if (existing.generatedThrough == null) return null

        // The marker is never before the schedule's own start date - the calculator never produces an
        // occurrence earlier than it - so moving the start date earlier always winds the marker back.
        return newStartDate.minusDays(1)
    }
}

/**
 * What became of an attempt to record a payment.
 *
 * @property transactionId the ledger entry that was written, for a caller that wants to follow it.
 */
sealed interface MarkPaidOutcome {

    /** The expense was written and the occurrence now points at it. */
    data class Paid(val transactionId: Long) : MarkPaidOutcome

    /** There is no such occurrence - most likely the schedule was retired and the screen is stale. */
    data object OccurrenceNotFound : MarkPaidOutcome

    /**
     * The occurrence was already paid or skipped before this attempt, so nothing was written.
     *
     * Reported rather than swallowed: the household should learn that the bill was already settled -
     * possibly by their own earlier tap - instead of being told about a payment that this attempt did
     * not make.
     */
    data object AlreadySettled : MarkPaidOutcome
}

/** What became of an attempt to resolve an occurrence as deliberately not paid. */
sealed interface SkipOutcome {

    /** The occurrence is now skipped, and no ledger entry was created. */
    data object Skipped : SkipOutcome

    /** There is no such occurrence. */
    data object OccurrenceNotFound : SkipOutcome

    /** The occurrence had already been paid or skipped, so nothing was changed. */
    data object AlreadySettled : SkipOutcome
}

/**
 * Thrown to abort the payment transaction when the occurrence stopped being pending while the payment
 * was being written.
 *
 * Private to this file: it is a signal *inside* the transaction, translated into [MarkPaidOutcome]
 * before any caller can see it. Throwing is what asks Room to roll the attempt back.
 */
private class OccurrenceSettledWhilePaying(occurrenceId: Long) : IllegalStateException(
    "Occurrence $occurrenceId was settled by another write while this payment was being recorded",
)

/** How many rows a conditional `UPDATE` changed when it did exactly what was asked. */
private const val CHANGED = 1

/**
 * The one normalisation policy for a schedule's optional free text: trim the ends, and treat blank as
 * "not given" so that `""` and `"   "` cannot become a stored value that renders as an empty line.
 *
 * Deliberately the same shape as the one [TransactionRepository] applies to a transaction's own
 * description and notes, for the same reason: `null` is a fact the screens can act on ("there is no
 * description"), while an empty string is a fact they have to keep testing for.
 */
private fun String?.normalizedOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
