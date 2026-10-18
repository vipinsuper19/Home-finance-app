package com.homefinance.app.feature.recurring

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homefinance.app.R
import com.homefinance.app.core.ui.component.HomeFinanceTopBar
import com.homefinance.app.core.ui.component.StatusPane
import com.homefinance.app.core.ui.theme.HomeFinanceTheme
import com.homefinance.app.core.util.CurrencyFormatter
import com.homefinance.app.core.util.DateFormatter
import com.homefinance.app.data.model.RecurrenceFrequency
import com.homefinance.app.data.model.RecurringScheduleStatus
import java.time.Instant
import java.time.LocalDate

/**
 * One recurring expense in full: what it expects, when it next falls due, what has happened to it, and the
 * three controls that change it.
 *
 * ### What is on this screen that the list is not
 *
 * The list answers "what do I owe, and when". This answers "what is this thing, exactly, and what has it
 * done" - the first and last occurrence, the status, when it was set up, the *one* occurrence that is
 * currently waiting, and a short history.
 *
 * ### Acting on an occurrence
 *
 * The outstanding occurrence is shown with its own date and its own expected amount, and the household can
 * record that it was paid or decide it was skipped. Nothing is written by looking at this screen:
 * [RecurringDetailViewModel.requestPay] opens a dialog, and only confirming it - which goes through the
 * repository's conditional update and its single ledger write - actually records anything. Repeated taps
 * cannot produce a second expense, and a date that passed while the phone was off is never marked paid on
 * the household's behalf.
 *
 * ### The history here is deliberately read-only
 *
 * The rows below the outstanding one are for reading back, not for acting on, so they carry no buttons. A
 * household scrolling through old dates should not be one stray tap away from settling one; the rows that
 * *are* actionable are the outstanding block, and the full list on the occurrence history screen, where
 * each row is the thing being acted on.
 *
 * @param onBack the screen was dismissed.
 * @param onEdit the schedule is to be changed.
 * @param onOpenOccurrences the full occurrence history is to be opened.
 */
@Composable
fun RecurringDetailScreen(
    scheduleId: Long,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpenOccurrences: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecurringDetailViewModel = viewModel(
        factory = RecurringDetailViewModel.factory(scheduleId),
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pay by viewModel.pay.collectAsStateWithLifecycle()
    val skip by viewModel.skip.collectAsStateWithLifecycle()

    RecurringDetailContent(
        uiState = uiState,
        pay = pay,
        skip = skip,
        onBack = onBack,
        onRetry = viewModel::retry,
        onEdit = onEdit,
        onOpenOccurrences = onOpenOccurrences,
        onPause = viewModel::pause,
        onResume = viewModel::resume,
        onRetire = viewModel::retire,
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

/**
 * The screen's body, taking its state and every action as parameters.
 *
 * Split out from [RecurringDetailScreen] so each state - loading, gone, failed, and the three things a
 * schedule can be - can be rendered and asserted directly, including failures a real database will not
 * produce on demand.
 */
@Composable
internal fun RecurringDetailContent(
    uiState: RecurringDetailUiState,
    pay: PayOccurrenceState?,
    skip: SkipOccurrenceState?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    onOpenOccurrences: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetire: () -> Unit,
    onRequestPay: (RecurringOccurrenceItem) -> Unit,
    onRequestSkip: (RecurringOccurrenceItem) -> Unit,
    onPayAmountChange: (String) -> Unit,
    onConfirmPay: () -> Unit,
    onDismissPay: () -> Unit,
    onConfirmSkip: () -> Unit,
    onDismissSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showStopConfirmation by remember { mutableStateOf(false) }

    val title = when (uiState) {
        is RecurringDetailUiState.Content -> uiState.schedule.name
        else -> stringResource(R.string.recurring_detail_title)
    }

    Column(modifier = modifier.fillMaxSize()) {
        HomeFinanceTopBar(title = title, onNavigationClick = onBack)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when (uiState) {
                RecurringDetailUiState.Loading -> RecurringLoadingSpinner(
                    modifier = Modifier.align(Alignment.Center),
                )

                RecurringDetailUiState.NotFound -> StatusPane(
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

                is RecurringDetailUiState.Failed -> StatusPane(
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

                is RecurringDetailUiState.Content -> DetailBody(
                    state = uiState,
                    onEdit = onEdit,
                    onOpenOccurrences = onOpenOccurrences,
                    onPause = onPause,
                    onResume = onResume,
                    onStopRequested = { showStopConfirmation = true },
                    onRequestPay = onRequestPay,
                    onRequestSkip = onRequestSkip,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
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

    if (showStopConfirmation) {
        RecurringConfirmDialog(
            title = stringResource(R.string.recurring_stop_confirm_title),
            message = stringResource(R.string.recurring_stop_confirm_message),
            confirmLabel = stringResource(R.string.recurring_stop_confirm_action),
            onConfirm = {
                showStopConfirmation = false
                onRetire()
            },
            onDismiss = { showStopConfirmation = false },
        )
    }
}

/** The loaded screen: the schedule, what is waiting on it, its history, and its controls. */
@Composable
private fun DetailBody(
    state: RecurringDetailUiState.Content,
    onEdit: () -> Unit,
    onOpenOccurrences: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStopRequested: () -> Unit,
    onRequestPay: (RecurringOccurrenceItem) -> Unit,
    onRequestSkip: (RecurringOccurrenceItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val schedule = state.schedule

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.actionError != null) {
            ActionErrorStrip(message = stringResource(state.actionError.messageRes()))
        }

        RecurringScheduleSummary(
            name = schedule.name,
            description = schedule.description,
            categoryName = schedule.categoryName,
            categoryIconKey = schedule.categoryIconKey,
            amountPaise = schedule.amountPaise,
            frequency = frequencySummary(schedule.frequency, schedule.intervalCount),
            status = schedule.status,
        )

        OutstandingOccurrenceCard(
            occurrence = state.outstanding,
            scheduleStatus = schedule.status,
            onRequestPay = onRequestPay,
            onRequestSkip = onRequestSkip,
        )

        FactsCard(schedule = schedule, occurrenceCount = state.occurrenceCount)

        if (state.recentOccurrences.isNotEmpty()) {
            HistoryCard(
                occurrences = state.recentOccurrences,
                onOpenOccurrences = onOpenOccurrences,
            )
        }

        ScheduleControls(
            status = schedule.status,
            onEdit = onEdit,
            onPause = onPause,
            onResume = onResume,
            onStopRequested = onStopRequested,
        )
    }
}

/**
 * The one occurrence the household can act on, or a plain sentence saying there is nothing waiting.
 *
 * It states the date and *that occurrence's* expected amount before offering anything, so "mark as paid"
 * is never a button whose meaning has to be guessed at, and it uses the same overdue chip - the word plus
 * the glyph - as the list row, so the urgency reads the same in both places.
 *
 * A **stopped** schedule still offers both actions on its last outstanding occurrence. A household who
 * decided not to renew a subscription may still owe the final bill, and refusing to let them record it
 * would leave the ledger wrong in order to make a point about the schedule being over.
 */
@Composable
private fun OutstandingOccurrenceCard(
    occurrence: RecurringOccurrenceItem?,
    scheduleStatus: RecurringScheduleStatus,
    onRequestPay: (RecurringOccurrenceItem) -> Unit,
    onRequestSkip: (RecurringOccurrenceItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (occurrence == null) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.recurring_detail_nothing_due_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.recurring_detail_nothing_due_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    val overdue = occurrence.state == OccurrenceDisplayState.OVERDUE &&
        scheduleStatus != RecurringScheduleStatus.RETIRED

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (overdue) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
            contentColor = if (overdue) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OccurrenceStateChip(state = occurrence.state)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = DateFormatter.format(occurrence.dueDate),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Text(
                text = stringResource(
                    R.string.recurring_detail_expected_occurrence,
                    CurrencyFormatter.formatInr(occurrence.amountPaise),
                ),
                style = MaterialTheme.typography.headlineSmall,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RecurringWideOutlinedButton(
                    label = stringResource(R.string.recurring_skip_action),
                    onClick = { onRequestSkip(occurrence) },
                    modifier = Modifier.weight(1f),
                )
                RecurringWideButton(
                    label = stringResource(R.string.recurring_pay_action),
                    onClick = { onRequestPay(occurrence) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** The schedule's own rule and bookkeeping, as label-and-value rows. */
@Composable
private fun FactsCard(
    schedule: RecurringScheduleCard,
    occurrenceCount: Int,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            RecurringDetailRow(
                label = stringResource(R.string.recurring_detail_category_label),
                value = schedule.categoryName,
            )
            RecurringDetailDivider()
            RecurringDetailRow(
                label = stringResource(R.string.recurring_detail_frequency_label),
                value = frequencySummary(schedule.frequency, schedule.intervalCount),
            )
            RecurringDetailDivider()
            RecurringDetailRow(
                label = stringResource(R.string.recurring_start_label),
                value = DateFormatter.format(schedule.startDate),
            )
            RecurringDetailDivider()
            RecurringDetailRow(
                label = stringResource(R.string.recurring_end_label),
                value = schedule.endDate?.let(DateFormatter::format)
                    ?: stringResource(R.string.recurring_end_none),
            )
            RecurringDetailDivider()
            RecurringDetailRow(
                label = stringResource(R.string.recurring_detail_status_label),
                value = stringResource(schedule.status.labelRes()),
            )
            RecurringDetailDivider()
            RecurringDetailRow(
                label = stringResource(R.string.recurring_detail_occurrence_count_label),
                value = occurrenceCount.toString(),
            )
            RecurringDetailDivider()
            RecurringDetailRow(
                label = stringResource(R.string.recurring_detail_added_label),
                value = DateFormatter.formatTimestamp(schedule.addedAt),
            )
        }
    }
}

/** The newest few occurrences, and the way to the whole list. */
@Composable
private fun HistoryCard(
    occurrences: List<RecurringOccurrenceItem>,
    onOpenOccurrences: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.recurring_detail_history_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
            )

            occurrences.forEach { occurrence ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                // Read-only here: settling happens on the outstanding block above, or on the full list.
                OccurrenceRow(
                    dueDate = occurrence.dueDate,
                    amountPaise = occurrence.amountPaise,
                    state = occurrence.state,
                    transactionRemoved = occurrence.transactionRemoved,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            TextButton(
                onClick = onOpenOccurrences,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text(text = stringResource(R.string.recurring_detail_history_open))
            }
        }
    }
}

/**
 * Change it, pause it, stop it.
 *
 * Stop is the only one that asks first, because it is the only one that cannot be undone. Pause and
 * resume are one control's two sides and are reversible, so they act immediately - and the screen reads
 * its own status from the database, so the label cannot end up describing a state the database is not in.
 */
@Composable
private fun ScheduleControls(
    status: RecurringScheduleStatus,
    onEdit: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStopRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RecurringWideButton(
            label = stringResource(R.string.recurring_detail_edit),
            onClick = onEdit,
        )

        if (status != RecurringScheduleStatus.RETIRED) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RecurringWideOutlinedButton(
                    label = if (status == RecurringScheduleStatus.ACTIVE) {
                        stringResource(R.string.recurring_detail_pause)
                    } else {
                        stringResource(R.string.recurring_detail_resume)
                    },
                    onClick = if (status == RecurringScheduleStatus.ACTIVE) onPause else onResume,
                    modifier = Modifier.weight(1f),
                )
                RecurringWideOutlinedButton(
                    label = stringResource(R.string.recurring_detail_stop),
                    onClick = onStopRequested,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Text(
                text = stringResource(R.string.recurring_detail_retired_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The sentence for one failed action. */
private fun ActionError.messageRes(): Int = when (this) {
    is ActionError.Failed -> R.string.recurring_action_failed
    is ActionError.GenerationFailed -> R.string.recurring_generation_failed
}

@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun RecurringDetailContentPreview() {
    HomeFinanceTheme {
        RecurringDetailContent(
            uiState = RecurringDetailUiState.Content(
                schedule = RecurringScheduleCard(
                    id = 1L,
                    name = "Shop rent",
                    description = "Ground floor, paid by UPI",
                    categoryName = "Rent",
                    amountPaise = 1_500_000L,
                    frequency = RecurrenceFrequency.MONTHLY,
                    intervalCount = 1,
                    status = RecurringScheduleStatus.ACTIVE,
                    startDate = LocalDate.of(2026, 10, 5),
                    endDate = LocalDate.of(2027, 10, 5),
                    addedAt = Instant.parse("2026-10-05T04:30:00Z"),
                    nextDueDate = LocalDate.of(2026, 10, 5),
                    nextDueState = OccurrenceDisplayState.OVERDUE,
                ),
                outstanding = RecurringOccurrenceItem(
                    id = 9L,
                    scheduleName = "Shop rent",
                    categoryName = "Rent",
                    dueDate = LocalDate.of(2026, 10, 5),
                    amountPaise = 1_500_000L,
                    state = OccurrenceDisplayState.OVERDUE,
                    transactionRemoved = false,
                ),
                recentOccurrences = listOf(
                    RecurringOccurrenceItem(
                        id = 8L,
                        scheduleName = "Shop rent",
                        categoryName = "Rent",
                        dueDate = LocalDate.of(2026, 9, 5),
                        amountPaise = 1_500_000L,
                        state = OccurrenceDisplayState.PAID,
                        transactionRemoved = false,
                    ),
                ),
                occurrenceCount = 2,
            ),
            pay = null,
            skip = null,
            onBack = {},
            onRetry = {},
            onEdit = {},
            onOpenOccurrences = {},
            onPause = {},
            onResume = {},
            onRetire = {},
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
