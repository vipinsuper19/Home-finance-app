package com.homefinance.app.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homefinance.app.R
import com.homefinance.app.core.common.PlaceholderData
import com.homefinance.app.core.ui.component.HomeFinanceTopBar
import com.homefinance.app.core.ui.component.SummaryAmountCard
import com.homefinance.app.core.ui.theme.HomeFinanceTheme
import com.homefinance.app.core.util.CurrencyFormatter
import com.homefinance.app.data.model.RecurrenceFrequency
import com.homefinance.app.data.model.TransactionType
import com.homefinance.app.feature.recurring.OccurrenceDisplayState
import java.time.LocalDate

/**
 * Home: the monthly overview, with what is due from the household's recurring schedules underneath it.
 *
 * The summary figures are still clearly marked as sample data because they are not read from the
 * ledger yet, while the recurring section below them is the household's own: what is late, what is due
 * today, and what is coming, all read from the occurrences the engine has generated.
 *
 * ### The dashboard writes nothing
 *
 * Nothing here records a payment, and no date on this screen is counted as money that has moved. The
 * one write Home performs is the same engine run the recurring tab performs when it is opened - it
 * creates expectations, never transactions, and moves no total. See [HomeViewModel] for why it belongs
 * here too.
 *
 * @param onOpenSettings the toolbar's settings action.
 * @param onOpenSchedule one recurring date was tapped; the schedule it belongs to is opened, which is
 *   where that date can actually be settled.
 * @param onOpenRecurring opens the recurring tab: the section's heading, each group's heading and the
 *   "view all" action all lead there.
 * @param onAddSchedule opens the form for a first schedule, offered only when the household has none.
 */
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenSchedule: (Long) -> Unit,
    onOpenRecurring: () -> Unit,
    onAddSchedule: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory()),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    HomeContent(
        uiState = uiState,
        onOpenSettings = onOpenSettings,
        onOpenSchedule = onOpenSchedule,
        onOpenRecurring = onOpenRecurring,
        onAddSchedule = onAddSchedule,
        onRetry = viewModel::retry,
        modifier = modifier,
    )
}

/**
 * The screen's body, taking its state as a parameter.
 *
 * Split out from [HomeScreen] so each state - the recurring section loading, populated, empty, or
 * failed, over the summary cards - can be rendered and asserted directly.
 */
@Composable
internal fun HomeContent(
    uiState: HomeUiState,
    onOpenSettings: () -> Unit,
    onOpenSchedule: (Long) -> Unit,
    onOpenRecurring: () -> Unit,
    onAddSchedule: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        HomeFinanceTopBar(
            title = stringResource(R.string.app_name),
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.action_open_settings),
                    )
                }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BalanceCard(
                balanceInPaise = uiState.balanceInPaise,
                showSampleBadge = uiState.isPlaceholderData,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryAmountCard(
                    label = stringResource(R.string.home_income_label),
                    amountInPaise = uiState.incomeInPaise,
                    amountColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                SummaryAmountCard(
                    label = stringResource(R.string.home_expense_label),
                    amountInPaise = uiState.expenseInPaise,
                    amountColor = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
            }
            RecurringPaymentsCard(
                state = uiState.recurring,
                onOpenRecurring = onOpenRecurring,
                onOpenSchedule = onOpenSchedule,
                onAddSchedule = onAddSchedule,
                onRetry = onRetry,
            )
        }
    }
}

@Composable
private fun BalanceCard(
    balanceInPaise: Long,
    showSampleBadge: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Column(
            // 16 dp interior padding matches SummaryAmountCard and the screen gutters.
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.home_balance_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (showSampleBadge) {
                    SampleDataBadge()
                }
            }
            Text(
                text = CurrencyFormatter.formatInr(balanceInPaise),
                style = MaterialTheme.typography.headlineMedium,
            )
        }
    }
}

@Composable
private fun SampleDataBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        // A white pill with dark text: the previous translucent-white badge faded the text to
        // roughly 4.4:1 contrast against the emerald card, below the 4.5:1 WCAG AA floor.
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = stringResource(R.string.home_sample_badge),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * A preview of the two states worth looking at: a section with something late in it, and the summary
 * cards above it exactly as production draws them.
 *
 * The rows are invented here and nowhere else - a preview is tooling, and the screen itself never
 * shows a household a bill they do not have.
 */
@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun HomeContentPreview() {
    HomeFinanceTheme {
        HomeContent(
            uiState = HomeUiState(
                balanceInPaise = PlaceholderData.balanceInPaise,
                incomeInPaise = PlaceholderData.incomeInPaise,
                expenseInPaise = PlaceholderData.expenseInPaise,
                recurring = HomeRecurringUiState.Ready(
                    overdue = listOf(
                        previewItem(
                            occurrenceId = 1L,
                            scheduleId = 10L,
                            name = "Electricity bill",
                            categoryName = "Electricity",
                            categoryType = TransactionType.EXPENSE,
                            dueDate = LocalDate.of(2026, 10, 2),
                            amountPaise = 250_000L,
                            state = OccurrenceDisplayState.OVERDUE,
                            daysFromToday = -3L,
                        ),
                    ),
                    dueToday = listOf(
                        previewItem(
                            occurrenceId = 2L,
                            scheduleId = 11L,
                            name = "Shop rent",
                            categoryName = "Rent",
                            categoryType = TransactionType.EXPENSE,
                            dueDate = LocalDate.of(2026, 10, 5),
                            amountPaise = 1_500_000L,
                            state = OccurrenceDisplayState.DUE,
                            daysFromToday = 0L,
                        ),
                    ),
                    upcoming = listOf(
                        previewItem(
                            occurrenceId = 3L,
                            scheduleId = 12L,
                            name = "Salary",
                            categoryName = "Salary",
                            categoryType = TransactionType.INCOME,
                            dueDate = LocalDate.of(2026, 11, 1),
                            amountPaise = 4_500_000L,
                            state = OccurrenceDisplayState.UPCOMING,
                            daysFromToday = 27L,
                        ),
                    ),
                ),
            ),
            onOpenSettings = {},
            onOpenSchedule = {},
            onOpenRecurring = {},
            onAddSchedule = {},
            onRetry = {},
        )
    }
}

/** One invented row for [HomeContentPreview] only. */
private fun previewItem(
    occurrenceId: Long,
    scheduleId: Long,
    name: String,
    categoryName: String,
    categoryType: TransactionType,
    dueDate: LocalDate,
    amountPaise: Long,
    state: OccurrenceDisplayState,
    daysFromToday: Long,
): HomeRecurringItem = HomeRecurringItem(
    occurrenceId = occurrenceId,
    scheduleId = scheduleId,
    scheduleName = name,
    categoryName = categoryName,
    categoryType = categoryType,
    dueDate = dueDate,
    amountPaise = amountPaise,
    state = state,
    daysFromToday = daysFromToday,
    frequency = RecurrenceFrequency.MONTHLY,
    intervalCount = 1,
)
