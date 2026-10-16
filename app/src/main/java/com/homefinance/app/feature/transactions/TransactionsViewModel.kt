package com.homefinance.app.feature.transactions

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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Backs the Transactions screen.
 *
 * ### Where the data comes from
 *
 * The screen shows what [TransactionRepository.observeAllWithCategory] emits - the ledger joined
 * to its category names in a single query. Nothing here polls and nothing here re-queries: Room
 * pushes a new list whenever a row changes, so the screen updates itself once transaction editing
 * arrives, with no change to this class.
 *
 * ### Loading, failing, retrying
 *
 * `observeAllWithCategory()` is cold, so the failure of the underlying query is observable rather
 * than fatal. [uiState] is that flow, mapped into [TransactionsUiState] and shared:
 *
 *  - `onStart` publishes [TransactionsUiState.Loading] before the first row arrives;
 *  - `catch` turns a failure into [TransactionsUiState.Error] and **ends** the flow, so a failed
 *    query cannot be followed by a late success that reorders the screen underneath the user;
 *  - [retry] re-subscribes from scratch, which is the only thing that can legitimately produce a
 *    different answer to a question the database has already refused once.
 *
 * A `catch` that swallows the failure is the point: a broken read must degrade to an error view,
 * not to a crash on a screen the household opened to look at their own money. The failure is
 * carried into the state rather than logged and discarded - there is no crash reporting to send it
 * to, and discarding it would leave the screen on a spinner forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModel(
    private val transactions: TransactionRepository,
) : ViewModel() {

    private val retryRequests = MutableStateFlow(0)

    val uiState: StateFlow<TransactionsUiState> =
        retryRequests
            .flatMapLatest { observeTransactions() }
            .stateIn(
                scope = viewModelScope,
                // The screen stops being collected when the user leaves the tab. Keeping the
                // subscription alive briefly means switching tabs and coming straight back does
                // not throw the list away and rebuild it.
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
                initialValue = TransactionsUiState.Loading,
            )

    /**
     * Asks for the ledger again after a failure.
     *
     * Bumping a counter rather than calling into the query directly keeps this class free of
     * coroutine bookkeeping: the flow restarts itself, and a replay arrives through exactly the
     * same path as the first attempt.
     */
    fun retry() {
        retryRequests.update { it + 1 }
    }

    private fun observeTransactions(): Flow<TransactionsUiState> =
        transactions.observeAllWithCategory()
            // The element type is stated explicitly: without it Kotlin narrows the flow to
            // `Content`, and the `Loading` and `Error` emissions below would no longer type-check.
            .map<List<TransactionWithCategory>, TransactionsUiState> { rows ->
                TransactionsUiState.Content(rows.map { it.toListItem() })
            }
            .onStart { emit(TransactionsUiState.Loading) }
            .catch { failure -> emit(TransactionsUiState.Error(failure)) }

    companion object {

        private const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L

        /**
         * Builds the view model from the application-scoped repositories.
         *
         * This is the project's one wiring seam - the application class owns the single database
         * and the repositories that wrap it, and there is deliberately no dependency-injection
         * framework. Passing the repositories in through the constructor is what keeps this class
         * testable off-device with fakes.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY],
                ) { "TransactionsViewModel needs the Application to reach the repositories" }

                TransactionsViewModel((application as HomeFinanceApp).transactionRepository)
            }
        }
    }
}
