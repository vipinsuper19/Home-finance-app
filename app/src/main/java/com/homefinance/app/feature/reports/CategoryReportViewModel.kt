package com.homefinance.app.feature.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.homefinance.app.HomeFinanceApp
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
 * Backs one category's report.
 *
 * ### Three reads, and everything else folded from them
 *
 * The category itself (so the heading can name it and draw its mark even in a period it has no entries
 * in), the period's totals for its direction (so the share under the heading has a denominator), and
 * the category's entries for the period. The total, the count, the average, the trend and the largest
 * entries are all readings of those entries, because the screen *lists* them anyway: a figure that is
 * derived from the rows on screen cannot disagree with them.
 *
 * ### Why this is a separate view model rather than a mode of the tab's
 *
 * The tab answers "what did the household do", and this answers "what did this one category do" over a
 * window that the household chooses *on this screen* once it is open. Sharing one view model would tie
 * two independently navigable screens to one set of controls, so stepping a month here would silently
 * move the tab underneath - which is exactly the kind of hidden coupling a ledger should not have.
 *
 * @param initialPeriod the window carried in from the row that was tapped, so the figure the household
 *   pressed is the figure they land on.
 * @param today the day the screen believes it is, pinning the one rule about the future.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoryReportViewModel(
    private val categoryId: Long,
    initialPeriod: ReportPeriod,
    private val reports: ReportRepository,
    private val today: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val periodChoice = MutableStateFlow(initialPeriod)

    private val retryRequests = MutableStateFlow(0)

    val uiState: StateFlow<CategoryReportUiState> =
        combine(periodChoice, retryRequests) { period, _ -> period }
            .flatMapLatest { period -> readReport(period) }
            .map { read -> read.toUiState() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
                initialValue = loadingState(initialPeriod),
            )

    /** Steps the window one month earlier. */
    fun onPreviousPeriod() {
        periodChoice.update { period -> period.previous() }
    }

    /** Steps the window one month later, unless that would report on a month that has not happened. */
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

    private fun readReport(period: ReportPeriod): Flow<CategoryRead> {
        val reads = combine(
            reports.observeCategory(categoryId),
            reports.observeTotals(period.from, period.to),
            reports.observeForCategoryInDateRange(categoryId, period.from, period.to),
        ) { category, totals, entries -> CategoryReads(period, category, totals, entries) }

        return reads
            .map<CategoryReads, CategoryRead> { CategoryRead.Rows(it) }
            .onStart { emit(CategoryRead.Loading(period)) }
            .catch { failure -> emit(CategoryRead.Failed(period, failure)) }
    }

    private fun CategoryRead.toUiState(): CategoryReportUiState = when (this) {
        is CategoryRead.Loading -> loadingState(period)
        is CategoryRead.Failed -> CategoryReportUiState.Failed(
            period = period,
            canGoForward = period.canGoForward(YearMonth.from(today)),
            isCurrentMonth = period.isCurrentMonth(YearMonth.from(today)),
            cause = cause,
        )
        is CategoryRead.Rows -> reads.toContent(YearMonth.from(today))
    }

    private fun loadingState(period: ReportPeriod): CategoryReportUiState.Loading =
        CategoryReportUiState.Loading(
            period = period,
            canGoForward = period.canGoForward(YearMonth.from(today)),
            isCurrentMonth = period.isCurrentMonth(YearMonth.from(today)),
        )

    /** One read of the category's report, before the period has been folded into what a screen draws. */
    private sealed interface CategoryRead {

        data class Loading(val period: ReportPeriod) : CategoryRead

        data class Failed(val period: ReportPeriod, val cause: Throwable) : CategoryRead

        data class Rows(val reads: CategoryReads) : CategoryRead
    }

    companion object {

        private const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L

        /**
         * Builds the view model against the application's own repository.
         *
         * The same one wiring seam as the rest of the app: the application class owns the single
         * database, and there is deliberately no dependency-injection framework.
         */
        fun factory(categoryId: Long, period: ReportPeriod): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY],
                ) { "CategoryReportViewModel needs the Application to reach the repository" }

                CategoryReportViewModel(
                    categoryId = categoryId,
                    initialPeriod = period,
                    reports = (application as HomeFinanceApp).reportRepository,
                )
            }
        }

        /**
         * Builds the view model over [repository] with [today] pinned.
         *
         * The seam a JVM test uses to drive the drill-down's state machine without a device.
         */
        fun factory(
            categoryId: Long,
            period: ReportPeriod,
            repository: ReportRepository,
            today: LocalDate,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CategoryReportViewModel(
                    categoryId = categoryId,
                    initialPeriod = period,
                    reports = repository,
                    today = today,
                )
            }
        }
    }
}
