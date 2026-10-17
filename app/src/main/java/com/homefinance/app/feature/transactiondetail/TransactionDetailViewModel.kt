package com.homefinance.app.feature.transactiondetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.homefinance.app.HomeFinanceApp
import com.homefinance.app.data.local.relation.TransactionWithCategory
import com.homefinance.app.data.repository.TransactionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs one transaction's details screen: it reads the row by id, and it deletes it.
 *
 * ### Where the data comes from
 *
 * The row is **observed**, not fetched once. That matters because this screen is where an edit
 * returns to: the household changes the amount, comes back, and the figures on screen are the ones
 * the database holds rather than the ones it was opened with. A one-shot read would leave the screen
 * showing the transaction as it was, which is exactly the "amount I saw five minutes ago" problem
 * [com.homefinance.app.feature.transactions.TransactionsUiState] exists to avoid.
 *
 * ### Why the state is one flow with a collector feeding it
 *
 * The row arrives from the database and the household's own actions - opening the confirmation,
 * confirming, failing - arrive from taps, and they are one screen's state even though they change for
 * different reasons. A single collector watches the row and merges each read into the state the
 * household is already looking at; [TransactionDetailUiState.mergedWithDeletionFrom] is that merge,
 * and it is where "a delete the household just confirmed" is kept from being overwritten by the
 * read that reports its own result.
 *
 * ### Deleting
 *
 * Nothing here removes the row from the screen as an act of optimism. The state reports the deletion
 * only after [TransactionRepository.deleteById] has returned, and the screen leaves on that - so a
 * failure leaves a transaction that is genuinely still there, in the state that says so, rather than
 * a hole where the household used to be able to see it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionDetailViewModel(
    private val transactions: TransactionRepository,
    private val transactionId: Long,
) : ViewModel() {

    private val state = MutableStateFlow<TransactionDetailUiState>(TransactionDetailUiState.Loading)

    /** The screen exactly as it should render it. */
    val uiState: StateFlow<TransactionDetailUiState> = state.asStateFlow()

    /**
     * A counter, not a boolean: each increment is a request to read the row again, and counting them
     * rather than flipping a flag means two retries in a row are two reads rather than one read and a
     * no-op. It is the same nudge [com.homefinance.app.feature.addtransaction.AddTransactionViewModel]
     * uses to restart a failed category read.
     */
    private val reloads = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            reloads
                .flatMapLatest { observeTransaction() }
                .collect { read -> state.update { current -> read.mergedWithDeletionFrom(current) } }
        }
    }

    /**
     * The transaction as one screen-state, from one read to its first value.
     *
     * `catch` ends the flow on failure instead of leaving it broken, so a failed read becomes a
     * sentence with a way out rather than a spinner that never stops. [retry] is that way out: it
     * throws the failed flow away and starts a new one.
     */
    private fun observeTransaction(): Flow<TransactionDetailUiState> =
        transactions.observeByIdWithCategory(transactionId)
            .map<TransactionWithCategory?, TransactionDetailUiState> { row ->
                if (row == null) {
                    TransactionDetailUiState.NotFound
                } else {
                    TransactionDetailUiState.Content(transaction = row)
                }
            }
            .onStart { emit(TransactionDetailUiState.Loading) }
            .catch { failure -> emit(TransactionDetailUiState.Failed(failure)) }

    /** Reads the transaction again after a failed read. Does nothing to a read that succeeded. */
    fun retry() {
        reloads.update { it + 1 }
    }

    /** Opens the "Delete transaction?" confirmation. Does nothing once a deletion is under way. */
    fun onDeleteRequested() {
        state.update { current ->
            if (current is TransactionDetailUiState.Content && !current.deleted) {
                current.copy(deleteConfirmationVisible = true, deleteError = null)
            } else {
                current
            }
        }
    }

    /**
     * Closes the confirmation without deleting anything.
     *
     * Refused while a deletion is in flight: the answer has already been given, and a dialog that
     * could still be dismissed mid-write would leave the household unsure whether it went through.
     */
    fun onDeleteDismissed() {
        state.update { current ->
            if (current is TransactionDetailUiState.Content && !current.isDeleting) {
                current.copy(deleteConfirmationVisible = false)
            } else {
                current
            }
        }
    }

    /**
     * Deletes the transaction and reports the outcome once the database has actually answered.
     *
     * A second call while one is in flight, or after one has landed, does nothing: the guard is here
     * as well as on the button, because a disabled button is a hint to the user and a guard in the
     * view model is a guarantee.
     *
     * `deleteById` reports how many rows it removed, and both answers are honest outcomes this method
     * tells apart:
     *
     *  - one row removed - the transaction is gone, and the screen leaves for the ledger;
     *  - zero rows - there was nothing to remove because the entry had already gone, so the screen
     *    says so instead of reporting a deletion it did not perform.
     */
    fun confirmDelete() {
        val current = state.value
        if (current !is TransactionDetailUiState.Content) return
        if (current.isDeleting || current.deleted) return

        state.value = current.copy(isDeleting = true, deleteError = null)

        viewModelScope.launch {
            val removed = try {
                transactions.deleteById(transactionId)
            } catch (failure: Exception) {
                state.update { it.withDeleteFailure(failure) }
                return@launch
            }

            state.update { latest ->
                when {
                    latest !is TransactionDetailUiState.Content -> latest
                    removed -> latest.copy(
                        isDeleting = false,
                        deleteConfirmationVisible = false,
                        deleted = true,
                    )
                    // The count is the database's own answer to "is it still there?", so the screen
                    // takes it at its word and reports what it found. The read that follows is
                    // already reporting the same absence.
                    else -> TransactionDetailUiState.NotFound
                }
            }
        }
    }

    /**
     * Applies a fresh read without losing what the household is doing to the row.
     *
     * A read arrives whenever the table changes, which happens for reasons that have nothing to do
     * with this screen - another entry being recorded, for one. The open confirmation, the delete in
     * flight and the sentence a failed delete produced are all facts about *this* screen's actions,
     * so they carry across; everything drawn from the row comes from the database.
     *
     * Three cases are settled before that merge, and all three are about a read that is not really
     * news:
     *
     *  - Once [TransactionDetailUiState.Content.deleted] is set the state is terminal. The next read
     *    is reporting the row's absence - the very deletion the household just confirmed - and letting
     *    it through would flip the screen from "done" to "not found" on its way out.
     *  - A read that reports the row gone **while this screen is removing it** is that removal's own
     *    echo. The database is free to tell this screen about its own delete before the delete call
     *    has returned to it - the invalidation and the write are two coroutines - so the screen must
     *    answer on the write's result rather than on the echo, or a confirmed deletion would
     *    occasionally end as "not found" instead of as a deletion.
     *  - A read of a row that is no longer there, at any other time, wins outright: the transaction
     *    has gone, and a confirmation about it is meaningless.
     */
    private fun TransactionDetailUiState.mergedWithDeletionFrom(
        current: TransactionDetailUiState,
    ): TransactionDetailUiState {
        if (current !is TransactionDetailUiState.Content) return this
        if (current.deleted) return current
        if (current.isDeleting && this is TransactionDetailUiState.NotFound) return current
        if (this !is TransactionDetailUiState.Content) return this

        return copy(
            deleteConfirmationVisible = current.deleteConfirmationVisible,
            isDeleting = current.isDeleting,
            deleteError = current.deleteError,
        )
    }

    /** Records a deletion failure only while the dialog is still open. */
    private fun TransactionDetailUiState.withDeleteFailure(cause: Throwable) =
        if (this is TransactionDetailUiState.Content) {
            copy(isDeleting = false, deleteError = DeleteError.Failed(cause))
        } else {
            this
        }

    companion object {

        /**
         * Builds the view model for the transaction with [transactionId].
         *
         * Same one wiring seam as the rest of the app: the application class owns the single database
         * and its repository, and there is deliberately no dependency-injection framework. The id is a
         * real argument because it is chosen by the caller - from the route the row was tapped open
         * with - rather than by the view model's own signature.
         */
        fun factory(transactionId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY],
                ) { "TransactionDetailViewModel needs the Application to reach the repository" }

                TransactionDetailViewModel(
                    transactions = (application as HomeFinanceApp).transactionRepository,
                    transactionId = transactionId,
                )
            }
        }

        /**
         * Builds the view model over [transactions] directly.
         *
         * The same seam as `HomeFinanceDatabase.build(context, name)` exists for: a test must be able
         * to render the real screen over a database of its own rather than the household's. Production
         * always takes [factory], and the two differ only in where the repository comes from.
         */
        fun factory(
            transactionId: Long,
            transactions: TransactionRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                TransactionDetailViewModel(
                    transactions = transactions,
                    transactionId = transactionId,
                )
            }
        }
    }
}
