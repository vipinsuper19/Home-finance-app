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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.homefinance.app.R
import com.homefinance.app.core.ui.theme.OnUtilityContainer
import com.homefinance.app.core.ui.theme.UtilityContainer
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
 * One schedule in the Recurring list: its mark, its name, its rule and figure, and when it next falls
 * due.
 *
 * ### Three lines, and no more
 *
 * A row states what the schedule is, what it expects and when, in that order. The description and the
 * category's own name are *not* repeated here even though the schedule may have both: the mark beside
 * the name is the category at a glance, the details screen spells both out, and a row that grew a line
 * for every fact a schedule has would stop being scannable, which is the one thing a list of bills has
 * to be.
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
            type = card.categoryType,
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

            // The rule and the figure it expects, on one line: "Monthly • ₹15,000.00", or the unit
            // price the household actually read off the bill: "Daily • ₹60.00 / litre".
            Text(
                text = listOf(
                    frequencySummary(card.frequency, card.intervalCount),
                    rateOrAmountText(card.amountPaise, card.ratePaise, card.unit),
                ).joinToString(separator = " • "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
 * The All / Expense / Income bar above the list.
 *
 * Three chips rather than a menu, because there are three and because each is a fact the household may
 * want to compare - a dropdown would hide two of them behind a tap. They are `selectable` in a
 * `selectableGroup` for the same reason the ledger's filter bar is: a screen reader then announces one
 * choice among three instead of three buttons, and "which one is on" is never carried by colour alone.
 *
 * Each chip is the same width so the bar does not change shape as the selection moves, and the chosen
 * one is filled in the theme's `primary` - the mock-up's own treatment - so the current view is
 * readable before the label is.
 */
@Composable
internal fun ScheduleTypeFilterBar(
    selected: ScheduleTypeFilter,
    onFilterChange: (ScheduleTypeFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // All, Expense, Income - the order the design draws them, and the order the ledger does not
        // share, because that bar was designed separately. The words are the ledger's own, so the two
        // screens cannot mean different things by "Expense".
        ScheduleTypeChip(
            label = stringResource(R.string.transactions_filter_all),
            filter = ScheduleTypeFilter.ALL,
            selected = selected,
            onFilterChange = onFilterChange,
            modifier = Modifier.weight(1f),
        )
        ScheduleTypeChip(
            label = stringResource(R.string.transactions_filter_expense),
            filter = ScheduleTypeFilter.EXPENSE,
            selected = selected,
            onFilterChange = onFilterChange,
            modifier = Modifier.weight(1f),
        )
        ScheduleTypeChip(
            label = stringResource(R.string.transactions_filter_income),
            filter = ScheduleTypeFilter.INCOME,
            selected = selected,
            onFilterChange = onFilterChange,
            modifier = Modifier.weight(1f),
        )
    }
}

/** One chip of [ScheduleTypeFilterBar]. */
@Composable
private fun ScheduleTypeChip(
    label: String,
    filter: ScheduleTypeFilter,
    selected: ScheduleTypeFilter,
    onFilterChange: (ScheduleTypeFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chosen = filter == selected

    FilterChip(
        selected = chosen,
        onClick = { onFilterChange(filter) },
        label = { Text(text = label, maxLines = 1) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = modifier,
    )
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

/** The "Active" / "Paused" / "Stopped" chip: a schedule that is not generating anything says so in words. */
@Composable
internal fun ScheduleStatusChip(
    status: RecurringScheduleStatus,
    modifier: Modifier = Modifier,
) {
    // Active is drawn as well as the two stopped states, because the details screen's heading states
    // where the schedule stands rather than only complaining when it is not running. The three are
    // three different containers so "running", "held" and "over" cannot be told apart by shape alone.
    val container = when (status) {
        RecurringScheduleStatus.ACTIVE -> MaterialTheme.colorScheme.primaryContainer
        RecurringScheduleStatus.PAUSED -> MaterialTheme.colorScheme.secondaryContainer
        RecurringScheduleStatus.RETIRED -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (status) {
        RecurringScheduleStatus.ACTIVE -> MaterialTheme.colorScheme.onPrimaryContainer
        RecurringScheduleStatus.PAUSED -> MaterialTheme.colorScheme.onSecondaryContainer
        RecurringScheduleStatus.RETIRED -> MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(status.labelRes()),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * One occurrence's state, as a chip.
 *
 * ### Four readings, four containers
 *
 * Overdue is the one that is asking to be noticed and is drawn in the error container; a date that is
 * simply ahead is drawn in the amber a household associates with a bill that has not arrived yet;
 * paid is drawn in the secondary green, because the money moved; and a skip is drawn in the neutral
 * surface, because nothing happened at all. The *words* are what carry the meaning - the colours only
 * agree with them.
 *
 * The overdue reading is also the only one that adds a glyph, for the same reason the list row does:
 * it is the one state that has to be noticed, and it must not depend on the reader telling two
 * container colours apart.
 *
 * @param label the words, or `null` for the state's own one-word name. A caller that can say something
 *   more useful - Home says "3 days overdue" rather than "Overdue" - passes it here rather than
 *   building a second chip, so the colours and the glyph keep being decided in one place.
 */
@Composable
internal fun OccurrenceStateChip(
    state: OccurrenceDisplayState,
    label: String? = null,
    modifier: Modifier = Modifier,
) {
    val settled = state.isSettled()

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
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content,
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
                text = label ?: stringResource(state.labelRes()),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * The heading of a schedule's details: what it is called, how often it comes round, which way the money
 * moves, what it expects and where it stands.
 *
 * ### Not a card
 *
 * The heading sits on the page rather than in a bordered box: it is the screen's title, and a title
 * inside a container reads as one more *fact* to be compared with the others instead of the subject they
 * are all about.
 *
 * ### The subtitle names the rule *and* the direction
 *
 * "Monthly Income" is the two things the household needs to place the figure below it - how often, and
 * which way - and it replaces the category's name, which the information card spells out in full. The
 * *direction* is the category's own type (see [RecurringScheduleCard.categoryType]), so the word cannot
 * disagree with the colour of the mark or with the entry this schedule's payments produce.
 *
 * ### The amount is the expected figure
 *
 * Drawn with the same formatter the ledger uses, so "what this costs" cannot read differently in two
 * places. When the household priced the thing by unit, the price of one unit is printed under it as well
 * ("₹60.00 / litre") - a *second* fact, not a replacement: the household may buy one litre or twenty, and
 * only they know which, so the amount stays the one the engine copies into an occurrence.
 *
 * It deliberately does **not** show a next due date. The card beneath it names the occurrence that is
 * actually waiting - or says that nothing is - with the date, that occurrence's figure and the button
 * that settles it. Repeating the date here as well would put the same fact on screen twice, once without
 * the ability to act on it.
 */
@Composable
internal fun RecurringScheduleSummary(
    name: String,
    categoryName: String,
    categoryType: TransactionType,
    categoryIconKey: String?,
    amountPaise: Long,
    frequency: String,
    status: RecurringScheduleStatus,
    unit: String? = null,
    ratePaise: Long? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryMark(
                iconKey = categoryIconKey,
                categoryName = categoryName,
                type = categoryType,
                size = 56.dp,
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = stringResource(
                        R.string.recurring_detail_direction,
                        frequency,
                        stringResource(categoryType.labelRes()),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    text = CurrencyFormatter.formatInr(amountPaise),
                    style = MaterialTheme.typography.headlineSmall
                        .copy(fontWeight = FontWeight.Bold),
                    color = amountColorFor(categoryType),
                )

                if (ratePaise != null && unit != null) {
                    Text(
                        text = stringResource(
                            R.string.recurring_detail_unit_price,
                            CurrencyFormatter.formatInr(ratePaise),
                            unit,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        ScheduleStatusChip(status = status)
    }
}

/**
 * One label-and-value line of a details card.
 *
 * The label and the value sit at opposite ends of the line, so a column of rows can be read down the
 * labels and across to the figures without the two running into each other. [icon] is optional and
 * decorative: it is drawn muted and carries no content description, because the label beside it already
 * says what the row is.
 */
@Composable
internal fun RecurringDetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    valueColor: Color = Color.Unspecified,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
        }

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
 * An occurrence whose recorded entry was deleted from the ledger keeps its "Paid" chip - the payment
 * *was* recorded - and gains a line saying the record is gone, rather than being quietly re-labelled as
 * unpaid. That is what the occurrence's own status means, and rewriting it would be inventing history.
 *
 * The two actions are nullable rather than optional-in-a-boolean, because a settled row genuinely does
 * not offer them: a household reading back through October should not be one stray tap away from
 * settling a date they were only looking at. Settling happens on the outstanding block above, or on
 * the full history screen, where each row is the thing being acted on.
 *
 * @param type the direction of the entry a payment would write, so the row's own button uses the same
 *   verb the schedule's details screen does: a household receiving a salary is not "paying" it.
 * @param onPay offered only while the occurrence is still pending, and `null` where it is not offered.
 * @param onSkip offered only while the occurrence is still pending, and `null` where it is not offered.
 */
@Composable
internal fun OccurrenceRow(
    dueDate: LocalDate,
    amountPaise: Long,
    state: OccurrenceDisplayState,
    type: TransactionType,
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
                        Text(
                            text = stringResource(
                                if (type == TransactionType.INCOME) {
                                    R.string.recurring_pay_action_received
                                } else {
                                    R.string.recurring_pay_action_paid
                                },
                            ),
                        )
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
