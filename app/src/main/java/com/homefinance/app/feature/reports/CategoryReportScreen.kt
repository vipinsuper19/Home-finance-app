package com.homefinance.app.feature.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homefinance.app.R
import com.homefinance.app.core.ui.component.HomeFinanceTopBar
import com.homefinance.app.core.ui.component.StatusPane
import com.homefinance.app.core.util.CurrencyFormatter
import com.homefinance.app.core.util.DateFormatter
import com.homefinance.app.data.model.TransactionType
import com.homefinance.app.feature.addtransaction.CategoryMark
import com.homefinance.app.feature.transactions.TransactionListItem

/**
 * One category's own report: what it came to, how it moved across the period, and what it was made of.
 *
 * This is where a row of the Reports tab's breakdown leads. The screen is opened with the period the
 * household was already reading, so the figure they tapped is the figure they land on; from here the
 * window can be changed again without moving the tab underneath.
 *
 * ### Where the subcategories went
 *
 * The design for this screen draws a "Top Subcategories" grid under the trend. This app has no
 * subcategory: a transaction carries one category and an optional free-text description, and inventing
 * a second taxonomy to draw in that slot would mean reporting on buckets the household never created.
 * The slot is instead filled with the largest entries *in this category* - the same ranked-list idea,
 * drawn from money that is really there - and the section is named for what it is.
 *
 * ### Where the first screen's summary starts the same way
 *
 * The stats under the heading are rows rather than three tiles across, for the reason the Reports tab's
 * summary gives: an exact amount printed to the paise does not fit in a third of a phone's width, and
 * ellipsising a household's own total is not an option.
 *
 * @param categoryId the category this report is about.
 * @param period the window it opens on, carried from the row that was tapped.
 */
@Composable
fun CategoryReportScreen(
    categoryId: Long,
    period: ReportPeriod,
    onBack: () -> Unit,
    onOpenTransaction: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CategoryReportViewModel = viewModel(
        factory = CategoryReportViewModel.factory(categoryId, period),
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    CategoryReportContent(
        uiState = uiState,
        onBack = onBack,
        onPreviousPeriod = viewModel::onPreviousPeriod,
        onNextPeriod = viewModel::onNextPeriod,
        onRangeChange = viewModel::onRangeChange,
        onRetry = viewModel::retry,
        onOpenTransaction = onOpenTransaction,
        modifier = modifier,
    )
}

/**
 * The screen's body, taking its state as a parameter.
 *
 * Split out so every state - a missing category, an empty period, a failed read - can be rendered and
 * asserted directly.
 */
@Composable
internal fun CategoryReportContent(
    uiState: CategoryReportUiState,
    onBack: () -> Unit,
    onPreviousPeriod: () -> Unit,
    onNextPeriod: () -> Unit,
    onRangeChange: (ReportRange) -> Unit,
    onRetry: () -> Unit,
    onOpenTransaction: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        HomeFinanceTopBar(
            title = stringResource(R.string.title_category_details),
            onNavigationClick = onBack,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PeriodNavigation(
                period = uiState.period,
                canGoForward = uiState.canGoForward,
                isCurrentMonth = uiState.isCurrentMonth,
                onPreviousPeriod = onPreviousPeriod,
                onNextPeriod = onNextPeriod,
            )

            RangeChips(range = uiState.period.range, onRangeChange = onRangeChange)

            when (uiState) {
                is CategoryReportUiState.Loading -> StatusPane(
                    icon = Icons.Outlined.DateRange,
                    title = stringResource(R.string.reports_loading),
                    modifier = Modifier.fillMaxWidth(),
                )

                is CategoryReportUiState.Missing -> StatusPane(
                    icon = Icons.Outlined.Warning,
                    title = stringResource(R.string.category_report_missing_title),
                    message = stringResource(R.string.category_report_missing_message),
                    accentColor = MaterialTheme.colorScheme.errorContainer,
                    accentContentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth(),
                )

                is CategoryReportUiState.Failed -> StatusPane(
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

                is CategoryReportUiState.Content -> CategoryReportSections(
                    content = uiState,
                    onOpenTransaction = onOpenTransaction,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** The category's heading, then its figures, then its shape over time, then the entries themselves. */
@Composable
private fun CategoryReportSections(
    content: CategoryReportUiState.Content,
    onOpenTransaction: (Long) -> Unit,
) {
    CategoryHeaderCard(content = content)
    CategoryStatsCard(content = content)

    if (content.isEmpty) {
        EmptyNote(
            text = stringResource(
                R.string.category_report_empty_message,
                DateFormatter.format(content.period.from),
                DateFormatter.format(content.period.to),
            ),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        return
    }

    CategoryTrendCard(content = content)
    CategoryLargestCard(content = content, onOpenTransaction = onOpenTransaction)
    CategoryEntriesCard(content = content, onOpenTransaction = onOpenTransaction)
}

/**
 * Who the report is about, and what it came to.
 *
 * The amount is coloured by the category's direction using the same rule as every other amount in the
 * app, so the figure is never ambiguous about whether it came in or went out.
 */
@Composable
private fun CategoryHeaderCard(
    content: CategoryReportUiState.Content,
    modifier: Modifier = Modifier,
) {
    val isExpense = content.type == TransactionType.EXPENSE

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CategoryMark(
                iconKey = content.categoryIconKey,
                categoryName = content.categoryName,
                type = content.type,
                size = 56.dp,
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = content.categoryName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = stringResource(
                        if (isExpense) {
                            R.string.category_report_type_expense
                        } else {
                            R.string.category_report_type_income
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = CurrencyFormatter.formatInr(content.totalPaise),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = amountColorFor(content.type),
                    maxLines = 1,
                    softWrap = false,
                )

                val share = content.sharePercent
                if (share != null) {
                    Text(
                        text = stringResource(
                            if (isExpense) {
                                R.string.category_report_share_expense
                            } else {
                                R.string.category_report_share_income
                            },
                            share,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** What the period came to for this category: the total, how many entries, and the average. */
@Composable
private fun CategoryStatsCard(
    content: CategoryReportUiState.Content,
    modifier: Modifier = Modifier,
) {
    val isExpense = content.type == TransactionType.EXPENSE

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatRow(
                label = stringResource(
                    if (isExpense) {
                        R.string.category_report_total_spent
                    } else {
                        R.string.category_report_total_received
                    },
                ),
                value = CurrencyFormatter.formatInr(content.totalPaise),
                valueColor = amountColorFor(content.type),
            )

            StatRow(
                label = stringResource(R.string.category_report_transactions),
                value = content.transactionCount.toString(),
            )

            // Absent rather than zero when nothing was recorded: there is no average of no entries.
            val average = content.averagePaise
            if (average != null) {
                StatRow(
                    label = stringResource(R.string.category_report_average),
                    value = CurrencyFormatter.formatInr(average),
                )
            }
        }
    }
}

/** One labelled figure of the stats card. */
@Composable
private fun StatRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** The category's own shape across the period, in the same buckets the Reports tab uses. */
@Composable
private fun CategoryTrendCard(
    content: CategoryReportUiState.Content,
    modifier: Modifier = Modifier,
) {
    val isExpense = content.type == TransactionType.EXPENSE
    val step = ((content.trend.size + MAX_TREND_LABELS - 1) / MAX_TREND_LABELS).coerceAtLeast(1)

    // A category belongs to exactly one direction, so its bucket total is whichever side of the ledger
    // it lives on - stated here rather than added up, so the chart cannot report a category's spending
    // as income if the two ever disagree.
    fun bucketTotal(bucket: ReportBucket): Long =
        if (isExpense) bucket.expensePaise else bucket.incomePaise

    val points = content.trend.mapIndexed { index, bucket ->
        ChartPoint(
            label = if (index % step == 0) bucketShortLabel(bucket) else "",
            value = bucketTotal(bucket),
        )
    }

    // Built outside the lambda below on purpose: `joinToString` is not an inline function, so a
    // `@Composable` call such as `stringResource` cannot happen inside it. The format is read once
    // here and applied as ordinary text, which keeps the wording in the resource file where it belongs.
    val pointFormat = stringResource(R.string.category_report_trend_point)
    val description = content.trend.joinToString(separator = "; ") { bucket ->
        pointFormat.format(bucketLongLabel(bucket), CurrencyFormatter.formatInr(bucketTotal(bucket)))
    }

    ReportCard(
        title = stringResource(
            if (isExpense) {
                R.string.category_report_trend_expense
            } else {
                R.string.category_report_trend_income
            },
        ),
        modifier = modifier,
    ) {
        TimeLineChart(points = points, description = description)
    }
}

/**
 * The biggest entries in the category, each with a bar showing its share of the category's total.
 *
 * This is what the design's ranked subcategory grid becomes. The bar is a *proportion indicator* over
 * figures that are printed in full beside it, so it is read as an aid and not as the number itself -
 * and the percentage is a whole number rounded half-up in integer arithmetic like every other share in
 * the app.
 */
@Composable
private fun CategoryLargestCard(
    content: CategoryReportUiState.Content,
    onOpenTransaction: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    ReportCard(
        title = stringResource(R.string.category_report_top_amounts),
        modifier = modifier,
    ) {
        content.largest.forEach { item ->
            LargestAmountRow(
                item = item,
                sharePercent = if (content.totalPaise <= 0L) {
                    0
                } else {
                    percentOf(item.amountPaise, content.totalPaise)
                },
                shareFraction = if (content.totalPaise <= 0L) {
                    0f
                } else {
                    // A drawing measurement, not money: the ratio is geometry, and the exact figures
                    // are printed beside it by CurrencyFormatter.
                    item.amountPaise.toFloat() / content.totalPaise.toFloat()
                },
                onClick = { onOpenTransaction(item.id) },
            )
        }
    }
}

/** One of the category's biggest entries, with its share of the category drawn as a bar. */
@Composable
private fun LargestAmountRow(
    item: TransactionListItem,
    sharePercent: Int,
    shareFraction: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = amountColorFor(item.type)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.description ?: item.categoryName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = DateFormatter.format(item.transactionDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = item.amountText(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    text = stringResource(R.string.category_report_share_of_category, sharePercent),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // A track plus a fill rather than a progress indicator, so a share of zero draws nothing at all
        // instead of a control that looks broken.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(shareFraction.coerceIn(0f, 1f))
                    .height(4.dp)
                    .background(color),
            )
        }
    }
}

/** Every entry in the category during the period, newest day first, each day under its own total. */
@Composable
private fun CategoryEntriesCard(
    content: CategoryReportUiState.Content,
    onOpenTransaction: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    ReportCard(
        title = stringResource(R.string.category_report_transactions_title, content.transactionCount),
        modifier = modifier,
    ) {
        content.groups.forEach { group ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = DateFormatter.format(group.date),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = CurrencyFormatter.formatInr(group.totalPaise),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }

            group.items.forEach { item ->
                ReportTransactionRow(item = item, onClick = { onOpenTransaction(item.id) })
            }
        }
    }
}

/**
 * The lengths the report can be read at, as chips.
 *
 * Chips here and a menu on the Reports tab, which is what each screen's design draws. They set the same
 * value, so a household that learns one recognises the other.
 */
@Composable
private fun RangeChips(
    range: ReportRange,
    onRangeChange: (ReportRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ReportRange.entries.forEach { option ->
            FilterChip(
                selected = option == range,
                onClick = { onRangeChange(option) },
                label = { Text(text = stringResource(option.labelRes)) },
            )
        }
    }
}

/** The most trend bucket labels drawn before they are thinned out. */
private const val MAX_TREND_LABELS = 6
