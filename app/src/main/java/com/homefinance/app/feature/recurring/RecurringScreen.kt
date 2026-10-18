package com.homefinance.app.feature.recurring

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homefinance.app.R
import com.homefinance.app.core.ui.component.HomeFinanceTopBar
import com.homefinance.app.core.ui.component.StatusPane
import com.homefinance.app.core.ui.theme.HomeFinanceTheme
import com.homefinance.app.data.model.RecurrenceFrequency
import com.homefinance.app.data.model.RecurringScheduleStatus
import com.homefinance.app.data.model.TransactionType
import java.time.Instant
import java.time.LocalDate

/**
 * The Recurring tab: every schedule the household has, what each one expects, and when each next falls
 * due.
 *
 * ### This screen starts the engine
 *
 * [RecurringListViewModel] brings every active schedule's occurrences up to date when it is created, so
 * simply opening this tab is what keeps the due dates current. That is deliberate and it is safe: a
 * generated occurrence is an expectation, never an entry, and generating one moves no total. Nothing
 * here, and nothing reachable from here, marks a bill paid because its date arrived - that takes a
 * deliberate tap on "Mark as paid".
 *
 * ### Nothing here polls
 *
 * The rows come from the database's own `Flow`. Paying, skipping, pausing or editing something on
 * another screen re-emits this list, so a row's next due date and its overdue marker update themselves
 * without this screen knowing anything happened. There is no pull-to-refresh: it would be a gesture
 * that re-reads data the screen is already being pushed, and the failure it might be reached for -
 * a stale read - is exactly what the loading, error and retry states are for.
 *
 * ### Why the add action is in the bar
 *
 * Adding a schedule is one tap, and the bar is where this app puts the actions that belong to the
 * screen rather than to a row - see [com.homefinance.app.core.ui.component.HomeFinanceTopBar]. It
 * stays on screen in every state, including the one where there is nothing to list yet, which is why
 * the empty state also keeps a button of its own: a household with no schedules at all should not have
 * to find the plus sign to begin.
 *
 * ### Why there is no back arrow
 *
 * This is a top-level tab the bottom bar already owns, exactly like the ledger.
 *
 * @param onAddSchedule the "new schedule" action, including the empty state's own button.
 * @param onOpenSchedule one row was tapped; its details are opened from there.
 */
@Composable
fun RecurringScreen(
    onAddSchedule: () -> Unit,
    onOpenSchedule: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecurringListViewModel = viewModel(factory = RecurringListViewModel.factory()),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    RecurringContent(
        uiState = uiState,
        onRetry = viewModel::retry,
        onAddSchedule = onAddSchedule,
        onOpenSchedule = onOpenSchedule,
        onActiveChange = viewModel::setActive,
        onFilterChange = viewModel::onFilterChange,
        modifier = modifier,
    )
}

/**
 * The screen's body, taking its state as a parameter.
 *
 * Split out from [RecurringScreen] so each state - loading, failed, empty, and populated, including a
 * failed *action* over a perfectly good list and a filter that matched nothing - can be rendered and
 * asserted directly.
 */
@Composable
internal fun RecurringContent(
    uiState: RecurringListUiState,
    onRetry: () -> Unit,
    onAddSchedule: () -> Unit,
    onOpenSchedule: (Long) -> Unit,
    onActiveChange: (Long, Boolean) -> Unit,
    onFilterChange: (ScheduleTypeFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        HomeFinanceTopBar(
            title = stringResource(R.string.recurring_screen_title),
            actions = {
                IconButton(onClick = onAddSchedule) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.recurring_add),
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
                RecurringListUiState.Loading -> RecurringLoadingSpinner(
                    modifier = Modifier.align(Alignment.Center),
                )

                is RecurringListUiState.Failed -> StatusPane(
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

                is RecurringListUiState.Ready -> ReadyBody(
                    state = uiState,
                    onAddSchedule = onAddSchedule,
                    onOpenSchedule = onOpenSchedule,
                    onActiveChange = onActiveChange,
                    onFilterChange = onFilterChange,
                )
            }
        }
    }
}

/**
 * The loaded screen: a failed action's strip if there is one, the filter, then the schedules.
 *
 * ### The two empty screens are not the same screen
 *
 * A household with no schedules at all gets an invitation and *no* filter bar: there is nothing to
 * filter, and three chips over an empty page would invite them to look for a problem that does not
 * exist. A household whose filter simply matched nothing keeps the bar and is told that is what
 * happened, because the alternative - "No recurring schedules yet" over a database full of them -
 * would be a lie about their own money. Neither is the failure above, which must never be dressed as
 * "you have nothing".
 */
@Composable
private fun ReadyBody(
    state: RecurringListUiState.Ready,
    onAddSchedule: () -> Unit,
    onOpenSchedule: (Long) -> Unit,
    onActiveChange: (Long, Boolean) -> Unit,
    onFilterChange: (ScheduleTypeFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (state.actionError != null) {
            ActionErrorStrip(
                message = stringResource(state.actionError.messageRes()),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (!state.hasAnySchedule) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                StatusPane(
                    icon = Icons.Outlined.Refresh,
                    title = stringResource(R.string.recurring_empty_title),
                    message = stringResource(R.string.recurring_empty_message),
                    action = {
                        Button(
                            onClick = onAddSchedule,
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Text(text = stringResource(R.string.recurring_empty_action))
                        }
                    },
                )
            }
            return@Column
        }

        ScheduleTypeFilterBar(
            selected = state.filter,
            onFilterChange = onFilterChange,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        if (state.schedules.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.recurring_no_matches),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                // No dividers between the rows: the mark, the name and the three lines under it are
                // enough to tell one schedule from the next, and a rule under every row would put the
                // eye back on the lines rather than on the figures.
                items(items = state.schedules, key = { it.id }) { schedule ->
                    RecurringScheduleRow(
                        card = schedule,
                        onOpen = { onOpenSchedule(schedule.id) },
                        onActiveChange = { active -> onActiveChange(schedule.id, active) },
                    )
                }
            }
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
private fun RecurringContentPreview() {
    HomeFinanceTheme {
        RecurringContent(
            uiState = RecurringListUiState.Ready(
                schedules = listOf(
                    RecurringScheduleCard(
                        id = 1L,
                        name = "Shop rent",
                        description = "Ground floor, paid by UPI",
                        categoryName = "Rent",
                        categoryType = TransactionType.EXPENSE,
                        amountPaise = 1_500_000L,
                        frequency = RecurrenceFrequency.MONTHLY,
                        intervalCount = 1,
                        status = RecurringScheduleStatus.ACTIVE,
                        startDate = LocalDate.of(2026, 10, 5),
                        endDate = null,
                        addedAt = Instant.parse("2026-10-05T04:30:00Z"),
                        nextDueDate = LocalDate.of(2026, 10, 5),
                        nextDueState = OccurrenceDisplayState.OVERDUE,
                    ),
                    RecurringScheduleCard(
                        id = 2L,
                        name = "Milk",
                        description = null,
                        categoryName = "Milk",
                        categoryType = TransactionType.EXPENSE,
                        amountPaise = 6_000L,
                        unit = "litre",
                        ratePaise = 6_000L,
                        frequency = RecurrenceFrequency.DAILY,
                        intervalCount = 1,
                        status = RecurringScheduleStatus.PAUSED,
                        startDate = LocalDate.of(2026, 10, 5),
                        endDate = null,
                        addedAt = Instant.parse("2026-10-05T04:30:00Z"),
                        nextDueDate = LocalDate.of(2026, 11, 5),
                        nextDueState = OccurrenceDisplayState.UPCOMING,
                    ),
                    RecurringScheduleCard(
                        id = 3L,
                        name = "Salary",
                        description = null,
                        categoryName = "Salary",
                        categoryType = TransactionType.INCOME,
                        amountPaise = 4_500_000L,
                        frequency = RecurrenceFrequency.MONTHLY,
                        intervalCount = 1,
                        status = RecurringScheduleStatus.ACTIVE,
                        startDate = LocalDate.of(2026, 10, 1),
                        endDate = null,
                        addedAt = Instant.parse("2026-10-01T04:30:00Z"),
                        nextDueDate = LocalDate.of(2026, 11, 1),
                        nextDueState = OccurrenceDisplayState.UPCOMING,
                    ),
                ),
            ),
            onRetry = {},
            onAddSchedule = {},
            onOpenSchedule = {},
            onActiveChange = { _, _ -> },
            onFilterChange = {},
        )
    }
}
