package com.homefinance.app.feature.recurring

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.homefinance.app.R
import com.homefinance.app.core.util.MoneyParseResult
import com.homefinance.app.core.util.RecurrenceCalculator
import com.homefinance.app.data.model.RecurrenceFrequency
import com.homefinance.app.feature.addtransaction.CategoriesUiState
import com.homefinance.app.feature.addtransaction.CategoryDialogState
import com.homefinance.app.feature.addtransaction.CategoryOption
import com.homefinance.app.feature.addtransaction.EditLoadState
import java.time.LocalDate

/**
 * The whole "new recurring expense" / "edit recurring expense" form, as one immutable snapshot.
 *
 * ### Why this is a `data class` and not a sealed hierarchy
 *
 * The same reason [com.homefinance.app.feature.addtransaction.AddTransactionUiState] is one: the
 * household is *mid-edit*. Their typed name, chosen category, typed amount, interval and dates
 * legitimately coexist with a category list that is still loading, and splitting that into
 * `Loading`/`Error`/`Content` variants would mean duplicating every field across each one and
 * discarding half-typed work whenever the category query re-emitted.
 *
 * The invariant that does matter - "never claim the schedule was saved when it was not" - is not a
 * property of this type's shape. It is enforced where it belongs: [savedScheduleId] is set only after
 * the repository call has returned, and the repository re-validates the name, the amount, the
 * interval, the dates and the category against the database before it writes anything.
 *
 * ### Why the dates are a `LocalDate` and never a string
 *
 * A schedule's start and end are plain calendar days, exactly like a transaction's date, and the form
 * holds them as the same type the database stores (an ISO epoch day). Nothing here compares date
 * *text*, so no locale, no timezone and no DST offset can shift a schedule's first or last day by one.
 *
 * @param name what the household called it, exactly as typed. Blank is caught on save rather than
 *   while typing, so a half-written name is not shouted at.
 * @param showNameError whether the last save attempt was refused for a missing name.
 * @param description free-text detail, optional. Sent as typed; the repository decides that blank
 *   means "not given" rather than storing an empty line.
 * @param amountInput the expected amount in rupees, exactly as typed. Parsed with the same exact
 *   parser the ledger's own form uses - no `Float`, no `Double`, no rounding.
 * @param category the chosen expense category, or `null` while none is chosen.
 * @param showCategoryError whether the last save attempt was refused for a missing category.
 * @param frequency how often the schedule repeats.
 * @param intervalInput how many periods between occurrences, as typed. It is text rather than an
 *   `Int` because an empty field and a field holding `abc` are two different things to say, and
 *   neither is a number.
 * @param intervalError why the typed interval was refused, or `null`.
 * @param startDate the day of the first occurrence, which also anchors every later one - a monthly
 *   schedule starting on the 31st recurs on the last day of every month, not on a "day of month"
 *   chosen from a list.
 * @param endDate the last day an occurrence may fall on, or `null` for "until paused".
 * @param showEndDateError whether the last save attempt was refused because the last occurrence
 *   precedes the first.
 * @param categories the active **expense** categories the picker can offer, including its own loading
 *   and failed states.
 * @param editLoad whether the row being changed has been read yet. An edit draws nothing until it has,
 *   so the household never sees one schedule's figures under another schedule's name.
 * @param isSaving whether a write is in flight. The save button is dead while it is true.
 * @param saveError why the last write failed, or `null`.
 * @param savedScheduleId the id the repository returned, or `null`. Only a non-null value here means
 *   the schedule exists; the screen leaves when it appears.
 * @param categoryDialog the "new category" dialog's state, or `null` when it is closed.
 */
@Immutable
data class RecurringFormUiState(
    val name: String = "",
    val showNameError: Boolean = false,
    val description: String = "",
    val amountInput: String = "",
    val amountError: MoneyParseResult.Problem? = null,
    val category: CategoryOption? = null,
    val showCategoryError: Boolean = false,
    val frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
    val intervalInput: String = DEFAULT_INTERVAL_INPUT,
    val intervalError: IntervalProblem? = null,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val showEndDateError: Boolean = false,
    val categories: CategoriesUiState = CategoriesUiState.Loading,
    val editLoad: EditLoadState = EditLoadState.Ready,
    val isSaving: Boolean = false,
    val saveError: RecurringSaveError? = null,
    val savedScheduleId: Long? = null,
    val categoryDialog: CategoryDialogState? = null,
) {

    /**
     * Whether the form is filled in enough to attempt a write.
     *
     * The save button is never disabled, deliberately. A form whose button goes dead with no
     * explanation is a form the household has to guess at; tapping it and being told which field is
     * missing is a better answer. The button *is* disabled while [isSaving] or after a successful
     * save, because those are the two states where a second tap would mean a second write.
     */
    val canAttemptSave: Boolean get() = !isSaving && savedScheduleId == null

    companion object {

        /** The interval a new schedule starts on: "every one month", i.e. monthly. */
        const val DEFAULT_INTERVAL_INPUT = "1"
    }
}

/**
 * Why the typed repeat interval was refused.
 *
 * Four distinct problems rather than one "invalid interval", for the same reason
 * [com.homefinance.app.core.util.MoneyParseResult] splits its failures: "you left this blank" and
 * "the largest interval this app can store is 1000" ask the household to do different things.
 */
@Immutable
sealed interface IntervalProblem {

    /** Nothing was typed. */
    data object Blank : IntervalProblem

    /** Not a run of plain digits - `1.5`, `-2` and `two` all land here. */
    data object NotANumber : IntervalProblem

    /** A whole number, but below one: a schedule cannot repeat zero times. */
    data object TooSmall : IntervalProblem

    /** A whole number, but past [RecurrenceCalculator.MAX_INTERVAL] - years past any real household. */
    data object TooLarge : IntervalProblem
}

/** What a typed repeat interval turned out to be. */
@Immutable
sealed interface IntervalParseResult {

    /** A whole number of periods, within the range the calculator accepts. */
    data class Success(val count: Int) : IntervalParseResult

    /**
     * The text could not be accepted.
     *
     * The problem is a separate member rather than a second field on [Success] so that "an interval,
     * but unfortunately it was a problem" is not a state the form can express - the same reasoning
     * behind [RecurrenceCalculator] returning a list and refusing an interval of zero up front.
     */
    data class Problem(val problem: IntervalProblem) : IntervalParseResult
}

/**
 * Reads the repeat interval the household typed.
 *
 * Deliberately strict about what a number looks like: only ASCII digits are accepted, one character at
 * a time, so `1.5`, `+2`, `-1` and `two` are all [IntervalProblem.NotANumber] rather than being partly
 * understood. Text is never trimmed into a different number than the one on screen, and the value is
 * never parsed as a floating-point number - an interval is a count of periods, and the only exact
 * representation of a count is an integer.
 */
fun parseInterval(raw: String): IntervalParseResult {
    val trimmed = raw.trim()

    if (trimmed.isEmpty()) {
        return IntervalParseResult.Problem(IntervalProblem.Blank)
    }

    if (!trimmed.all { it in '0'..'9' }) {
        return IntervalParseResult.Problem(IntervalProblem.NotANumber)
    }

    val value = trimmed.toLongOrNull()
        ?: return IntervalParseResult.Problem(IntervalProblem.TooLarge)

    return when {
        value < 1L -> IntervalParseResult.Problem(IntervalProblem.TooSmall)
        value > RecurrenceCalculator.MAX_INTERVAL.toLong() ->
            IntervalParseResult.Problem(IntervalProblem.TooLarge)
        else -> IntervalParseResult.Success(value.toInt())
    }
}

/**
 * Why a write was refused, in the three shapes the form draws.
 *
 * Kept apart from the field errors above because it is not about anything the household can point at
 * in a control: the category they chose may have been retired in another screen, the schedule they are
 * changing may have been stopped and deleted from under them, or the write itself may have failed.
 */
@Immutable
sealed interface RecurringSaveError {

    /**
     * The chosen category is gone, is not an expense category, or has been retired.
     *
     * Only reachable on a fresh choose: a schedule already filed under a category that has since been
     * retired keeps that category and can be saved unchanged, because otherwise turning a category off
     * would make the schedules under it uneditable.
     */
    data object CategoryUnavailable : RecurringSaveError

    /** The schedule being changed is no longer there. */
    data object ScheduleMissing : RecurringSaveError

    /** The write failed. [cause] is kept for tests and a future crash log. */
    data class Failed(val cause: Throwable) : RecurringSaveError
}

/** The sentence for one refused interval. */
@StringRes
internal fun IntervalProblem.messageRes(): Int = when (this) {
    IntervalProblem.Blank -> R.string.recurring_interval_error_blank
    IntervalProblem.NotANumber -> R.string.recurring_interval_error_not_a_number
    IntervalProblem.TooSmall -> R.string.recurring_interval_error_too_small
    IntervalProblem.TooLarge -> R.string.recurring_interval_error_too_large
}

/** The sentence for one refused write. */
@StringRes
internal fun RecurringSaveError.messageRes(): Int = when (this) {
    RecurringSaveError.CategoryUnavailable -> R.string.add_transaction_save_error_category
    RecurringSaveError.ScheduleMissing -> R.string.recurring_form_save_error_missing
    is RecurringSaveError.Failed -> R.string.recurring_form_save_error_generic
}
