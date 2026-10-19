package com.homefinance.app.feature.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.homefinance.app.HomeFinanceApp
import com.homefinance.app.data.model.TransactionType
import com.homefinance.app.data.repository.ReportRepository
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
 * Backs the Reports screen.
 *
 * ### Where the data comes from
 *
 * Six reads of [ReportRepository], all of them **aggregates the database computed**: the period's
 * totals, the period before it, the two breakdowns, the per-day sums every chart is folded from, and
 * the handful of largest entries. Not one transaction is loaded into this class to be added up, so no
 * total here can disagree with the database about what a row is worth.
 *
 * They are combined rather than sequenced, so one change to the ledger - an entry added, edited or
 * deleted - re-runs all of them and the whole report moves together. That is what makes the screen
 * refresh itself after a transaction is recorded elsewhere, with no cooperation from this class.
 *
 * ### Why the period is a flow of its own
 *
 * [periodChoice] is what the household controls, and it sits *outside* the read: choosing a month
 * re-subscribes to the six queries with new date bounds rather than filtering rows already in memory,
 * because the right answer to "what did November cost?" is a question only the database can answer at
 * its own size. It also means the period is still known while a new one loads, so the heading and its
 * arrows never disappear underneath the household's finger.
 *
 * ### Loading, failing, retrying
 *
 * The reads are cold flows, so a failure is observable rather than fatal. Each read is arranged into
 * a small internal [ReportRead]: `onStart` publishes the period's loading state before the first row
 * arrives, `catch` turns a failure into a failed read **and ends the flow**, so a late success cannot
 * reorder the screen underneath the household, and [retry] re-subscribes from scratch. Nothing is
 * swallowed: a failure is carried into the state and drawn, because a report that showed a quiet
 * page of zeroes after a failed query would be telling the household they had spent nothing.
 *
 * @param today the day the screen believes it is, used to refuse a step into a month that has not
 *   happened. A constructor parameter so a test can pin it; production supplies the device's day.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModel(
    private val reports: ReportRepository,
    private val today: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val periodChoice = MutableStateFlow(ReportPeriod.initial(today))

    private val retryRequests = MutableStateFlow(0)

    val uiState: StateFlow<ReportsUiState> =
        combine(periodChoice, retryRequests) { period, _ -> period }
            .flatMapLatest { period -> readReport(period) }
            .map { read -> read.toUiState() }
            .stateIn(
                scope = viewModelScope,
                // Leaving the tab briefly on the way to a category's own report does not throw the
                // figures away and show a spinner when the household comes back.
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
                initialValue = currentLoadingState(),
            )

    /** Steps the whole window one month earlier. */
    fun onPreviousPeriod() {
        periodChoice.update { period -> period.previous() }
    }

    /**
     * Steps the whole window one month later, unless that would report on a month that has not
     * happened.
     *
     * The arrow is not drawn at all in that case - see [ReportsUiState.canGoForward] - but the guard
     * is kept here as well, because "cannot be asked for" and "cannot happen" are different
     * guarantees and this one is cheap.
     */
    fun onNextPeriod() {
        periodChoice.update { period ->
            if (period.canGoForward(YearMonth.from(today))) period.next() else period
        }
    }

    /** Changes how many months the report covers, keeping the month it ends at. */
    fun onRangeChange(range: ReportRange) {
        periodChoice.update { period -> period.withRange(range) }
    }

    /** Asks the database again after a failed read. */
    fun retry() {
        retryRequests.update { it + 1 }
    }

    /**
     * The six reads of one period, arranged into the one thing the screen draws.
     *
     * The daily sums are read from [ReportPeriod.earliestReadDate], which is the earlier of the
     * period's own start and the trend chart's six-month window - so one read serves both charts, and
     * the trend cannot ask about a month outside the range it was given.
     */
    private fun readReport(period: ReportPeriod): Flow<ReportRead> {
        val previous = period.previousPeriod

        val totals = combine(
            reports.observeTotals(period.from, period.to),
            reports.observeTotals(previous.from, previous.to),
        ) { current, earlier -> current to earlier }

        val breakdowns = combine(
            reports.observeCategoryTotals(TransactionType.EXPENSE, period.from, period.to),
            reports.observeCategoryTotals(TransactionType.INCOME, period.from, period.to),
        ) { expenses, incomes -> expenses to incomes }

        val charts = combine(
            reports.observeDailyTotals(period.earliestReadDate, period.to),
            reports.observeLargestInDateRange(period.from, period.to, TOP_AMOUNTS_LIMIT),
        ) { daily, largest -> daily to largest }

        return combine(totals, breakdowns, charts) { (current, earlier), (expenses, incomes), (daily, largest) ->
            ReportReads(
                period = period,
                totals = current,
                previousTotals = earlier,
                expenseBreakdown = expenses,
                incomeBreakdown = incomes,
                dailyTotals = daily,
                largest = largest,
            )
        }
            .map<ReportReads, ReportRead> { ReportRead.Rows(it) }
            // The element type is stated on `map` above: without it Kotlin narrows the flow to
            // `Rows` and the `Loading` and `Failed` emissions below would no longer type-check.
            .onStart { emit(ReportRead.Loading(period)) }
            .catch { failure -> emit(ReportRead.Failed(period, failure)) }
    }

    private fun ReportRead.toUiState(): ReportsUiState = when (this) {
        is ReportRead.Loading -> currentLoadingState(period)
        is ReportRead.Failed -> ReportsUiState.Failed(
            period = period,
            canGoForward = canGoForward(period),
            isCurrentMonth = isCurrentMonth(period),
            cause = cause,
        )
        is ReportRead.Rows -> reads.toContent(YearMonth.from(today))
    }

    private fun currentLoadingState(period: ReportPeriod = periodChoice.value): ReportsUiState.Loading =
        ReportsUiState.Loading(
            period = period,
            canGoForward = canGoForward(period),
            isCurrentMonth = isCurrentMonth(period),
        )

    private fun canGoForward(period: ReportPeriod): Boolean = period.canGoForward(YearMonth.from(today))

    private fun isCurrentMonth(period: ReportPeriod): Boolean = period.isCurrentMonth(YearMonth.from(today))

    /**
     * One read of the report, before the period has been folded into what a screen draws.
     *
     * The period travels with the result so that a read which arrives after the household has already
     * stepped to another month can be recognised for what it is. `flatMapLatest` cancels the previous
     * read when the period changes, so this is a belt-and-braces statement of intent rather than the
     * mechanism - but it is also what lets the loading and failed states name the period they are
     * about.
     */
    private sealed interface ReportRead {

        data class Loading(val period: ReportPeriod) : ReportRead

        data class Failed(val period: ReportPeriod, val cause: Throwable) : ReportRead

        data class Rows(val reads: ReportReads) : ReportRead
    }

    companion object {

        private const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L

        /**
         * How many of the period's largest entries the report names.
         *
         * Five: the section answers "what were the big ones?" rather than listing the ledger again,
         * and every one of them can still be opened. The database applies the limit, so a busy month
         * costs the same to draw as a quiet one.
         */
        private const val TOP_AMOUNTS_LIMIT = 5

        /**
         * Builds the view model against the application's own repositories.
         *
         * The same one wiring seam as the rest of the app: the application class owns the single
         * database, and there is deliberately no dependency-injection framework.
         */
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY],
                ) { "ReportsViewModel needs the Application to reach the repository" }

                ReportsViewModel((application as HomeFinanceApp).reportRepository)
            }
        }

        /**
         * Builds the view model over [repository] with [today] pinned.
         *
         * The seam a JVM test uses to pin the day and drive the screen's state machine without a
         * device.
         */
        fun factory(
            repository: ReportRepository,
            today: LocalDate,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ReportsViewModel(repository, today) }
        }
    }
}
