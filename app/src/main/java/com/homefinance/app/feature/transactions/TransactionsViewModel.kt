package com.homefinance.app.feature.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.homefinance.app.HomeFinanceApp
import com.homefinance.app.data.local.relation.TransactionWithCategory
import com.homefinance.app.data.repository.TransactionRepository
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
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
 * ### Searching and filtering
 *
 * The search text, the direction chips and the month selector are held here as three small state
 * flows and combined into one [TransactionFilterIntent]. Filtering itself is *not* a query: the
 * ledger is already in memory, and [toContent] applies the controls to it. This is deliberate -
 * a household types a search one character at a time, and none of those keystrokes should reach
 * SQLite, restart a cursor, or make the list flicker through a loading state.
 *
 * The consequence is that the whole ledger is read whenever it changes. At the size of one
 * household's records that is a few hundred rows and a few hundred microseconds; a future task
 * that needs server-sized data can push the filters into the query without changing this class's
 * public shape, because the filters are already a value.
 *
 * ### Loading, failing, retrying
 *
 * `observeAllWithCategory()` is cold, so the failure of the underlying query is observable rather
 * than fatal. The read is mapped into a small internal [LedgerRead] and combined with the filters:
 *
 *  - `onStart` publishes [LedgerRead.Loading] before the first row arrives;
 *  - `catch` turns a failure into [LedgerRead.Failed] and **ends** the flow, so a failed query
 *    cannot be followed by a late success that reorders the screen underneath the user;
 *  - [retry] re-subscribes from scratch, which is the only thing that can legitimately produce a
 *    different answer to a question the database has already refused once.
 *
 * A `catch` that swallows the failure is the point: a broken read must degrade to an error view,
 * not to a crash on a screen the household opened to look at their own money. The failure is
 * carried into the state rather than logged and discarded - there is no crash reporting to send it
 * to, and discarding it would leave the screen on a spinner forever.
 *
 * The filters are combined *outside* the read, so changing a filter re-renders the rows that are
 * already held without re-reading the database.
 *
 * @param today the day the screen considers to be today, used to label the newest headings. It is
 *   a constructor parameter so tests can pin it; a device supplies the real thing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModel(
    private val transactions: TransactionRepository,
    private val today: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")

    private val typeFilter = MutableStateFlow(TransactionTypeFilter.ALL)

    /** Null until the household picks a month, which is what makes the opening month follow the data. */
    private val monthChoice = MutableStateFlow<YearMonth?>(null)

    private val retryRequests = MutableStateFlow(0)

    private val filterIntent: Flow<TransactionFilterIntent> =
        combine(searchQuery, typeFilter, monthChoice) { query, type, month ->
            TransactionFilterIntent(query = query, type = type, month = month)
        }

    val uiState: StateFlow<TransactionsUiState> =
        combine(
            filterIntent,
            retryRequests.flatMapLatest { observeLedger() },
        ) { intent, read -> read.toUiState(intent) }
            .stateIn(
                scope = viewModelScope,
                // The screen stops being collected when the user leaves the tab. Keeping the
                // subscription alive briefly means switching tabs and coming straight back does
                // not throw the list away and rebuild it.
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
                initialValue = TransactionsUiState.Loading,
            )

    /** Applies the search text as it is typed. */
    fun onQueryChange(query: String) {
        searchQuery.value = query
    }

    /** Applies one of the direction chips. */
    fun onTypeFilterChange(filter: TransactionTypeFilter) {
        typeFilter.value = filter
    }

    /** Applies a month chosen from the selector. */
    fun onMonthChange(month: YearMonth) {
        monthChoice.value = month
    }

    /**
     * Puts every control back to its opening position.
     *
     * The month returns to `null` rather than to the month that is showing, so "clear" means the
     * same thing it means on first open - follow the ledger - instead of pinning whichever month
     * happened to be on screen when the household gave up on their filters.
     */
    fun clearFilters() {
        searchQuery.value = ""
        typeFilter.value = TransactionTypeFilter.ALL
        monthChoice.value = null
    }

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

    private fun observeLedger(): Flow<LedgerRead> =
        transactions.observeAllWithCategory()
            // The element type is stated explicitly: without it Kotlin narrows the flow to
            // `Loaded`, and the `Loading` and `Failed` emissions below would no longer type-check.
            .map<List<TransactionWithCategory>, LedgerRead> { LedgerRead.Loaded(it) }
            .onStart { emit(LedgerRead.Loading) }
            .catch { failure -> emit(LedgerRead.Failed(failure)) }

    private fun LedgerRead.toUiState(intent: TransactionFilterIntent): TransactionsUiState =
        when (this) {
            LedgerRead.Loading -> TransactionsUiState.Loading
            is LedgerRead.Failed -> TransactionsUiState.Error(cause)
            is LedgerRead.Loaded -> rows.toContent(intent = intent, today = today)
        }

    /**
     * One read of the ledger, before any control has been applied.
     *
     * Kept private and separate from [TransactionsUiState] because the screen has no business
     * knowing about a state that has not been filtered yet: three outcomes are what the screen
     * can draw, and this is the intermediate that produces them.
     */
    private sealed interface LedgerRead {

        data object Loading : LedgerRead

        data class Failed(val cause: Throwable) : LedgerRead

        data class Loaded(val rows: List<TransactionWithCategory>) : LedgerRead
    }

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
