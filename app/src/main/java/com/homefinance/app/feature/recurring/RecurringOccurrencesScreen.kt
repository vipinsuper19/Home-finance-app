package com.homefinance.app.feature.recurring

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import com.homefinance.app.core.ui.component.HomeFinanceTopBar
import com.homefinance.app.core.ui.component.StatusPane
import com.homefinance.app.core.ui.theme.HomeFinanceTheme
import com.homefinance.app.data.model.TransactionType
import java.time.LocalDate

/**
 * Every occurrence of one schedule, newest first: what fell due, what it was expected to be worth, and what
 * became of each one.
 *
 * ### The rows here *are* actionable
 *
 * Unlike the short history on the details screen, each still-pending row offers the same two actions, and
 * each is settled through the same dialog and the same repository call. That is what makes this the place
 * to catch up: a household who did not open the app for three weeks finds the three dates that passed
 * sitting here, and can pay or skip each one deliberately.
 *
 * Nothing on this screen marks anything paid because a date passed. An overdue row is a row that *can* be
 * paid, not a row that has been.
 *
 * @param onBack the screen was dismissed.
 */
@Composable
fun RecurringOccurrencesScreen(
    scheduleId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecurringOccurrencesViewModel = viewModel(
        factory = RecurringOccurrencesViewModel.factory(scheduleId),
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pay by viewModel.pay.collectAsStateWithLifecycle()
    val skip by viewModel.skip.collectAsStateWithLifecycle()

    RecurringOccurrencesContent(
        uiState = uiState,
        pay = pay,
        skip = skip,
        onBack = onBack,
        onRetry = viewModel::retry,
        onRequestPay = viewModel::requestPay,
        onRequestSkip = viewModel::requestSkip,
        onPayAmountChange = viewModel::onPayAmountChange,
        onConfirmPay = viewModel::confirmPay,
        onDismissPay = viewModel::dismissPay,
        onConfirmSkip = viewModel::confirmSkip,
        onDismissSkip = viewModel::dismissSkip,
        modifier = modifier,
    )
}

/** The screen's body, taking its state and every action as parameters. */
@Composable
internal fun RecurringOccurrencesContent(
    uiState: RecurringOccurrencesUiState,
    pay: PayOccurrenceState?,
    skip: SkipOccurrenceState?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRequestPay: (RecurringOccurrenceItem) -> Unit,
    onRequestSkip: (RecurringOccurrenceItem) -> Unit,
    onPayAmountChange: (String) -> Unit,
    onConfirmPay: () -> Unit,
    onDismissPay: () -> Unit,
    onConfirmSkip: () -> Unit,
    onDismissSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        HomeFinanceTopBar(
            title = stringResource(R.string.recurring_occurrences_title),
            onNavigationClick = onBack,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when (uiState) {
                RecurringOccurrencesUiState.Loading -> RecurringLoadingSpinner(
                    modifier = Modifier.align(Alignment.Center),
                )

                RecurringOccurrencesUiState.NotFound -> StatusPane(
                    icon = Icons.Outlined.Warning,
                    title = stringResource(R.string.recurring_missing_title),
                    message = stringResource(R.string.recurring_missing_message),
                    accentColor = MaterialTheme.colorScheme.errorContainer,
                    accentContentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.align(Alignment.Center),
                    action = {
                        Button(onClick = onBack) {
                            Text(text = stringResource(R.string.recurring_missing_action))
                        }
                    },
                )

                is RecurringOccurrencesUiState.Failed -> StatusPane(
                    icon = Icons.Outlined.Warning,
                    title = stringResource(R.string.recurring_error_title),
                    message = stringResource(R.string.recurring_error_message),
                    accentColor = MaterialTheme.colorScheme.errorContainer,
                    accentContentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.align(Alignment.Center),
                    action = {
                        Button(onClick = onRetry) {
                            Text(text = stringResource(R.string.recurring_error_retry))
                        }
                    },
                )

                is RecurringOccurrencesUiState.Content ->
                    if (uiState.occurrences.isEmpty()) {
                        StatusPane(
                            icon = Icons.Outlined.Refresh,
                            title = stringResource(R.string.recurring_occurrences_empty_title),
                            message = stringResource(R.string.recurring_occurrences_empty_message),
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        ) {
                            items(
                                items = uiState.occurrences,
                                key = { it.id },
                            ) { occurrence ->
                                OccurrenceRow(
                                    dueDate = occurrence.dueDate,
                                    amountPaise = occurrence.amountPaise,
                                    state = occurrence.state,
                                    type = occurrence.categoryType,
                                    transactionRemoved = occurrence.transactionRemoved,
                                    onPay = { onRequestPay(occurrence) },
                                    onSkip = { onRequestSkip(occurrence) },
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                        }
                    }
            }
        }
    }

    OccurrenceDialogs(
        pay = pay,
        skip = skip,
        onPayAmountChange = onPayAmountChange,
        onConfirmPay = onConfirmPay,
        onDismissPay = onDismissPay,
        onConfirmSkip = onConfirmSkip,
        onDismissSkip = onDismissSkip,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun RecurringOccurrencesContentPreview() {
    HomeFinanceTheme {
        RecurringOccurrencesContent(
            uiState = RecurringOccurrencesUiState.Content(
                scheduleName = "Shop rent",
                occurrences = listOf(
                    RecurringOccurrenceItem(
                        id = 3L,
                        scheduleName = "Shop rent",
                        categoryName = "Rent",
                        categoryType = TransactionType.EXPENSE,
                        dueDate = LocalDate.of(2026, 10, 5),
                        amountPaise = 1_500_000L,
                        state = OccurrenceDisplayState.OVERDUE,
                        transactionRemoved = false,
                    ),
                    RecurringOccurrenceItem(
                        id = 2L,
                        scheduleName = "Shop rent",
                        categoryName = "Rent",
                        categoryType = TransactionType.EXPENSE,
                        dueDate = LocalDate.of(2026, 9, 5),
                        amountPaise = 1_500_000L,
                        state = OccurrenceDisplayState.PAID,
                        transactionRemoved = false,
                    ),
                    RecurringOccurrenceItem(
                        id = 1L,
                        scheduleName = "Shop rent",
                        categoryName = "Rent",
                        categoryType = TransactionType.EXPENSE,
                        dueDate = LocalDate.of(2026, 8, 5),
                        amountPaise = 1_500_000L,
                        state = OccurrenceDisplayState.SKIPPED,
                        transactionRemoved = false,
                    ),
                ),
            ),
            pay = null,
            skip = null,
            onBack = {},
            onRetry = {},
            onRequestPay = {},
            onRequestSkip = {},
            onPayAmountChange = {},
            onConfirmPay = {},
            onDismissPay = {},
            onConfirmSkip = {},
            onDismissSkip = {},
        )
    }
}
