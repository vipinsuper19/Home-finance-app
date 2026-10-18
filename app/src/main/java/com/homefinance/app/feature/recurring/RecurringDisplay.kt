package com.homefinance.app.feature.recurring

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.homefinance.app.R
import com.homefinance.app.core.util.MoneyParseResult
import com.homefinance.app.data.local.relation.RecurringOccurrenceDetail
import com.homefinance.app.data.local.relation.RecurringScheduleWithProgress
import com.homefinance.app.data.model.RecurrenceFrequency
import com.homefinance.app.data.model.RecurringOccurrenceStatus
import com.homefinance.app.data.model.RecurringScheduleStatus
import java.time.LocalDate

/**
 * How one scheduled occurrence reads to the household *right now*.
 *
 * ### Derived, never stored
 *
 * Two of these five states are facts that are written down - [PAID] and [SKIPPED] are the occurrence's
 * own terminal statuses - and the other three are a *reading* of one date against today. That split is
 * the whole reason `UPCOMING`, `DUE` and `OVERDUE` are not columns: a row written as "overdue" at one
 * minute past midnight would be wrong by the time anyone looked at it, and nothing would have written
 * it back. The stored status answers "what became of this date", which never changes; this answers
 * "where does this date stand against today", which changes every midnight and is therefore recomputed
 * every time it is drawn.
 *
 * Because it is derived, a device that has been asleep for a week shows the truth the moment it wakes
 * up, with no regeneration, no migration and no background job.
 */
@Immutable
enum class OccurrenceDisplayState {
    /** Its due date is still ahead. */
    UPCOMING,

    /** Its due date is today - it wants attention, but it is not late. */
    DUE,

    /** Its due date has passed and it is still worth recording or skipping. */
    OVERDUE,

    /** An expense was recorded for it. */
    PAID,

    /** The household decided it was not paid. */
    SKIPPED,
}

/**
 * Reads one occurrence's [status] and [dueDate] against [today].
 *
 * `PENDING` is the only status whose reading depends on today: the two written-down states mean the
 * same thing whenever they are read.
 */
fun occurrenceDisplayState(
    status: RecurringOccurrenceStatus,
    dueDate: LocalDate,
    today: LocalDate,
): OccurrenceDisplayState = when (status) {
    RecurringOccurrenceStatus.PAID -> OccurrenceDisplayState.PAID
    RecurringOccurrenceStatus.SKIPPED -> OccurrenceDisplayState.SKIPPED
    RecurringOccurrenceStatus.PENDING -> when {
        dueDate.isBefore(today) -> OccurrenceDisplayState.OVERDUE
        dueDate == today -> OccurrenceDisplayState.DUE
        else -> OccurrenceDisplayState.UPCOMING
    }
}

/**
 * True when nothing can be done to this occurrence any more.
 *
 * Paid and skipped are both final - the occurrence is kept, but neither can be re-settled, and neither
 * is ever generated again - so anything offering "mark paid" or "skip" has to check this first.
 */
fun OccurrenceDisplayState.isSettled(): Boolean =
    this == OccurrenceDisplayState.PAID || this == OccurrenceDisplayState.SKIPPED

/** The sentence that names [this] state. */
@StringRes
fun OccurrenceDisplayState.labelRes(): Int = when (this) {
    OccurrenceDisplayState.UPCOMING -> R.string.recurring_occurrence_upcoming
    OccurrenceDisplayState.DUE -> R.string.recurring_occurrence_due
    OccurrenceDisplayState.OVERDUE -> R.string.recurring_occurrence_overdue
    OccurrenceDisplayState.PAID -> R.string.recurring_occurrence_paid
    OccurrenceDisplayState.SKIPPED -> R.string.recurring_occurrence_skipped
}

/** The sentence that names [this] schedule state. */
@StringRes
fun RecurringScheduleStatus.labelRes(): Int = when (this) {
    RecurringScheduleStatus.ACTIVE -> R.string.recurring_status_active
    RecurringScheduleStatus.PAUSED -> R.string.recurring_status_paused
    RecurringScheduleStatus.RETIRED -> R.string.recurring_status_retired
}

/**
 * The one-word name of [this] frequency, for an interval of one: "Monthly".
 *
 * An interval of one is not drawn as "Every 1 month" - see [frequencySummary] - because the household
 * thinks of it as *monthly*, and a phrase that counts to one reads like a mistake.
 */
@StringRes
fun RecurrenceFrequency.labelRes(): Int = when (this) {
    RecurrenceFrequency.DAILY -> R.string.recurring_frequency_daily
    RecurrenceFrequency.WEEKLY -> R.string.recurring_frequency_weekly
    RecurrenceFrequency.MONTHLY -> R.string.recurring_frequency_monthly
    RecurrenceFrequency.YEARLY -> R.string.recurring_frequency_yearly
}

/** The counted phrase for [this] frequency: "Every 2 months". */
@StringRes
fun RecurrenceFrequency.everyPluralRes(): Int = when (this) {
    RecurrenceFrequency.DAILY -> R.plurals.recurring_frequency_every_days
    RecurrenceFrequency.WEEKLY -> R.plurals.recurring_frequency_every_weeks
    RecurrenceFrequency.MONTHLY -> R.plurals.recurring_frequency_every_months
    RecurrenceFrequency.YEARLY -> R.plurals.recurring_frequency_every_years
}

/**
 * The unit one period of [this] frequency is counted in: "months".
 *
 * Used where a count and its unit are shown together - the form's "Repeat every (months)" - so the unit
 * cannot be written one way beside a field and another way in a row's summary.
 */
@StringRes
internal fun RecurrenceFrequency.unitRes(): Int = when (this) {
    RecurrenceFrequency.DAILY -> R.string.recurring_frequency_unit_days
    RecurrenceFrequency.WEEKLY -> R.string.recurring_frequency_unit_weeks
    RecurrenceFrequency.MONTHLY -> R.string.recurring_frequency_unit_months
    RecurrenceFrequency.YEARLY -> R.string.recurring_frequency_unit_years
}

/**
 * Reads one schedule row as a screen draws it.
 *
 * The derived state is worked out here, once, against the caller's own [today], so a row cannot be drawn
 * as upcoming by the list and overdue by the details card an instant later. The projection's next-due
 * date is by definition the earliest still-`PENDING` occurrence, so the only three readings it can
 * produce are upcoming, due and overdue - never paid or skipped.
 */
internal fun RecurringScheduleWithProgress.toCard(today: LocalDate): RecurringScheduleCard =
    RecurringScheduleCard(
        id = id,
        name = name,
        description = description,
        categoryName = categoryName,
        categoryIconKey = categoryIconKey,
        amountPaise = amountPaise,
        frequency = frequency,
        intervalCount = intervalCount,
        status = status,
        startDate = startDate,
        endDate = endDate,
        addedAt = createdAt,
        nextDueDate = nextDueDate,
        nextDueState = nextDueDate?.let {
            occurrenceDisplayState(
                status = RecurringOccurrenceStatus.PENDING,
                dueDate = it,
                today = today,
            )
        },
    )

/**
 * Reads one occurrence row as a screen draws it.
 *
 * A `PAID` occurrence whose `transaction_id` has gone back to `null` had its expense deleted from the
 * ledger. That is carried as [RecurringOccurrenceItem.transactionRemoved] rather than by changing the
 * state: the occurrence really was paid, and rewriting that to "skipped" would be editing the past to
 * match the present.
 */
internal fun RecurringOccurrenceDetail.toItem(today: LocalDate): RecurringOccurrenceItem =
    RecurringOccurrenceItem(
        id = id,
        scheduleName = scheduleName,
        categoryName = categoryName,
        categoryIconKey = categoryIconKey,
        dueDate = dueDate,
        amountPaise = amountPaise,
        state = occurrenceDisplayState(status = status, dueDate = dueDate, today = today),
        transactionRemoved = transactionId == null && status == RecurringOccurrenceStatus.PAID,
    )

/**
 * How a schedule's rule reads: "Monthly" for an interval of one, "Every 2 months" otherwise.
 *
 * Resolved in the composable rather than in the view model because it is a *translation*, not a fact
 * about the schedule: the view model carries the frequency and the interval, which are the two things
 * the database holds, and the wording belongs where `stringResource` can reach it.
 */
@Composable
fun frequencySummary(frequency: RecurrenceFrequency, intervalCount: Int): String =
    if (intervalCount <= 1) {
        stringResource(frequency.labelRes())
    } else {
        pluralStringResource(frequency.everyPluralRes(), intervalCount, intervalCount)
    }

/**
 * The sentence under a rejected amount.
 *
 * The copy is the add form's own, deliberately: "the amount must be more than zero" is the same fact
 * whether the household is recording an expense or recording the payment of one, and two wordings
 * would suggest two rules. Only [MoneyParseResult.Problem] reaches here - [MoneyParseResult.Success]
 * carries a figure, not a problem - so there is no "and yet it worked" branch to write.
 */
@StringRes
internal fun MoneyParseResult.Problem.messageRes(): Int = when (this) {
    MoneyParseResult.Problem.Blank -> R.string.add_transaction_amount_error_blank
    MoneyParseResult.Problem.NotANumber -> R.string.add_transaction_amount_error_not_a_number
    MoneyParseResult.Problem.TooManyDecimalPlaces ->
        R.string.add_transaction_amount_error_too_precise
    MoneyParseResult.Problem.NotPositive -> R.string.add_transaction_amount_error_not_positive
    MoneyParseResult.Problem.TooLarge -> R.string.add_transaction_amount_error_too_large
}
