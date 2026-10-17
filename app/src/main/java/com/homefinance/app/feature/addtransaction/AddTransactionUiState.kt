package com.homefinance.app.feature.addtransaction

import androidx.compose.runtime.Immutable
import com.homefinance.app.core.util.MoneyParseResult
import com.homefinance.app.data.model.TransactionType
import java.time.LocalDate

/**
 * Everything the add-transaction form renders, as a single immutable snapshot.
 *
 * ### Why this is a `data class` and not a sealed hierarchy
 *
 * [com.homefinance.app.feature.transactions.TransactionsUiState] is a sealed hierarchy because a
 * ledger read has to make "an error showing stale figures" impossible. A *form* is a different
 * shape of problem: the household is mid-edit, and their typed amount, chosen category, date and
 * description legitimately coexist with a category list that is still loading. Splitting that
 * into `Loading`/`Error`/`Content` variants would mean duplicating every field across each one and
 * throwing the user's half-typed entry away whenever the category query re-emitted.
 *
 * The invariant that *does* matter here - "never claim the entry was saved when it was not" - is
 * not a property of the state's shape. It is enforced where it belongs: [savedTransactionId] is
 * set only after the repository call has returned, and the repository itself re-validates the
 * amount and the category against the database before it writes anything.
 *
 * @param type which side of the ledger the entry is for; the form can flip it.
 * @param amountInput exactly what the household typed, unformatted, so the field never fights the
 *   user by rewriting their digits mid-keystroke.
 * @param amountError the reason the last save attempt rejected [amountInput], or `null`. Cleared as
 *   soon as the user edits the field again.
 * @param categoryId the chosen category, or `null` for none yet.
 * @param showCategoryError set when a save attempt was refused because no category was chosen.
 * @param date the calendar day the money moved; starts at today.
 * @param description optional free text.
 * @param categories the selectable categories for [type], still loading or failed.
 * @param isSaving a save is in flight; the button is disabled so a double tap cannot write twice.
 * @param saveError why the last save attempt failed, or `null`.
 * @param savedTransactionId the id the repository allocated once the write actually landed. The
 *   screen navigates away when this becomes non-null and refuses further saves until it does.
 * @param categoryDialog the state of the "new category" dialog, or `null` when it is closed.
 */
@Immutable
data class AddTransactionUiState(
    val type: TransactionType,
    val date: LocalDate,
    val amountInput: String = "",
    val amountError: MoneyParseResult.Problem? = null,
    val categoryId: Long? = null,
    val showCategoryError: Boolean = false,
    val description: String = "",
    val categories: CategoriesUiState = CategoriesUiState.Loading,
    val isSaving: Boolean = false,
    val saveError: SaveError? = null,
    val savedTransactionId: Long? = null,
    val categoryDialog: CategoryDialogState? = null,
)

/**
 * The categories the form can offer for the currently selected type.
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

/** One selectable category: the id the ledger stores and the name the chip shows. */
@Immutable
data class CategoryOption(val id: Long, val name: String)

/** Why a save attempt failed. */
@Immutable
sealed interface SaveError {

    /**
     * The chosen category is no longer usable - it was removed, or its type no longer matches the
     * entry. The repository refuses the write rather than filing money under the wrong heading.
     */
    data object CategoryUnavailable : SaveError

    /** Any other failure from the write. [cause] is kept for tests and a future crash log. */
    data class Failed(val cause: Throwable) : SaveError
}

/**
 * The "new category" dialog, opened only when the household has no active category to pick yet.
 *
 * A database that starts empty is the normal case on a fresh install, so without this the form
 * would be permanently unusable: there would be nothing to file the first transaction under.
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
