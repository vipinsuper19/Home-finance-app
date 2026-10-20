package com.homefinance.app.feature.recurring

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homefinance.app.R
import com.homefinance.app.core.ui.component.HomeFinanceTopBar
import com.homefinance.app.core.ui.component.StatusPane
import com.homefinance.app.core.ui.theme.HomeFinanceTheme
import com.homefinance.app.core.ui.theme.OnUtilityContainer
import com.homefinance.app.core.ui.theme.UtilityContainer
import com.homefinance.app.core.util.CurrencyFormatter
import com.homefinance.app.core.util.DateFormatter
import com.homefinance.app.data.model.RecurrenceFrequency
import com.homefinance.app.data.model.RecurringScheduleStatus
import com.homefinance.app.data.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.absoluteValue

/**
 * One recurring schedule in full: what it expects, when it next falls due, what has happened to it, and
 * the controls that change it.
 *
 * ### The shape of the screen
 *
 * A heading that says what this schedule *is* - its mark, its name, how often it comes round and which
 * way the money moves - then the one occurrence that is actually waiting, then its own rule, then what
 * it has already done. The order runs from "what is this" to "what do I do about it" to "what has it
 * been doing", which is the order the household reads it in.
 *
 * ### Acting on an occurrence
 *
 * The outstanding occurrence is shown with its date and its expected amount, and the household can
 * record that it was paid or decide it was skipped. Nothing is written by looking at this screen:
 * [RecurringDetailViewModel.requestPay] opens the payment sheet and
 * [PayOccurrenceState] holds what was typed, and only confirming it - which goes through the
 * repository's conditional update and its single ledger write - actually records anything. Repeated
 * taps cannot produce a second entry, and a date that passed while the phone was off is never marked
 * paid on the household's behalf.
 *
 * ### The history here is deliberately read-only, and so is the summary
 *
 * The rows below the outstanding one are for reading back: each one leads to the full occurrence list,
 * where the row is the thing being acted on, rather than carrying its own settle buttons. A household
 * scrolling through old dates should not be one stray tap away from settling one. The summary figures
 * lead to that same list, because they are a count *of* it.
 *
 * ### Where the schedule's own controls live
 *
 * Pause, resume and stop are in the top bar's overflow menu - they act on the *schedule*, not on any
 * one date, and giving them the whole width of the foot of the screen made an occasional decision look
 * like the point of the screen. Edit is the pill in the information card's heading, where the facts it
 * changes are listed. Stop is the only one that asks first, because it is the only one that cannot be
 * undone.
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

    val content = uiState as? RecurringDetailUiState.Content

    Column(modifier = modifier.fillMaxSize()) {
        HomeFinanceTopBar(
            // The bar names the screen rather than the schedule: the schedule's own name is the first
            // thing in the heading below, at a size that says it is the subject, and a title that
            // elided a long name would be the one place that could not spell it out.
            title = stringResource(R.string.recurring_detail_title),
            onNavigationClick = onBack,
            actions = {
                if (content != null) {
                    ScheduleActionsMenu(
                        status = content.schedule.status,
                        onPause = onPause,
                        onResume = onResume,
                        onStopRequested = { showStopConfirmation = true },
                    )
                }
            },
        )

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

/**
 * The schedule's own three controls, behind one overflow button.
 *
 * A stopped schedule draws no button at all rather than an empty menu: there is nothing left that can
 * be done to it, and a menu that opens onto nothing is worse than no menu.
 */
@Composable
private fun ScheduleActionsMenu(
    status: RecurringScheduleStatus,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStopRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (status == RecurringScheduleStatus.RETIRED) return

    var open by remember { mutableStateOf(false) }
    val active = status == RecurringScheduleStatus.ACTIVE

    Box(modifier = modifier) {
        IconButton(onClick = { open = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.recurring_detail_more_actions),
            )
        }

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(
                            if (active) {
                                R.string.recurring_detail_pause
                            } else {
                                R.string.recurring_detail_resume
                            },
                        ),
                    )
                },
                onClick = {
                    open = false
                    if (active) onPause() else onResume()
                },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.recurring_detail_stop)) },
                onClick = {
                    open = false
                    onStopRequested()
                },
            )
        }
    }
}

/**
 * The loaded screen: the schedule, what is waiting on it, its history, and its controls.
 *
 * @param state the whole snapshot: the schedule, the outstanding occurrence if there is one, the short
 *   history, the three counts and the day they were all read against.
 */
@Composable
private fun DetailBody(
    state: RecurringDetailUiState.Content,
    onEdit: () -> Unit,
    onOpenOccurrences: () -> Unit,
    onRequestPay: (RecurringOccurrenceItem) -> Unit,
    onRequestSkip: (RecurringOccurrenceItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val schedule = state.schedule

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.actionError != null) {
            ActionErrorStrip(
                message = stringResource(state.actionError.messageRes()),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        RecurringScheduleSummary(
            name = schedule.name,
            categoryName = schedule.categoryName,
            categoryType = schedule.categoryType,
            categoryIconKey = schedule.categoryIconKey,
            amountPaise = schedule.amountPaise,
            unit = schedule.unit,
            ratePaise = schedule.ratePaise,
            frequency = frequencySummary(schedule.frequency, schedule.intervalCount),
            status = schedule.status,
        )

        NextPaymentCard(
            occurrence = state.outstanding,
            categoryType = schedule.categoryType,
            today = state.today,
            onRequestPay = onRequestPay,
            onRequestSkip = onRequestSkip,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        ScheduleInformationCard(
            schedule = schedule,
            onEdit = onEdit,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        PaymentSummaryCard(
            paidCount = state.paidCount,
            upcomingCount = state.upcomingCount,
            overdueCount = state.overdueCount,
            onOpenOccurrences = onOpenOccurrences,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        if (state.recentOccurrences.isNotEmpty()) {
            RecentPaymentsCard(
                occurrences = state.recentOccurrences,
                today = state.today,
                onOpenOccurrences = onOpenOccurrences,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        RecurringWideOutlinedButton(
            label = stringResource(R.string.recurring_detail_view_all),
            onClick = onOpenOccurrences,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        if (schedule.status == RecurringScheduleStatus.RETIRED) {
            Text(
                text = stringResource(R.string.recurring_detail_retired_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

/**
 * The one occurrence the household can act on, or a plain sentence saying there is nothing waiting.
 *
 * It states the date, the day of the week and *that occurrence's* expected amount before offering
 * anything, so "mark as received" is never a button whose meaning has to be guessed at. The chip in its
 * heading says how far off the date is - "Due in 5 days", "5 days overdue" - using the same words the
 * history rows use, so the urgency reads the same wherever it appears.
 *
 * A **stopped** schedule still offers both actions on its last outstanding occurrence. A household who
 * decided not to renew a subscription may still owe the final bill, and refusing to let them record it
 * would leave the ledger wrong in order to make a point about the schedule being over.
 *
 * Skip is the quieter of the two: it records that nothing happened, it writes no ledger entry, and on
 * this screen it is the exception rather than the point.
 */
@Composable
private fun NextPaymentCard(
    occurrence: RecurringOccurrenceItem?,
    categoryType: TransactionType,
    today: LocalDate,
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

    val overdue = occurrence.state == OccurrenceDisplayState.OVERDUE
    val container = if (overdue) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val content = if (overdue) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val amountText = CurrencyFormatter.formatInr(occurrence.amountPaise)
    val markLabel = stringResource(
        if (categoryType == TransactionType.INCOME) {
            R.string.recurring_detail_mark_received
        } else {
            R.string.recurring_detail_mark_paid
        },
        amountText,
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
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
                Icon(
                    imageVector = Icons.Outlined.CalendarMonth,
                    contentDescription = null,
                    tint = if (overdue) content else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.recurring_detail_next_payment),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                DueStateChip(state = occurrence.state, dueDate = occurrence.dueDate, today = today)
            }

            Text(
                text = amountText,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            )

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = DateFormatter.format(occurrence.dueDate),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = DateFormatter.formatWeekday(occurrence.dueDate),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            RecurringWideButton(label = markLabel, onClick = { onRequestPay(occurrence) })

            TextButton(
                onClick = { onRequestSkip(occurrence) },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(text = stringResource(R.string.recurring_skip_action))
            }
        }
    }
}

/**
 * How far off a date is, in the words the heading and the history rows share.
 *
 * Overdue counts *backwards* from today, so a bill that was due on the 5th and is being read on the
 * 10th reads "5 days overdue" rather than "-5 days". Century-scale mistakes are the only way the
 * difference can overflow an `Int`, and a date that far away is not a schedule anybody keeps.
 */
@Composable
private fun dueText(
    state: OccurrenceDisplayState,
    dueDate: LocalDate,
    today: LocalDate,
): String {
    val days = ChronoUnit.DAYS.between(today, dueDate).toInt()
    return when (state) {
        OccurrenceDisplayState.OVERDUE -> pluralStringResource(
            R.plurals.recurring_overdue_by_days,
            days.absoluteValue,
            days.absoluteValue,
        )
        OccurrenceDisplayState.DUE -> stringResource(R.string.recurring_occurrence_due)
        OccurrenceDisplayState.UPCOMING ->
            pluralStringResource(R.plurals.recurring_due_in_days, days, days)
        // An outstanding occurrence is never one of these two - it is filtered to the unsettled ones -
        // so this is a net rather than a reading.
        OccurrenceDisplayState.PAID, OccurrenceDisplayState.SKIPPED ->
            stringResource(state.labelRes())
    }
}

/** The due-soon / late chip on the next payment card: amber while there is time, red once there is not. */
@Composable
private fun DueStateChip(
    state: OccurrenceDisplayState,
    dueDate: LocalDate,
    today: LocalDate,
    modifier: Modifier = Modifier,
) {
    val overdue = state == OccurrenceDisplayState.OVERDUE

    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (overdue) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            UtilityContainer
        },
        contentColor = if (overdue) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            OnUtilityContainer
        },
        modifier = modifier,
    ) {
        Text(
            text = dueText(state = state, dueDate = dueDate, today = today),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * The schedule's own rule, as label-and-value rows, with the way to change it in the heading.
 *
 * Edit is a pill here rather than a button at the foot of the screen because this is the card it edits:
 * the household reads "Repeats: Monthly", decides it is wrong, and the control that fixes it is on the
 * same row as the heading that names the card. The status is not repeated here - the heading above
 * already says it in a chip - and the description only appears when the household wrote one.
 */
@Composable
private fun ScheduleInformationCard(
    schedule: RecurringScheduleCard,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.recurring_detail_schedule_information),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                EditPill(onClick = onEdit)
            }

            RecurringDetailRow(
                icon = Icons.Outlined.LocalOffer,
                label = stringResource(R.string.recurring_detail_category_label),
                value = schedule.categoryName,
            )
            RecurringDetailDivider()
            RecurringDetailRow(
                icon = Icons.Outlined.Repeat,
                label = stringResource(R.string.recurring_detail_frequency_label),
                value = frequencySummary(schedule.frequency, schedule.intervalCount),
            )
            RecurringDetailDivider()
            RecurringDetailRow(
                icon = Icons.Outlined.CalendarToday,
                label = stringResource(R.string.recurring_start_label),
                value = DateFormatter.format(schedule.startDate),
            )
            RecurringDetailDivider()
            RecurringDetailRow(
                icon = Icons.Outlined.CalendarToday,
                label = stringResource(R.string.recurring_end_label),
                value = schedule.endDate?.let(DateFormatter::format)
                    ?: stringResource(R.string.recurring_end_none),
            )

            schedule.description?.let { description ->
                RecurringDetailDivider()
                RecurringDetailRow(
                    label = stringResource(R.string.recurring_detail_description_label),
                    value = description,
                )
            }
        }
    }
}

/** The outlined "Edit" pill in a card's heading. */
@Composable
private fun EditPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(R.string.recurring_detail_edit_short),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * What the schedule has done so far, in three figures.
 *
 * One column per reading rather than a sentence, because the household is comparing them. The figures
 * *add up with the history* rather than with the short list on this screen: they are counted over every
 * occurrence, which is what makes the card worth opening.
 *
 * A skip is counted by none of the three. The household decided it did not happen, so calling it paid,
 * upcoming or overdue would all be untrue - and the history below still shows it.
 */
@Composable
private fun PaymentSummaryCard(
    paidCount: Int,
    upcomingCount: Int,
    overdueCount: Int,
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            CardHeading(
                icon = Icons.Outlined.BarChart,
                title = stringResource(R.string.recurring_detail_payment_summary),
                onClick = onOpenOccurrences,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SummaryFigure(
                    count = paidCount,
                    label = stringResource(R.string.recurring_detail_stat_paid),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                SummaryFigureDivider()
                SummaryFigure(
                    count = upcomingCount,
                    label = stringResource(R.string.recurring_detail_stat_upcoming),
                    color = OnUtilityContainer,
                    modifier = Modifier.weight(1f),
                )
                SummaryFigureDivider()
                SummaryFigure(
                    count = overdueCount,
                    label = stringResource(R.string.recurring_detail_stat_overdue),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** One figure of [PaymentSummaryCard]: the count over its label. */
@Composable
private fun SummaryFigure(
    count: Int,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = color,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The hairline between two [SummaryFigure]s. */
@Composable
private fun SummaryFigureDivider(modifier: Modifier = Modifier) {
    VerticalDivider(
        modifier = modifier,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * The first few occurrences - waiting dates soonest first, then settled history newest first - each
 * leading to the full list.
 *
 * The rows are read-only in the sense that matters: tapping one opens the occurrence history, where
 * every row is the thing being acted on, rather than offering settle buttons inside a summary. That
 * keeps the two screens from disagreeing about what a row's tap does, and keeps a stray tap on a
 * summary from settling a date the household was only looking at.
 */
@Composable
private fun RecentPaymentsCard(
    occurrences: List<RecurringOccurrenceItem>,
    today: LocalDate,
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            CardHeading(
                title = stringResource(R.string.recurring_detail_recent_payments),
                onClick = onOpenOccurrences,
            )

            occurrences.forEach { occurrence ->
                PaymentRow(
                    occurrence = occurrence,
                    today = today,
                    onClick = onOpenOccurrences,
                )
            }

            Spacer(modifier = Modifier.size(8.dp))
        }
    }
}

/** A card heading: its title, an optional leading icon, and the chevron that says it opens something. */
@Composable
private fun CardHeading(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** One payment in the short history: when, how much, how it stands, and the way to the whole list. */
@Composable
private fun PaymentRow(
    occurrence: RecurringOccurrenceItem,
    today: LocalDate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DateBadge(dueDate = occurrence.dueDate, state = occurrence.state)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = CurrencyFormatter.formatInr(occurrence.amountPaise),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = paymentSubtitle(occurrence = occurrence, today = today),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OccurrenceStateChip(state = occurrence.state)
    }
}

/**
 * A row's second line: what became of the date, or how far off it still is.
 *
 * An occurrence whose entry was deleted from the ledger says so here rather than losing its "Paid"
 * chip: the payment *was* recorded, and rewriting that would be editing the past to match the present.
 */
@Composable
private fun paymentSubtitle(occurrence: RecurringOccurrenceItem, today: LocalDate): String = when {
    occurrence.transactionRemoved -> stringResource(R.string.recurring_occurrence_payment_removed)
    occurrence.state == OccurrenceDisplayState.PAID ->
        stringResource(R.string.recurring_paid_on, DateFormatter.format(occurrence.dueDate))
    occurrence.state == OccurrenceDisplayState.SKIPPED ->
        stringResource(R.string.recurring_skipped_on, DateFormatter.format(occurrence.dueDate))
    else -> dueText(
        state = occurrence.state,
        dueDate = occurrence.dueDate,
        today = today,
    )
}

/**
 * The day a payment falls on, as a block: the number over the month.
 *
 * The date is the thing being scanned for in a list of payments, so it is drawn as a shape rather than
 * as a sentence, and its container follows the occurrence's state - the same amber, green and red the
 * chips use, so the two agree at a glance.
 */
@Composable
private fun DateBadge(
    dueDate: LocalDate,
    state: OccurrenceDisplayState,
    modifier: Modifier = Modifier,
) {
    val container = when {
        state == OccurrenceDisplayState.OVERDUE -> MaterialTheme.colorScheme.errorContainer
        state == OccurrenceDisplayState.PAID -> MaterialTheme.colorScheme.secondaryContainer
        state == OccurrenceDisplayState.SKIPPED -> MaterialTheme.colorScheme.surfaceVariant
        else -> UtilityContainer
    }
    val content = when {
        state == OccurrenceDisplayState.OVERDUE -> MaterialTheme.colorScheme.onErrorContainer
        state == OccurrenceDisplayState.PAID -> MaterialTheme.colorScheme.onSecondaryContainer
        state == OccurrenceDisplayState.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> OnUtilityContainer
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .width(48.dp)
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                // Padded to two digits so the block does not change width between the 5th and the 15th,
                // which would make a column of dates read as a ragged edge.
                text = dueDate.dayOfMonth.toString().padStart(length = 2, padChar = '0'),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
            Text(
                text = DateFormatter.formatShortMonth(dueDate),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Clip,
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
                    categoryName = "Shop Rent",
                    categoryType = TransactionType.EXPENSE,
                    amountPaise = 1_500_000L,
                    frequency = RecurrenceFrequency.MONTHLY,
                    intervalCount = 1,
                    status = RecurringScheduleStatus.ACTIVE,
                    startDate = LocalDate.of(2026, 10, 5),
                    endDate = LocalDate.of(2027, 10, 5),
                    addedAt = Instant.parse("2026-10-05T04:30:00Z"),
                    nextDueDate = LocalDate.of(2026, 11, 5),
                    nextDueState = OccurrenceDisplayState.UPCOMING,
                ),
                outstanding = RecurringOccurrenceItem(
                    id = 9L,
                    scheduleName = "Shop rent",
                    categoryName = "Shop Rent",
                    categoryType = TransactionType.EXPENSE,
                    dueDate = LocalDate.of(2026, 11, 5),
                    amountPaise = 1_500_000L,
                    state = OccurrenceDisplayState.UPCOMING,
                    transactionRemoved = false,
                ),
                recentOccurrences = listOf(
                    RecurringOccurrenceItem(
                        id = 9L,
                        scheduleName = "Shop rent",
                        categoryName = "Shop Rent",
                        categoryType = TransactionType.EXPENSE,
                        dueDate = LocalDate.of(2026, 11, 5),
                        amountPaise = 1_500_000L,
                        state = OccurrenceDisplayState.UPCOMING,
                        transactionRemoved = false,
                    ),
                    RecurringOccurrenceItem(
                        id = 8L,
                        scheduleName = "Shop rent",
                        categoryName = "Shop Rent",
                        categoryType = TransactionType.EXPENSE,
                        dueDate = LocalDate.of(2026, 10, 5),
                        amountPaise = 1_500_000L,
                        state = OccurrenceDisplayState.PAID,
                        transactionRemoved = false,
                    ),
                ),
                occurrenceCount = 14,
                today = LocalDate.of(2026, 10, 31),
                paidCount = 12,
                upcomingCount = 1,
                overdueCount = 1,
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
