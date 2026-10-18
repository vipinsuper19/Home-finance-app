package com.homefinance.app.feature.recurring

import androidx.compose.runtime.Immutable
import com.homefinance.app.core.util.MoneyInput
import com.homefinance.app.core.util.MoneyParseResult
import com.homefinance.app.data.repository.MarkPaidOutcome
import com.homefinance.app.data.repository.RecurringRepository
import com.homefinance.app.data.repository.SkipOutcome
import com.homefinance.app.data.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The two things a household can do to a due occurrence - record that it was paid, or record that it
 * was not - as one small state machine.
 *
 * ### Why this is not inside either view model
 *
 * Both the schedule's details screen and the occurrence history offer the same two actions on the same
 * rows, and neither owns the flow: the repository does. Keeping the machine here means the two screens
 * cannot disagree about what "already paid" means, and the whole thing - validation, the guard against
 * a second tap, and the translation of the repository's outcome into what the sheet shows - is
 * testable without a screen, a database or a navigation graph.
 *
 * ### What it deliberately does not do
 *
 * It does not create anything itself: both actions go through
 * [RecurringRepository.markOccurrencePaid] and [RecurringRepository.skipOccurrence], which are the
 * gates that own the rules - a positive amount, a category that exists, exactly one ledger entry per
 * occurrence, and a conditional `UPDATE` that makes a second attempt change nothing. Nothing here can
 * add a transaction by mistake, because nothing here writes one at all.
 *
 * It also does not report success. When a payment lands, the sheet closes and the state is left
 * `null`: the schedule and occurrence rows the household is looking at are database `Flow`s, so they
 * have already re-emitted with the new figures by the time the sheet is gone. There is no result to
 * hand back and no stale list to refresh.
 *
 * ### Why the amount is editable, and why that is not the expected amount
 *
 * A bill whose price changed since it was scheduled is the normal case, so the sheet starts at the
 * occurrence's **expected** amount - the figure the schedule committed to on that date - and lets the
 * household correct it to what was actually paid. The two are shown as two separate facts for exactly
 * that reason, and the corrected figure goes through the same exact parser the ledger's own form uses.
 * The occurrence keeps its expected amount either way; only the transaction records what was really
 * paid.
 *
 * @param instant the clock used for the audit stamps, injectable so a test can pin it.
 */
class OccurrenceActionsController(
    private val repository: RecurringRepository,
    private val instant: () -> Instant = Instant::now,
) {

    private val payState = MutableStateFlow<PayOccurrenceState?>(null)

    private val skipState = MutableStateFlow<SkipOccurrenceState?>(null)

    /** The open payment sheet, or `null` when it is closed. */
    val pay: StateFlow<PayOccurrenceState?> = payState.asStateFlow()

    /** The open "skip this occurrence" dialog, or `null` when it is closed. */
    val skip: StateFlow<SkipOccurrenceState?> = skipState.asStateFlow()

    /**
     * Opens the payment sheet for [target], with the amount field already holding what the schedule
     * expected.
     *
     * Opening a sheet is all this does. Nothing is written until [confirmPay] is called, so looking at
     * the amount an occurrence was expected to be worth can never cost the household a ledger entry.
     */
    fun requestPay(target: OccurrenceToSettle) {
        payState.value = PayOccurrenceState(
            occurrenceId = target.occurrenceId,
            scheduleName = target.scheduleName,
            categoryName = target.categoryName,
            type = target.type,
            categoryIconKey = target.categoryIconKey,
            dueDate = target.dueDate,
            expectedAmountPaise = target.amountPaise,
            amountInput = MoneyInput.toEditableText(target.amountPaise),
        )
    }

    /** Jots down what was actually paid, clearing whatever the last attempt complained about. */
    fun onPayAmountChange(text: String) {
        val current = payState.value ?: return
        payState.value = current.copy(amountInput = text, amountError = null, failure = null)
    }

    /**
     * Records the payment, or explains why it was not recorded.
     *
     * Re-entrant calls are refused outright: the first thing that happens on a confirmed payment is
     * that the dialog is marked as saving, and a second tap - from an impatient double-tap, or from a
     * recomposition landing between them - finds that flag set and returns. The database would refuse
     * the second payment anyway, but not writing a second ledger entry in the first place is better
     * than writing one and rolling it back.
     *
     * A failure never closes the sheet and never clears the typed figure: the household has just told
     * the app something and the app has to answer them about it.
     */
    suspend fun confirmPay() {
        val current = payState.value ?: return
        if (current.isSaving) return

        val paise = when (val parsed = MoneyInput.parseRupeesToPaise(current.amountInput)) {
            is MoneyParseResult.Success -> parsed.paise
            is MoneyParseResult.Problem -> {
                // No write, and the sheet stays exactly where it is.
                payState.value = current.copy(amountError = parsed, failure = null)
                return
            }
        }

        payState.value = current.copy(isSaving = true, amountError = null, failure = null)

        val outcome = try {
            repository.markOccurrencePaid(
                occurrenceId = current.occurrenceId,
                amountPaise = paise,
                now = instant(),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            payState.value = current.copy(isSaving = false, failure = SettleFailure.Failed(failure))
            return
        }

        payState.value = when (outcome) {
            // The ledgers rows the household is looking at have already re-emitted. Nothing to say.
            is MarkPaidOutcome.Paid -> null
            MarkPaidOutcome.OccurrenceNotFound -> current.copy(
                isSaving = false,
                failure = SettleFailure.OccurrenceNotFound,
            )
            MarkPaidOutcome.AlreadySettled -> current.copy(
                isSaving = false,
                failure = SettleFailure.AlreadySettled,
            )
        }
    }

    /** Closes the payment sheet without writing anything. */
    fun dismissPay() {
        // Refused while a write is in flight: the repository may already have inserted the entry, and
        // closing the sheet would hide the outcome that is about to arrive.
        if (payState.value?.isSaving == true) return
        payState.value = null
    }

    /** Opens the skip confirmation for [target]. Nothing is written until [confirmSkip]. */
    fun requestSkip(target: OccurrenceToSettle) {
        skipState.value = SkipOccurrenceState(
            occurrenceId = target.occurrenceId,
            scheduleName = target.scheduleName,
            dueDate = target.dueDate,
        )
    }

    /**
     * Marks the occurrence as deliberately not paid.
     *
     * No transaction is written and no total moves: this is the household saying the bill did not
     * happen. The occurrence is kept so it can never be generated again, and the failure of a second
     * attempt is reported rather than pretended away.
     */
    suspend fun confirmSkip() {
        val current = skipState.value ?: return
        if (current.isSaving) return

        skipState.value = current.copy(isSaving = true, failure = null)

        val outcome = try {
            repository.skipOccurrence(occurrenceId = current.occurrenceId, now = instant())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            skipState.value = current.copy(isSaving = false, failure = SettleFailure.Failed(failure))
            return
        }

        skipState.value = when (outcome) {
            SkipOutcome.Skipped -> null
            SkipOutcome.OccurrenceNotFound -> current.copy(
                isSaving = false,
                failure = SettleFailure.OccurrenceNotFound,
            )
            SkipOutcome.AlreadySettled -> current.copy(
                isSaving = false,
                failure = SettleFailure.AlreadySettled,
            )
        }
    }

    /** Closes the skip confirmation without changing anything. */
    fun dismissSkip() {
        if (skipState.value?.isSaving == true) return
        skipState.value = null
    }
}

/**
 * One occurrence, reduced to everything either settle flow needs to describe it.
 *
 * Both flows show the schedule's name, its category, its due date and what it expected, so the pair is
 * carried together rather than looked up again on a worker thread while the flow is already open.
 */
@Immutable
data class OccurrenceToSettle(
    val occurrenceId: Long,
    val scheduleName: String,
    val categoryName: String,
    /** The category's type: the direction of the entry a payment will write. */
    val type: TransactionType,
    /** The category's stored mark, or `null` when nobody chose one. Drawn on the sheet's card. */
    val categoryIconKey: String? = null,
    val dueDate: LocalDate,
    val amountPaise: Long,
)

/** Reduces a row the screen is drawing to what the settle flows need. */
internal fun RecurringOccurrenceItem.toSettleTarget(): OccurrenceToSettle = OccurrenceToSettle(
    occurrenceId = id,
    scheduleName = scheduleName,
    categoryName = categoryName,
    type = categoryType,
    categoryIconKey = categoryIconKey,
    dueDate = dueDate,
    amountPaise = amountPaise,
)

/**
 * The open "mark as paid" sheet.
 *
 * It carries the *facts the entry will be written with* as well as the field the household edits: the
 * schedule it belongs to, its category, which way the money moves, the due date and what was expected.
 * All of them are read from the occurrence the sheet was opened on rather than looked up again, so the
 * sheet cannot describe one payment and settle another.
 *
 * @param type the category's own type, so the sheet's wording and colour agree with the entry the write
 *   will produce, which re-reads the same category.
 * @param expectedAmountPaise what the occurrence was scheduled for, kept so the sheet can name both
 *   figures and say which is which.
 * @param amountInput exactly what the household typed, unformatted, so the field never fights the user
 *   by rewriting their digits mid-keystroke.
 * @param amountError why the last attempt rejected the typed figure, or `null`. Cleared on the next
 *   keystroke, not left standing while the household fixes it.
 * @param failure what the repository said, or `null`. Non-null disables the confirm button: the
 *   occurrence was already settled or has gone, so retrying the same tap can only fail again.
 */
@Immutable
data class PayOccurrenceState(
    val occurrenceId: Long,
    val scheduleName: String,
    val categoryName: String,
    /** The category's type: the direction of the entry that will be written when this confirms. */
    val type: TransactionType,
    /** The category's stored mark, or `null` when nobody chose one. Drawn on the sheet's card. */
    val categoryIconKey: String? = null,
    val dueDate: LocalDate,
    val expectedAmountPaise: Long,
    val amountInput: String = "",
    val amountError: MoneyParseResult.Problem? = null,
    val isSaving: Boolean = false,
    val failure: SettleFailure? = null,
)

/** The open "skip this occurrence" confirmation. */
@Immutable
data class SkipOccurrenceState(
    val occurrenceId: Long,
    val scheduleName: String,
    val dueDate: LocalDate,
    val isSaving: Boolean = false,
    val failure: SettleFailure? = null,
)

/** What went wrong when an occurrence was being settled, in the three shapes the settle flows draw. */
@Immutable
sealed interface SettleFailure {

    /** There is no such occurrence any more; the screen is looking at a stale row. */
    data object OccurrenceNotFound : SettleFailure

    /**
     * It was already paid or skipped - most likely by the household's own earlier tap.
     *
     * Reported rather than swallowed, because the alternative is a sheet that appears to have done
     * something when in fact the payment was recorded at some other moment.
     */
    data object AlreadySettled : SettleFailure

    /** The write failed. [cause] is kept for tests and a future crash log. */
    data class Failed(val cause: Throwable) : SettleFailure
}
