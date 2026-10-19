package com.homefinance.app.feature.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homefinance.app.R
import com.homefinance.app.core.ui.icon.getCategoryIconAndColors
import com.homefinance.app.core.ui.component.HomeFinanceTopBar
import com.homefinance.app.core.ui.component.StatusPane
import com.homefinance.app.core.util.CurrencyFormatter
import com.homefinance.app.core.util.DateFormatter
import com.homefinance.app.data.model.TransactionType
import com.homefinance.app.data.model.categoryIconKeyFor
import com.homefinance.app.feature.addtransaction.CategoryMark
import com.homefinance.app.feature.transactions.TransactionListItem
import java.time.YearMonth
import kotlin.math.abs

/**
 * The most bucket labels a chart puts on its x-axis before thinning them.
 *
 * A twelve-month report has twelve buckets and a phone has room for about six readable month names.
 * Thinning rather than rotating or dropping bars keeps every bar drawn and only changes how often it
 * is named, which is what makes the axis legible instead of crowded.
 */
private const val MAX_AXIS_LABELS = 6

/**
 * The Reports tab: how much came in, how much went out, and where it went.
 *
 * ### Where the data comes from
 *
 * The screen renders [ReportsViewModel.uiState] and nothing else. It never touches a DAO or a
 * repository, never launches a coroutine and never queries anything while composing - the aggregate
 * reads happen in the view model, and Room pushes a new report when a transaction is added, edited or
 * deleted anywhere in the app. There is no refresh button, because there is nothing to refresh.
 *
 * ### Insets and the shell
 *
 * The bottom bar and the system-bar padding belong to the application shell's single `Scaffold`, and
 * [HomeFinanceTopBar] zeroes its own insets for the same reason. Adding padding for them here as well
 * would double them, as it would on the Home or Transactions screen.
 *
 * There is deliberately no back arrow: this is a top-level tab the bottom bar already owns.
 *
 * @param onOpenCategory opens the report for one category. The period travels with the tap, so the
 *   drill-down opens on the same window the household was already reading rather than on a default.
 * @param onOpenTransaction opens one entry's details, from the largest-transactions section.
 */
@Composable
fun ReportsScreen(
    onOpenCategory: (ReportPeriod, Long) -> Unit,
    onOpenTransaction: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReportsViewModel = viewModel(factory = ReportsViewModel.factory()),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ReportsContent(
        uiState = uiState,
        onPreviousPeriod = viewModel::onPreviousPeriod,
        onNextPeriod = viewModel::onNextPeriod,
        onRangeChange = viewModel::onRangeChange,
        onRetry = viewModel::retry,
        onOpenCategory = onOpenCategory,
        onOpenTransaction = onOpenTransaction,
        modifier = modifier,
    )
}

/**
 * The screen's body, taking its state as a parameter.
 *
 * Split out from [ReportsScreen] so every state - including a failure a real database will not produce
 * on demand, and an empty period - can be rendered and asserted directly.
 */
@Composable
internal fun ReportsContent(
    uiState: ReportsUiState,
    onPreviousPeriod: () -> Unit,
    onNextPeriod: () -> Unit,
    onRangeChange: (ReportRange) -> Unit,
    onRetry: () -> Unit,
    onOpenCategory: (ReportPeriod, Long) -> Unit,
    onOpenTransaction: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        HomeFinanceTopBar(
            title = stringResource(R.string.title_reports),
            actions = { ReportRangeMenu(range = uiState.period.range, onRangeChange = onRangeChange) },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Outside the state, because the household must be able to step away from a month whose
            // figures failed to load - or that is simply taking a moment - without leaving the screen.
            PeriodNavigation(
                period = uiState.period,
                canGoForward = uiState.canGoForward,
                isCurrentMonth = uiState.isCurrentMonth,
                onPreviousPeriod = onPreviousPeriod,
                onNextPeriod = onNextPeriod,
            )

            when (uiState) {
                is ReportsUiState.Loading -> StatusPane(
                    icon = Icons.Outlined.DateRange,
                    title = stringResource(R.string.reports_loading),
                    modifier = Modifier.fillMaxWidth(),
                )

                is ReportsUiState.Failed -> StatusPane(
                    icon = Icons.Outlined.Warning,
                    title = stringResource(R.string.reports_error_title),
                    message = stringResource(R.string.reports_error_message),
                    accentColor = MaterialTheme.colorScheme.errorContainer,
                    accentContentColor = MaterialTheme.colorScheme.onErrorContainer,
                    action = {
                        TextButton(onClick = onRetry) {
                            Icon(imageVector = Icons.Filled.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = stringResource(R.string.reports_error_retry))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                is ReportsUiState.Content -> ReportSections(
                    content = uiState,
                    onOpenCategory = { categoryId -> onOpenCategory(uiState.period, categoryId) },
                    onOpenTransaction = onOpenTransaction,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * The report itself, in the order it is read: what the period came to, then its shape over time, then
 * what it was made of, then which months led here, then the entries worth naming.
 */
@Composable
private fun ReportSections(
    content: ReportsUiState.Content,
    onOpenCategory: (Long) -> Unit,
    onOpenTransaction: (Long) -> Unit,
) {
    if (content.isEmpty) {
        StatusPane(
            icon = Icons.Outlined.DateRange,
            title = stringResource(R.string.reports_empty_title),
            message = stringResource(R.string.reports_empty_message),
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }

    SummaryCard(summary = content.summary, period = content.period)

    IncomeExpenseCard(buckets = content.buckets)

    BreakdownCard(
        title = stringResource(R.string.reports_expense_breakdown_title),
        totalLabel = stringResource(R.string.reports_breakdown_total_expense),
        type = TransactionType.EXPENSE,
        shares = content.expenses,
        totalPaise = content.summary.expensePaise,
        emptyMessage = stringResource(R.string.reports_breakdown_no_expenses),
        onOpenCategory = onOpenCategory,
    )

    BreakdownCard(
        title = stringResource(R.string.reports_income_breakdown_title),
        totalLabel = stringResource(R.string.reports_breakdown_total_income),
        type = TransactionType.INCOME,
        shares = content.incomes,
        totalPaise = content.summary.incomePaise,
        emptyMessage = stringResource(R.string.reports_breakdown_no_income),
        onOpenCategory = onOpenCategory,
    )

    MonthlyTrendCard(points = content.trend)

    LargestTransactionsCard(items = content.topAmounts, onOpenTransaction = onOpenTransaction)
}

/**
 * The window the report covers, and the two arrows that move it.
 *
 * The forward arrow is *absent* rather than disabled when the window already ends at this month:
 * there is no later data to show, and a greyed control that does nothing invites a household to keep
 * pressing it. The sentence underneath says which month this is, so a partial month is never read as
 * a finished one.
 */
@Composable
internal fun PeriodNavigation(
    period: ReportPeriod,
    canGoForward: Boolean,
    isCurrentMonth: Boolean,
    onPreviousPeriod: () -> Unit,
    onNextPeriod: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onPreviousPeriod) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.reports_period_previous),
                )
            }

            Text(
                text = reportPeriodLabel(period),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (canGoForward) {
                IconButton(onClick = onNextPeriod) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.reports_period_next),
                    )
                }
            } else {
                // The same width as the button it replaces, so the month name does not shift sideways
                // the moment the household reaches the current month.
                Spacer(modifier = Modifier.size(ActionSize))
            }
        }

        if (isCurrentMonth) {
            Text(
                text = stringResource(R.string.reports_current_month_note),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The length of the report, chosen from a menu.
 *
 * A menu rather than a row of chips because the top bar has one line to work with, and because the
 * choice is a *setting* of the screen rather than a filter over a list.
 */
@Composable
private fun ReportRangeMenu(
    range: ReportRange,
    onRangeChange: (ReportRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        TextButton(onClick = { expanded = true }) {
            Text(text = stringResource(range.labelRes))
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = stringResource(R.string.reports_range_label),
            )
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ReportRange.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text = stringResource(option.labelRes)) },
                    onClick = {
                        expanded = false
                        onRangeChange(option)
                    },
                )
            }
        }
    }
}

/**
 * What the period came to, one line per figure.
 *
 * ### Why the three figures are stacked rather than set side by side
 *
 * The design draws three cards across the width, and at this app's precision that cannot work: money
 * is printed to the paise by [CurrencyFormatter], so a net figure reads `₹1,24,500.00`, and a third of
 * a phone's width is about a hundred density-independent pixels. Three across would either ellipsise a
 * household's own totals - which is unacceptable, since the number *is* the information - or shrink
 * the type below the size the rest of the app uses. Stacked, each figure is full width, survives the
 * largest font scale the system offers, and still sits inside one card with the hierarchy the design
 * intended.
 *
 * The colour carries the meaning of the figure, and the change caption compares it with the period of
 * the same length immediately before it.
 */
@Composable
private fun SummaryCard(
    summary: ReportSummary,
    period: ReportPeriod,
    modifier: Modifier = Modifier,
) {
    val comparison = comparisonCaption(period)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SummaryRow(
                label = stringResource(R.string.reports_summary_income),
                amountPaise = summary.incomePaise,
                count = summary.incomeCount,
                amountColor = MaterialTheme.colorScheme.primary,
                changePercent = summary.incomeChangePercent,
                comparisonCaption = comparison,
                // More income than last time is the good direction.
                changeIsGood = { percent -> percent >= 0 },
            )

            SummaryRow(
                label = stringResource(R.string.reports_summary_expense),
                amountPaise = summary.expensePaise,
                count = summary.expenseCount,
                amountColor = MaterialTheme.colorScheme.error,
                changePercent = summary.expenseChangePercent,
                comparisonCaption = comparison,
                // More spending than last time is not.
                changeIsGood = { percent -> percent <= 0 },
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SummaryRow(
                label = stringResource(R.string.reports_summary_net),
                amountPaise = summary.netPaise,
                count = summary.transactionCount,
                amountColor = if (summary.netPaise < 0L) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                changePercent = summary.netChangePercent,
                comparisonCaption = comparison,
                changeIsGood = { percent -> percent >= 0 },
            )

            val savingsRate = summary.savingsRatePercent
            if (savingsRate != null) {
                Text(
                    text = if (savingsRate < 0) {
                        stringResource(R.string.reports_savings_rate_negative, abs(savingsRate))
                    } else {
                        stringResource(R.string.reports_savings_rate, savingsRate)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One labelled figure: its name and how many entries made it, then its amount and its change. */
@Composable
private fun SummaryRow(
    label: String,
    amountPaise: Long,
    count: Long,
    amountColor: Color,
    changePercent: Int?,
    comparisonCaption: String,
    changeIsGood: (Int) -> Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = pluralStringResource(
                    R.plurals.reports_summary_count,
                    count.toInt(),
                    count.toInt(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = CurrencyFormatter.formatInr(amountPaise),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = amountColor,
                maxLines = 1,
                softWrap = false,
            )

            if (changePercent != null) {
                Text(
                    text = changeText(changePercent, comparisonCaption),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (changeIsGood(changePercent)) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/**
 * How a figure moved against the period before it, in words.
 *
 * A fall is printed as the *magnitude* with a down arrow (`↓ 8%`), so the arrow carries the direction
 * and the number is never a negative percentage of a negative amount - which reads like a double
 * negative and gets subtracted by mistake.
 */
@Composable
private fun changeText(percent: Int, comparisonCaption: String): String = when {
    percent > 0 -> stringResource(R.string.reports_comparison_up, percent, comparisonCaption)
    percent < 0 -> stringResource(R.string.reports_comparison_down, abs(percent), comparisonCaption)
    else -> stringResource(R.string.reports_comparison_flat, comparisonCaption)
}

/** The period each change is measured against, in words. */
@Composable
private fun comparisonCaption(period: ReportPeriod): String = when (period.range) {
    ReportRange.MONTH -> stringResource(
        R.string.reports_comparison_vs_month,
        DateFormatter.formatShortMonth(period.previousPeriod.endMonth.atDay(1)),
    )

    else -> pluralStringResource(
        R.plurals.reports_comparison_vs_months,
        period.range.months,
        period.range.months,
    )
}

/** The window itself, in words: `Nov 2026`, or `Sep – Nov 2026` for a longer one. */
@Composable
internal fun reportPeriodLabel(period: ReportPeriod): String = when {
    period.range == ReportRange.MONTH -> DateFormatter.formatMonth(period.endMonth)

    period.from.year == period.to.year -> stringResource(
        R.string.reports_period_range_same_year,
        DateFormatter.formatShortMonth(period.from),
        DateFormatter.formatShortMonth(period.endMonth.atDay(1)),
        period.to.year,
    )

    else -> stringResource(
        R.string.reports_period_range_cross_year,
        DateFormatter.formatShortMonth(period.from),
        period.from.year,
        DateFormatter.formatShortMonth(period.endMonth.atDay(1)),
        period.to.year,
    )
}

/** Income against expenses over the period, bucketed by week or by month. */
@Composable
private fun IncomeExpenseCard(
    buckets: List<ReportBucket>,
    modifier: Modifier = Modifier,
) {
    ReportCard(
        title = stringResource(R.string.reports_chart_income_vs_expense),
        modifier = modifier,
    ) {
        if (buckets.all { it.isEmpty }) {
            EmptyNote(text = stringResource(R.string.reports_chart_no_activity))
        } else {
            GroupedBarChart(
                columns = buckets.mapToChartColumns(),
                series = listOf(
                    ChartSeries(
                        label = stringResource(R.string.reports_series_income),
                        color = MaterialTheme.colorScheme.primary,
                    ),
                    ChartSeries(
                        label = stringResource(R.string.reports_series_expense),
                        color = MaterialTheme.colorScheme.error,
                    ),
                ),
            )
        }
    }
}

/** The last few months, so the period being read has a shape behind it. */
@Composable
private fun MonthlyTrendCard(
    points: List<TrendPoint>,
    modifier: Modifier = Modifier,
) {
    ReportCard(
        title = stringResource(R.string.reports_monthly_trend_title, ReportPeriod.TREND_MONTHS),
        modifier = modifier,
    ) {
        NetTrendChart(points = points)
    }
}

/**
 * One direction's breakdown: a ring, and a legend that names every category and what it came to.
 *
 * The ring is decoration over a list of numbers: every figure the household needs - the name, the
 * amount and the share - is in the legend as text, so the section is readable without reading colour,
 * and every row is the way into that category's own report.
 *
 * The whole breakdown is listed rather than an "other" bucket, because a category's total should
 * never be hidden behind a summary of itself in an app whose categories the household chose.
 */
@Composable
private fun BreakdownCard(
    title: String,
    totalLabel: String,
    type: TransactionType,
    shares: List<CategoryShare>,
    totalPaise: Long,
    emptyMessage: String,
    onOpenCategory: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    ReportCard(title = title, modifier = modifier) {
        if (shares.isEmpty()) {
            EmptyNote(text = emptyMessage)
            return@ReportCard
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CategoryDonutChart(
                slices = shares.map { share ->
                    DonutSlice(
                        label = share.categoryName,
                        value = share.totalPaise,
                        color = shareColor(share = share, type = type),
                    )
                },
                centreValue = CurrencyFormatter.formatInr(totalPaise),
                centreLabel = totalLabel,
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                shares.forEach { share ->
                    BreakdownLegendRow(share = share, type = type, onOpenCategory = onOpenCategory)
                }
            }
        }
    }
}

/** One category of a breakdown: its mark's colour, its name, what it came to, and its share. */
@Composable
private fun BreakdownLegendRow(
    share: CategoryShare,
    type: TransactionType,
    onOpenCategory: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = { onOpenCategory(share.categoryId) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color = shareColor(share = share, type = type), shape = CircleShape),
        )

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = share.categoryName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = CurrencyFormatter.formatInr(share.totalPaise),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = "${share.percent}%",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** The period's biggest entries, so a month's total has a few names attached to it. */
@Composable
private fun LargestTransactionsCard(
    items: List<TransactionListItem>,
    onOpenTransaction: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    ReportCard(
        title = stringResource(R.string.reports_top_amounts_title),
        modifier = modifier,
    ) {
        items.forEach { item ->
            ReportTransactionRow(item = item, onClick = { onOpenTransaction(item.id) })
        }
    }
}

/**
 * One entry, as a report row.
 *
 * It reads differently from the ledger's row on purpose. In the ledger the category is the headline
 * because that is how a *day* is remembered; here the question is what the amount was for, so the
 * description leads where there is one and the category becomes the supporting line - and the row is
 * drawn from the same joined row and the same mark as the ledger, so the two can never disagree about
 * which category something was filed under.
 */
@Composable
internal fun ReportTransactionRow(
    item: TransactionListItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = item.description

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryMark(
            iconKey = item.categoryIconKey,
            categoryName = item.categoryName,
            type = item.type,
            size = 40.dp,
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = description ?: item.categoryName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (description == null) {
                    DateFormatter.format(item.transactionDate)
                } else {
                    stringResource(
                        R.string.category_report_entry,
                        item.categoryName,
                        DateFormatter.format(item.transactionDate),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = item.amountText(),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = amountColorFor(item.type),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** A titled card, so every section of the report is drawn the same shape. */
@Composable
internal fun ReportCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            content()
        }
    }
}

/** A section that has nothing to draw, said in words rather than drawn as an empty chart. */
@Composable
internal fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The colour a category is drawn in on this screen: the saturated tone of its own mark.
 *
 * Taken from the same function the ledger's marks use, so a category's ring wedge is the colour the
 * household already associates with it, and no separate report palette exists to drift away from it.
 */
@Composable
private fun shareColor(share: CategoryShare, type: TransactionType): Color {
    val key = categoryIconKeyFor(
        categoryName = share.categoryName,
        type = type,
        storedIconKey = share.categoryIconKey,
    )
    return getCategoryIconAndColors(key).second
}

/** The colour an amount is drawn in, matching the ledger. */
@Composable
internal fun amountColorFor(type: TransactionType): Color = when (type) {
    TransactionType.INCOME -> MaterialTheme.colorScheme.primary
    TransactionType.EXPENSE -> MaterialTheme.colorScheme.error
}

/**
 * Turns the period's buckets into the bars a chart draws.
 *
 * Two jobs, both of them about fitting a phone: the label is thinned when there are more buckets than
 * the axis can name (every second month of a year, rather than twelve month names in a row), and each
 * bucket carries its own figures as a sentence so the drawing is not the only copy of the data.
 */
@Composable
private fun List<ReportBucket>.mapToChartColumns(): List<ChartColumn> {
    val step = ((size + MAX_AXIS_LABELS - 1) / MAX_AXIS_LABELS).coerceAtLeast(1)

    return mapIndexed { index, bucket ->
        ChartColumn(
            label = if (index % step == 0) bucketShortLabel(bucket) else "",
            values = listOf(bucket.incomePaise, bucket.expensePaise),
            description = stringResource(
                R.string.reports_chart_column,
                bucketLongLabel(bucket),
                CurrencyFormatter.formatInr(bucket.incomePaise),
                CurrencyFormatter.formatInr(bucket.expensePaise),
            ),
        )
    }
}

/** `1 Nov` under a week, `Sep` under a month. */
internal fun bucketShortLabel(bucket: ReportBucket): String = when (bucket.kind) {
    ReportBucketKind.WEEK -> DateFormatter.formatDayMonth(bucket.startDate)
    ReportBucketKind.MONTH -> DateFormatter.formatShortMonth(bucket.startDate)
}

/** The same bucket named in full, for the sentence a screen reader reads. */
internal fun bucketLongLabel(bucket: ReportBucket): String = when (bucket.kind) {
    ReportBucketKind.WEEK -> DateFormatter.format(bucket.startDate)
    ReportBucketKind.MONTH -> DateFormatter.formatMonth(YearMonth.from(bucket.startDate))
}

/** The size of a control that shares a row with the month arrows. */
private val ActionSize = 48.dp
