package com.homefinance.app.feature.recurring

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.homefinance.app.R
import com.homefinance.app.core.util.CurrencyFormatter
import com.homefinance.app.core.util.DateFormatter
import com.homefinance.app.data.model.RecurringScheduleStatus
import com.homefinance.app.data.model.TransactionType
import com.homefinance.app.feature.addtransaction.CategoryMark
import java.time.LocalDate

/**
 * The recurring feature's drawing rules, kept in one file so the list, the details screen and the
 * occurrence history cannot disagree about what a schedule or an occurrence looks like.
 *
 * Nothing here decides anything: every value it draws has already been resolved by a view model
 * against a known "today", and every sentence comes from a string resource.
 */

/**
 * One schedule in the Recurring list.
 *
 * ### The whole row is the tap target
 *
 * There is one action - open the schedule - so the entire row carries it, rather than a chevron at the
 * right edge that the household has to aim at. The switch inside stops the row's own click from firing
 * (a `Switch` consumes its own taps), so toggling it can never also open the details screen.
 *
 * ### The overdue cue is not a colour
 *
 * An overdue occurrence is announced three ways at once: the literal word "Overdue", the warning glyph
 * beside it, and the date it started being late. Colour is added on top of that and never carries the
 * message by itself, because red is the one thing a household with a colour-vision deficiency cannot
 * rely on - and this is the row that is supposed to catch their eye.
 */
@Composable
internal fun RecurringScheduleRow(
    card: RecurringScheduleCard,
    onOpen: () -> Unit,
    onActiveChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryMark(
            iconKey = card.categoryIconKey,
            categoryName = card.categoryName,
            type = TransactionType.EXPENSE,
            size = 44.dp,
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = card.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (card.description != null) {
                Text(
                    text = card.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // The rule and the figure it expects, on one line: "Monthly • ₹15,000.00".
            Text(
                text = listOf(
                    frequencySummary(card.frequency, card.intervalCount),
                    CurrencyFormatter.formatInr(card.amountPaise),
                ).joinToString(separator = " • "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = card.categoryName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            NextDueLine(card = card)
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (card.status != RecurringScheduleStatus.ACTIVE) {
                ScheduleStatusChip(status = card.status)
            }

            Switch(
                checked = card.status == RecurringScheduleStatus.ACTIVE,
                onCheckedChange = onActiveChange,
                enabled = card.status != RecurringScheduleStatus.RETIRED,
            )
        }
    }
}

/**
 * Where the schedule stands: its next due date, or the fact that it has one that is late.
 *
 * A retired schedule is never reported as overdue, however old its outstanding occurrence is: a
 * household that stopped a schedule knows why, and shouting about a date they deliberately abandoned
 * would train them to ignore the marker everywhere else.
 */
@Composable
private fun NextDueLine(card: RecurringScheduleCard, modifier: Modifier = Modifier) {
    val dueDate = card.nextDueDate
    val state = card.nextDueState

    if (dueDate == null || state == null) {
        Text(
            text = stringResource(R.string.recurring_nothing_outstanding),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }

    val overdue = state == OccurrenceDisplayState.OVERDUE &&
        card.status != RecurringScheduleStatus.RETIRED

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (overdue) {
            Icon(
                imageVector = Icons.Outlined.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
        }

        val text = when {
            overdue -> stringResource(
                R.string.recurring_overdue_since,
                DateFormatter.format(dueDate),
            )
            state == OccurrenceDisplayState.DUE ->
                stringResource(R.string.recurring_occurrence_due)
            else -> stringResource(R.string.recurring_next_due, DateFormatter.format(dueDate))
        }

        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (overdue) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (overdue) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** The "Paused" / "Stopped" chip: a schedule that is not generating anything says so in words. */
@Composable
internal fun ScheduleStatusChip(
    status: RecurringScheduleStatus,
    modifier: Modifier = Modifier,
) {
    val paused = status == RecurringScheduleStatus.PAUSED

    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (paused) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
        contentColor = if (paused) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onErrorContainer
        },
        modifier = modifier,
    ) {
        Text(
            text = stringResource(status.labelRes()),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * One occurrence's state, as a chip.
 *
 * The overdue reading is the only one that adds a glyph, for the same reason the list row does: it is
 * the one state that is asking to be noticed, and it must not depend on the reader telling two
 * container colours apart.
 */
@Composable
internal fun OccurrenceStateChip(
    state: OccurrenceDisplayState,
    modifier: Modifier = Modifier,
) {
    val settled = state.isSettled()

    Surface(
        shape = MaterialTheme.shapes.small,
        color = when {
            state == OccurrenceDisplayState.OVERDUE -> MaterialTheme.colorScheme.errorContainer
            settled -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = when {
            state == OccurrenceDisplayState.OVERDUE -> MaterialTheme.colorScheme.onErrorContainer
            settled -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSecondaryContainer
        },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (settled) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            } else if (state == OccurrenceDisplayState.OVERDUE) {
                Icon(
                    imageVector = Icons.Outlined.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            }

            Text(
                text = stringResource(state.labelRes()),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * The headline block of a schedule's details: what it is for, what it expects, and where it stands.
 *
 * The amount is the schedule's *expected* figure and is drawn with the same formatter the ledger uses,
 * so "what this costs" cannot read differently in two places.
 *
 * It deliberately does **not** show a next due date. The block beneath it names the one occurrence that
 * is actually waiting - or says that nothing is - and it does so with the date, the expected figure of
 * *that* occurrence and the two buttons that settle it. Repeating the date here as well would put the
 * same fact on screen twice, in one place without the ability to act on it.
 */
@Composable
internal fun RecurringScheduleSummary(
    name: String,
    description: String?,
    categoryName: String,
    categoryIconKey: String?,
    amountPaise: Long,
    frequency: String,
    status: RecurringScheduleStatus,
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CategoryMark(
                    iconKey = categoryIconKey,
                    categoryName = categoryName,
                    type = TransactionType.EXPENSE,
                    size = 44.dp,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = categoryName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ScheduleStatusChip(status = status)
            }

            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = CurrencyFormatter.formatInr(amountPaise),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            )

            Text(
                text = frequency,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One label-and-value line of a details card. */
@Composable
internal fun RecurringDetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.2f),
        )
    }
}

/** The hairline between two [RecurringDetailRow]s, inset past the label column. */
@Composable
internal fun RecurringDetailDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * One occurrence in the history: its date, what it was expected to be worth, and what became of it.
 *
 * An occurrence whose expense was deleted from the ledger keeps its "Paid" chip - the payment *was*
 * recorded - and gains a line saying the record is gone, rather than being quietly re-labelled as
 * unpaid. That is what the occurrence's own status means, and rewriting it would be inventing history.
 *
 * The two actions are nullable rather than optional-in-a-boolean, because the details screen's short
 * history genuinely does not offer them: a household reading back through October should not be one
 * stray tap away from settling a date they were only looking at. Settling happens on the outstanding
 * block above, or on the full history screen, where each row is the thing being acted on.
 *
 * @param onPay offered only while the occurrence is still pending, and `null` where it is not offered.
 * @param onSkip offered only while the occurrence is still pending, and `null` where it is not offered.
 */
@Composable
internal fun OccurrenceRow(
    dueDate: LocalDate,
    amountPaise: Long,
    state: OccurrenceDisplayState,
    transactionRemoved: Boolean,
    onPay: (() -> Unit)? = null,
    onSkip: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = DateFormatter.format(dueDate),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )

            Text(
                text = CurrencyFormatter.formatInr(amountPaise),
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OccurrenceStateChip(state = state)

            if (transactionRemoved) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.recurring_occurrence_payment_removed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!state.isSettled() && (onPay != null || onSkip != null)) {
                Spacer(modifier = Modifier.weight(1f))

                if (onSkip != null) {
                    TextButton(onClick = onSkip) {
                        Text(text = stringResource(R.string.recurring_skip_action))
                    }
                }
                if (onPay != null) {
                    TextButton(onClick = onPay) {
                        Text(text = stringResource(R.string.recurring_pay_action))
                    }
                }
            }
        }
    }
}

/**
 * The strip that reports a failed pause, resume or stop without taking the list away.
 *
 * It has no dismiss button and no action: the household can read it and try again, and the next
 * successful action clears it. Adding a "Dismiss" would imply the failure is something to be
 * acknowledged rather than something that did not happen.
 */
@Composable
internal fun ActionErrorStrip(
    message: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Warning,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * A destructive-or-not confirmation, laid out the same way wherever the recurring screens ask one.
 *
 * Used for both "Stop this schedule?" and "Skip this occurrence?", because they are the same shape of
 * question: one irreversible decision, described in a sentence, with the safe option first.
 */
@Composable
internal fun RecurringConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isWorking: Boolean = false,
    progressLabel: String? = null,
) {
    AlertDialog(
        onDismissRequest = { if (!isWorking) onDismiss() },
        modifier = modifier,        title = { Text(text = title) },
        text = { Text(text = message) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isWorking) {
                if (isWorking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    if (progressLabel != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = progressLabel)
                    }
                } else {
                    Text(text = confirmLabel)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isWorking) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}

/** A centred spinner, for the states whose only fact is that the database has not answered yet. */
@Composable
internal fun RecurringLoadingSpinner(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** An empty, quiet placeholder used where a details card would otherwise have a gap. */
@Composable
internal fun RecurringEmptyNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** An action button that fills the width, for the details screen's own controls. */
@Composable
internal fun RecurringWideButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        Text(text = label)
    }
}

/** The quieter half of a pair of wide buttons. */
@Composable
internal fun RecurringWideOutlinedButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        Text(text = label)
    }
}
