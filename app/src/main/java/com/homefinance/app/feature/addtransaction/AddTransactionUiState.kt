package com.homefinance.app.feature.addtransaction

import androidx.compose.runtime.Immutable
import com.homefinance.app.core.util.MoneyInput
import com.homefinance.app.core.util.MoneyParseResult
import com.homefinance.app.core.util.QuantityInput
import com.homefinance.app.core.util.QuantityParseResult
import com.homefinance.app.data.model.TransactionType
import java.time.LocalDate

/**
 * Everything the transaction form renders, as a single immutable snapshot.
 *
 * ### One form, two jobs
 *
 * The same form records a new entry and changes an existing one - same fields, same rules, same
 * arithmetic - so it has one state type and one view model rather than two of each that could drift
 * apart. What differs between the two jobs is only *where the form starts*: recording a new entry
 * begins from the household's defaults, and changing an existing one begins by reading that entry
 * out of the database. That one read is the only phase editing has that adding does not, and it is
 * [editLoad].
 *
 * ### Why this is a `data class` and not a sealed hierarchy
 *
 * [com.homefinance.app.feature.transactions.TransactionsUiState] is a sealed hierarchy because a
 * ledger read has to make "an error showing stale figures" impossible. A *form* is a different
 * shape of problem: the household is mid-edit, and their typed amount, chosen category, date,
 * description and notes legitimately coexist with a category list that is still loading. Splitting
 * that into `Loading`/`Error`/`Content` variants would mean duplicating every field across each one
 * and throwing the user's half-typed entry away whenever the category query re-emitted.
 *
 * The invariant that *does* matter here - "never claim the entry was saved when it was not" - is
 * not a property of the state's shape. It is enforced where it belongs: [savedTransactionId] is
 * set only after the repository call has returned, and the repository itself re-validates the
 * amount, the category and the quantity-and-rate arithmetic against the database before it writes
 * anything.
 *
 * ### Why the category is the whole option and not just its id
 *
 * The category is picked on a screen of its own, and this form has to be able to *name* what was
 * picked without asking the database again. Keeping the [CategoryOption] - an id and the name that
 * came with it - means the row can never show "nothing chosen" for a moment because the category
 * list happens to be reloading. The list is still the only place an option can come from: nothing
 * here invents a name. An edit widens that to "the list, or the entry being edited": a row filed
 * under a category that has since been retired still shows that category's name, because that is
 * the name the household filed it under, and the picker simply does not offer it again.
 *
 * @param type which side of the ledger the entry is for; the form can flip it.
 * @param amountInput exactly what the household typed, unformatted, so the field never fights the
 *   user by rewriting their digits mid-keystroke. Ignored while [useQuantityAndRate] is on, but kept,
 *   so switching the mode back and forth does not throw away what was typed.
 * @param amountError the reason the last save attempt rejected the amount - the typed one, or the one
 *   derived from [quantityInput] and [rateInput] - or `null`. Cleared as soon as the user edits.
 * @param category the chosen category, or `null` for none yet.
 * @param showCategoryError set when a save attempt was refused because no category was chosen.
 * @param date the calendar day the money moved; starts at today.
 * @param description optional one-line free text, shown as the ledger row's subtitle.
 * @param notes optional longer free text, kept apart from [description].
 * @param useQuantityAndRate whether the entry is being recorded as "1.5 at ₹60" instead of as a flat
 *   amount. A mode rather than a separate screen, because it is the same entry either way.
 * @param quantityInput exactly what the household typed as a quantity, unformatted.
 * @param quantityError the reason the last save attempt rejected [quantityInput], or `null`.
 * @param rateInput exactly what the household typed as a per-unit price, unformatted.
 * @param rateError the reason the last save attempt rejected [rateInput], or `null`.
 * @param categories the selectable categories for [type], still loading or failed. Rendered by the
 *   category picker; the form itself needs only [category].
 * @param editLoad where an edit is in reading the entry it is changing. [EditLoadState.Ready] on a
 *   form that is recording a new entry, which has nothing to read.
 * @param isSaving a save is in flight; the button is disabled so a double tap cannot write twice.
 * @param saveError why the last save attempt failed, or `null`.
 * @param savedTransactionId the id of the transaction this form has finished with once the write
 *   actually landed - allocated by an insert, or the one an update rewrote. The screen navigates
 *   away when this becomes non-null and refuses further saves until it does.
 * @param categoryDialog the state of the "new category" dialog, or `null` when it is closed. Opened
 *   from the category picker, never from the form.
 */
@Immutable
data class AddTransactionUiState(
    val type: TransactionType,
    val date: LocalDate,
    val amountInput: String = "",
    val amountError: MoneyParseResult.Problem? = null,
    val category: CategoryOption? = null,
    val showCategoryError: Boolean = false,
    val description: String = "",
    val notes: String = "",
    val useQuantityAndRate: Boolean = false,
    val quantityInput: String = "",
    val quantityError: QuantityParseResult.Problem? = null,
    val rateInput: String = "",
    val rateError: MoneyParseResult.Problem? = null,
    val categories: CategoriesUiState = CategoriesUiState.Loading,
    val editLoad: EditLoadState = EditLoadState.Ready,
    val isSaving: Boolean = false,
    val saveError: SaveError? = null,
    val savedTransactionId: Long? = null,
    val categoryDialog: CategoryDialogState? = null,
) {

    /**
     * The amount an entry recorded by quantity and rate will be saved as, or `null` when it cannot be
     * worked out yet.
     *
     * Derived rather than stored, so it cannot go stale: it is a pure function of the two fields the
     * household is typing, and it always answers with the exact figure
     * [com.homefinance.app.data.repository.TransactionRepository] would accept. When it is positive
     * the form shows it in the amount field, which is why the amount is never silently ambiguous
     * about which of the two modes is in force.
     *
     * `null` means "not computable yet" and covers three honest cases that the form treats alike -
     * the switch is off, one of the two fields is empty or invalid, or the product is too large to be
     * money at all. Which of them it was only matters when Save is tapped, and
     * [AddTransactionViewModel.save] reports it precisely there.
     */
    val derivedAmountPaise: Long?
        get() {
            if (!useQuantityAndRate) return null

            val thousandths = (QuantityInput.parseToThousandths(quantityInput) as? QuantityParseResult.Success)
                ?.thousandths ?: return null
            val ratePaise = (MoneyInput.parseRupeesToPaise(rateInput) as? MoneyParseResult.Success)
                ?.paise ?: return null

            return QuantityInput.amountPaise(thousandths, ratePaise)
        }
}

/**
 * Where an edit is in its one read of the entry it is changing.
 *
 * A sealed hierarchy, unlike the form's own state, because these four really are mutually
 * exclusive: a form that is still reading the row has no figures to show, and a form whose row has
 * gone must not be drawn as though it had. The alternative - a `loading` flag beside a `notFound`
 * flag beside the prefilled fields - would let the screen render a filled-in form for a
 * transaction that is not there, and let the household save it.
 *
 * [Ready] is also what an add form reports, because there is nothing for it to read. It therefore
 * means "this form may be drawn", which is exactly the question the screen asks.
 */
@Immutable
sealed interface EditLoadState {

    /** The row is being read; nothing is prefilled yet. */
    data object Loading : EditLoadState

    /**
     * The row was read and the form is filled in from it; the household may now change it.
     *
     * Also the state of an add form, which has no row to read and is ready from the start.
     */
    data object Ready : EditLoadState

    /**
     * There is no transaction with that id any more.
     *
     * Not an error: the entry was deleted - most likely from this very app, moments ago - and the
     * honest answer is a sentence saying so, not a failure or, worse, an empty form that would
     * insert a new transaction if it were saved.
     */
    data object NotFound : EditLoadState

    /** The read failed; [cause] is kept so a test can prove which failure arrived. */
    data class Failed(val cause: Throwable) : EditLoadState
}

/**
 * The categories the form can offer for its currently selected type.
 *
 * Retired categories are deliberately absent from [Ready.options]: a category that has been turned
 * off must not be offered for a *new* transaction even though it still labels the old ones. The
 * filtering happens in the view model, because [com.homefinance.app.data.local.dao.CategoryDao]
 * deliberately returns retired categories as well - history needs their names.
 */
@Immutable
sealed interface CategoriesUiState {

    /** The first read of the category list, before the database has answered. */
    data object Loading : CategoriesUiState

    /** The active categories for the selected type, alphabetically. Empty means none exist yet. */
    data class Ready(val options: List<CategoryOption>) : CategoriesUiState

    /** The category query failed; [cause] is kept so a test can prove which failure arrived. */
    data class Failed(val cause: Throwable) : CategoriesUiState
}

/** One selectable category: the id the ledger stores and the name the picker offers. */
@Immutable
data class CategoryOption(val id: Long, val name: String)

/** Why a save attempt failed. */
@Immutable
sealed interface SaveError {

    /**
     * The repository refused the write as impossible: there is no such category, or it is not of the
     * entry's type.
     *
     * In practice this is a net rather than a path the household can walk into. The picker offers only
     * categories of the form's own type, categories are retired rather than deleted so a chosen one
     * cannot vanish, and the amount sent with the write was derived by
     * [QuantityInput.amountPaise] - the very function the repository re-runs before accepting it - so
     * none of the rules [com.homefinance.app.data.repository.TransactionRepository] enforces can be
     * broken by anything this screen produces. It is reported anyway rather than swallowed, because
     * the alternative would be a form that looked like it had saved an entry that does not exist.
     */
    data object CategoryUnavailable : SaveError

    /**
     * An edit found no transaction with the id the form was opened for.
     *
     * The entry was deleted - from another screen, or moments before Save was tapped - between the
     * form filling itself in and the write being attempted. The repository refuses to insert a
     * replacement, so the household is told the entry is gone rather than being shown a success for
     * a change that never happened.
     */
    data object TransactionMissing : SaveError

    /** Any other failure from the write. [cause] is kept for tests and a future crash log. */
    data class Failed(val cause: Throwable) : SaveError
}

/**
 * The "new category" dialog, opened from the category picker and never from the form.
 *
 * The form deliberately offers no way to create a category: filing an entry and inventing a place to
 * file it are two different jobs, and only the second one belongs on a picker. It still has to exist
 * there, because a database that starts empty is the normal case on a fresh install - without it
 * there would be nothing at all to file the household's first transaction under.
 */
@Immutable
data class CategoryDialogState(
    val nameInput: String = "",
    val isSaving: Boolean = false,
    val error: CategoryDialogError? = null,
)

/** Why creating a category failed. Each maps to its own sentence in the dialog. */
@Immutable
sealed interface CategoryDialogError {

    /** The name was empty once trimmed. */
    data object BlankName : CategoryDialogError

    /** A category with that name already exists for this type, compared case-insensitively. */
    data object Duplicate : CategoryDialogError

    /** Any other failure. [cause] is kept for tests and a future crash log. */
    data class Failed(val cause: Throwable) : CategoryDialogError
}
