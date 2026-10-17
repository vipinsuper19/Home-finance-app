package com.homefinance.app.feature.transactiondetail

import androidx.compose.runtime.Immutable
import com.homefinance.app.data.local.relation.TransactionWithCategory

/**
 * Everything the transaction details screen renders, as one of exactly four mutually exclusive
 * snapshots.
 *
 * ### Why a sealed hierarchy
 *
 * This is a *read* - the same shape of problem
 * [com.homefinance.app.feature.transactions.TransactionsUiState] is a hierarchy for, and for the
 * same reason: a class carrying `isLoading`, a transaction and an error message can represent states
 * that do not exist, and the dangerous one is "an error next to figures that are still on screen".
 * Here the four outcomes are an exhaustive `when`, so the compiler forces the screen to decide what
 * each of them looks like.
 *
 * ### Why it carries the projection rather than a copy of it
 *
 * The transaction is held exactly as the join returned it - the same
 * [TransactionWithCategory] the ledger's rows arrive as - because this screen draws *all* of it and
 * there is nothing to narrow. A second data class holding the same eleven fields would be a copy to
 * keep in step for no gain, and it would have to be updated every time a column was added. The
 * ledger's own rows are narrowed to a
 * [com.homefinance.app.feature.transactions.TransactionListItem], and legitimately so: a row draws
 * four things out of eleven.
 *
 * The amounts stay an exact `Long` count of paise here and are formatted by the composable through
 * [com.homefinance.app.core.util.CurrencyFormatter], matching the ledger and the Home screen: no
 * pre-formatted, locale-dependent string is stored, so the state stays trivially testable and cannot
 * go stale when the locale changes.
 */
@Immutable
sealed interface TransactionDetailUiState {

    /** The first read, before the database has answered. */
    data object Loading : TransactionDetailUiState

    /**
     * There is no transaction with that id.
     *
     * Not an error: the entry was deleted, most likely from this very app, moments ago. It is a
     * separate state from [Failed] because it needs different words and a different way out - there
     * is nothing to retry, and retrying would only find it missing again.
     */
    data object NotFound : TransactionDetailUiState

    /**
     * The read failed.
     *
     * [cause] is kept so a test can prove *which* failure reached the screen and so a future crash
     * log has something to record. The screen never renders it: a SQLite exception message is not
     * something a household can act on, so the words come from string resources instead.
     */
    data class Failed(val cause: Throwable) : TransactionDetailUiState

    /**
     * The transaction, and whatever the household is currently doing to it.
     *
     * The three deletion fields ride along with the transaction rather than sitting in a variant of
     * their own, because they only ever mean anything *beside* a transaction: a confirmation about
     * nothing, or a failure about a row that is not there, are not states this screen can be in.
     *
     * @param transaction the row exactly as the database returned it.
     * @param deleteConfirmationVisible the "Delete transaction?" dialog is open, waiting for an
     *   answer. Held here rather than in the composable so that a rotation does not silently dismiss
     *   a question the household has not answered yet.
     * @param isDeleting a deletion is in flight: the dialog stays up with its action disabled, so a
     *   second tap cannot send a second delete.
     * @param deleteError why the last deletion failed, or `null`. Shown in the dialog, beside the
     *   action that caused it, because a failure there is a fact about that button.
     * @param deleted the transaction has been removed and the screen is leaving. Set only after the
     *   database has confirmed it, and terminal: the read that follows is already reporting the row's
     *   absence, and it must not overwrite this with "not found" on the way out.
     */
    @Immutable
    data class Content(
        val transaction: TransactionWithCategory,
        val deleteConfirmationVisible: Boolean = false,
        val isDeleting: Boolean = false,
        val deleteError: DeleteError? = null,
        val deleted: Boolean = false,
    ) : TransactionDetailUiState
}

/**
 * Why a deletion was refused.
 *
 * A sealed interface with one member, deliberately: it is the shape the rest of the app's write
 * failures take - see `SaveError` and `CategoryDialogError` - and it keeps the raw [Throwable] out of
 * the state's own type, so a failure that needs its own sentence can be added without changing the
 * state at all.
 */
@Immutable
sealed interface DeleteError {

    /**
     * The delete could not be carried out. The transaction is still in the database: nothing was
     * removed, so nothing needs to be put back.
     */
    data class Failed(val cause: Throwable) : DeleteError
}
