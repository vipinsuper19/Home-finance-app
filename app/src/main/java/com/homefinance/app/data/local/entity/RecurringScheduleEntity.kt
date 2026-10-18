package com.homefinance.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.homefinance.app.data.model.RecurrenceFrequency
import com.homefinance.app.data.model.RecurringScheduleStatus
import java.time.Instant
import java.time.LocalDate

/**
 * One recurring household item: rent on the 5th, milk every day, the cylinder every two months, a
 * salary on the 1st.
 *
 * ### A schedule is a plan, not money
 *
 * This row records an *expectation*. It is not an entry, it moves no total, and nothing in this
 * table is ever counted in the ledger: the money only becomes real when the household records one of
 * its occurrences as paid, which writes a separate [TransactionEntity]. That separation is the whole
 * point of the table, and it is why creating a schedule and letting its due date pass can never add
 * an entry on their own.
 *
 * ### Which way the money moves
 *
 * There is deliberately no `type` column: the direction is the *category's* own
 * [CategoryEntity.type], and a schedule whose category is an income category produces income when one
 * of its occurrences is paid. A copy of the direction here would be a second place for it to be
 * wrong, and the two would eventually disagree - the ledger entry is written against the category,
 * so the category is the authority.
 *
 * ### The shape of the rule
 *
 * [frequency] says how often and [intervalCount] how many of those units apart - "every two months"
 * is `MONTHLY` with an interval of 2. [startDate] is both the first occurrence and the **anchor**:
 * a monthly schedule recurs on the day-of-month it started on, never "30 days later", and a yearly
 * one on the same month and day. See [com.homefinance.app.core.util.RecurrenceCalculator] for the
 * rules about months that are too short for the anchor day.
 *
 * [endDate] is inclusive - the last date an occurrence may fall on - and `null` means the schedule
 * runs until the household pauses or retires it. There is deliberately no "number of occurrences"
 * field: a count and an end date can disagree with each other, and a household thinks in dates.
 *
 * ### Money
 *
 * [amountPaise] is the **expected** amount, a positive [Long] count of paise, and it is a *snapshot
 * of what the household expects* rather than a rule the engine re-derives. It never touches the
 * ledger by itself. Each generated occurrence copies it, so changing the schedule's amount changes
 * what happens next without rewriting what was already expected - the same policy
 * [com.homefinance.app.data.repository.RecurringRepository] documents for the rule as a whole.
 *
 * [ratePaise] and [unit] are the optional *unit price*: "₹60 a litre", "₹45 a kilo". They are the
 * two figures the household actually reads off a milk bill, so the list can say "Daily • ₹60/litre"
 * instead of a monthly total it had to work out. They are recorded together or not at all, and
 * neither is derived from the other: there is no quantity column, so
 * whether the expected amount is one unit or twenty is a fact only the household knows. The
 * expectation stays the authority for what an occurrence is worth, exactly as it does for every other
 * schedule.
 *
 * ### Progress, not status
 *
 * [generatedThrough] is the latest due date for which occurrences have been generated. It is an
 * *optimisation and a bound*, not the authority on what exists: the uniqueness of
 * `(schedule_id, due_date)` on [RecurringOccurrenceEntity] is what makes duplicates impossible, and
 * generation is safe to repeat at any time from any starting point. Because it only ever moves
 * forward when a write has actually succeeded, an interrupted run simply resumes.
 *
 * ### Deleting a schedule
 *
 * There is no delete. Retiring ([RecurringScheduleStatus.RETIRED]) stops the generation and keeps
 * the row, because the occurrences already paid under it are history and the ledger entries they
 * link to must keep something truthful to point at. The foreign key on
 * [RecurringOccurrenceEntity] refuses to delete a schedule that still has occurrences.
 *
 * @param id surrogate key, allocated by SQLite on insert (`0` means "not yet inserted").
 * @param name the display name ("Shop rent"), normalised by [requireValidName] before it is stored.
 * @param description free-text detail the household may add ("Ground floor, paid by UPI"), or `null`.
 *   Optional on purpose: a schedule that says everything in its name must not be forced to repeat
 *   itself. It is *not* used for anything financial - the amount, category and rule are all columns of
 *   their own - so it is never parsed and never has to be well-formed.
 * @param categoryId the category each occurrence's transaction will be filed under. The category's
 *   own type decides the direction of the money: an expense category makes this an expense schedule,
 *   an income category makes it an income one. The repository refuses anything but an existing,
 *   active category.
 * @param amountPaise the expected amount, positive, in paise.
 * @param unit what one unit of the thing is called - "litre", "kg", "piece" - or `null` when the
 *   household did not price it by unit. Free text, trimmed, and never parsed.
 * @param ratePaise the price of one [unit], positive, in paise, or `null`. Recorded with [unit] or
 *   not at all: a rate with nothing to price would be a figure the screens could not label.
 * @param frequency how often the schedule comes round.
 * @param intervalCount how many [frequency] units apart occurrences are; at least 1.
 * @param startDate the first occurrence and the anchor every later occurrence is derived from.
 * @param endDate the last date an occurrence may fall on, inclusive, or `null` for "until further
 *   notice".
 * @param status whether the schedule is still expected to come round; see
 *   [RecurringScheduleStatus].
 * @param generatedThrough the latest due date already generated, or `null` when none has been yet.
 * @param createdAt audit timestamp (UTC instant) for when the row was written.
 * @param updatedAt audit timestamp for when the row was last changed.
 */
@Entity(
    tableName = "recurring_schedules",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["category_id"]),
        // The engine asks "which schedules are still active?" on every visit to the Recurring tab,
        // so the status is worth an index of its own even though the table stays small.
        Index(value = ["status"]),
    ],
)
data class RecurringScheduleEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "description")
    val description: String? = null,

    @ColumnInfo(name = "category_id")
    val categoryId: Long,

    @ColumnInfo(name = "amount_paise")
    val amountPaise: Long,

    @ColumnInfo(name = "unit")
    val unit: String? = null,

    @ColumnInfo(name = "rate_paise")
    val ratePaise: Long? = null,

    @ColumnInfo(name = "frequency")
    val frequency: RecurrenceFrequency,

    @ColumnInfo(name = "interval_count")
    val intervalCount: Int,

    @ColumnInfo(name = "start_date")
    val startDate: LocalDate,

    @ColumnInfo(name = "end_date")
    val endDate: LocalDate? = null,

    @ColumnInfo(name = "status")
    val status: RecurringScheduleStatus = RecurringScheduleStatus.ACTIVE,

    @ColumnInfo(name = "generated_through")
    val generatedThrough: LocalDate? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Instant,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
) {
    companion object {

        /**
         * The one definition of the schedule naming policy, deliberately the same shape as
         * [CategoryEntity.normalizeName]: trim the ends and collapse any run of inner whitespace to
         * a single space, so `"  Shop   rent "` becomes `"Shop rent"`.
         *
         * Schedules are *not* unique by name - a household may legitimately have "Milk" morning and
         * evening - so there is no unique index here and normalising is about display rather than
         * about identity.
         */
        fun normalizeName(raw: String): String = raw.trim().replace(WHITESPACE_RUN, SINGLE_SPACE)

        /**
         * Normalises [raw] and rejects a name that is empty once normalised.
         *
         * @throws IllegalArgumentException if [raw] is blank or only whitespace.
         */
        fun requireValidName(raw: String): String {
            val normalized = normalizeName(raw)
            require(normalized.isNotEmpty()) { "A schedule name must not be blank" }
            return normalized
        }

        private const val SINGLE_SPACE = " "
        private val WHITESPACE_RUN = Regex("\\s+")
    }
}
