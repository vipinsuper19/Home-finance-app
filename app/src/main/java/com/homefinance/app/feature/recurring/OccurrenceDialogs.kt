package com.homefinance.app.feature.recurring

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CurrencyRupee
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.homefinance.app.R
import com.homefinance.app.core.util.CurrencyFormatter
import com.homefinance.app.core.util.DateFormatter
import com.homefinance.app.data.model.TransactionType
import com.homefinance.app.feature.addtransaction.AmountField
import com.homefinance.app.feature.addtransaction.CategoryMark

/**
 * The payment sheet: what is being paid, and what was actually handed over.
 *
 * ### Why a sheet, and why it is the transaction form
 *
 * Recording a recurring payment *is* recording a transaction - the same figure, the same category, the
 * same ledger table - so the sheet draws the ledger form's own amount field rather than inventing a
 * second way to type an amount, and it draws the facts the entry will be written with (its date, its
 * category, its description) beside it. What it deliberately does not draw is the add screen's category
 * picker and date picker: the entry is filed under the schedule's own category and dated the date the
 * schedule committed to, and offering controls that the write would then ignore would be worse than
 * offering none. The one figure that legitimately differs from the plan is the amount, which is why
 * that is the one field.
 *
 * ### Nothing is written until the confirm button
 *
 * Opening this sheet is [OccurrenceActionsController.requestPay], which writes nothing at all. A
 * household looking at a due date - or a household who taps the row and changes their mind - must not
 * end up with an entry they never agreed to; that is the whole reason the details screen can offer
 * "mark as received" as a button rather than treating a due date as a payment.
 *
 * ### Expected and actual are two different figures, and are labelled so
 *
 * The expected amount is what the schedule committed to on that date, and it is shown as a fact. The
 * amount field is what the household actually handed over, and it starts at the expected figure because
 * that is the common case. The two are never merged into one number on screen: a bill that came to more
 * than planned has to be recordable, and the household has to be able to see which is which.
 *
 * When the repository refuses the write, the reason replaces the note and the confirm button goes dead,
 * because the only two refusals that are not catastrophic - the occurrence was already settled, or it
 * has gone - cannot be fixed by tapping again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PayOccurrenceSheet(
    state: PayOccurrenceState,
    onAmountChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        // Swiping the sheet away and tapping outside it are both "no": refused while a write is in
        // flight, because the repository may already have inserted the entry and hiding the sheet would
        // hide the outcome that is about to arrive.
        onDismissRequest = { if (!state.isSaving) onDismiss() },
        // Opened at its full height rather than half: this sheet is one decision, and the button that
        // makes it should not need to be hunted for below a fold. The content still scrolls when the
        // screen is shorter than it.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        // A fixed share of the screen rather than whatever height the content happens to want, because
        // the actions below are given the weighted remainder of it and must not depend on the facts
        // above being short. The sheet is always this tall, which suits it: it is never a short
        // question, and the facts still scroll within it on a screen too small to hold them.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(
                        if (state.type == TransactionType.INCOME) {
                            R.string.recurring_pay_sheet_title_income
                        } else {
                            R.string.recurring_pay_sheet_title_expense
                        },
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )

                NoticeBanner(
                    icon = Icons.Outlined.CheckCircle,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    text = stringResource(R.string.recurring_pay_sheet_intro),
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CategoryMark(
                                iconKey = state.categoryIconKey,
                                categoryName = state.categoryName,
                                type = state.type,
                                size = 44.dp,
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = state.scheduleName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    // The direction on its own rather than "Monthly income": the sheet
                                    // is about one date, and the rule behind it is stated in full on
                                    // the schedule's own screen.
                                    text = stringResource(state.type.labelRes()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        SheetFactRow(
                            icon = Icons.Outlined.CalendarToday,
                            label = stringResource(R.string.recurring_detail_due_date_label),
                            value = stringResource(
                                R.string.recurring_pay_sheet_due_date_value,
                                DateFormatter.format(state.dueDate),
                                DateFormatter.formatWeekday(state.dueDate),
                            ),
                        )
                        SheetFactRow(
                            icon = Icons.Outlined.CurrencyRupee,
                            label = stringResource(R.string.recurring_pay_expected_label),
                            value = CurrencyFormatter.formatInr(state.expectedAmountPaise),
                        )
                        SheetFactRow(
                            icon = Icons.Outlined.LocalOffer,
                            label = stringResource(R.string.recurring_detail_category_label),
                            value = state.categoryName,
                        )
                        SheetFactRow(
                            icon = Icons.AutoMirrored.Outlined.Notes,
                            label = stringResource(R.string.recurring_pay_sheet_description_label),
                            // What the ledger entry will actually say. See
                            // [com.homefinance.app.data.repository.RecurringRepository
                            // .markOccurrencePaid].
                            value = state.scheduleName,
                        )
                    }
                }
            }

            // The action zone, pinned below the facts: the field the household owns, the sentence saying
            // what confirming it will write, and the two buttons. A confirm button that has to be
            // scrolled for is one that may never be reached, and a warning about a tap is worth nothing
            // if it is the thing that scrolled away - so the three travel together, and the facts above
            // are what scrolls on a screen too small to hold everything.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 4.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AmountField(
                    value = state.amountInput,
                    derived = false,
                    error = state.amountError?.let { stringResource(it.messageRes()) },
                    onValueChange = onAmountChange,
                )

                val failure = state.failure?.message()
                NoticeBanner(
                    icon = Icons.Outlined.Info,
                    containerColor = if (failure == null) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    contentColor = if (failure == null) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                    text = failure ?: stringResource(
                        if (state.type == TransactionType.INCOME) {
                            R.string.recurring_pay_sheet_note_income
                        } else {
                            R.string.recurring_pay_sheet_note_expense
                        },
                    ),
                )

                RecurringWideButton(
                    label = if (state.isSaving) {
                        stringResource(R.string.recurring_pay_working)
                    } else {
                        stringResource(R.string.recurring_pay_sheet_confirm)
                    },
                    onClick = onConfirm,
                    enabled = !state.isSaving && state.failure == null,
                )

                RecurringWideOutlinedButton(
                    label = stringResource(R.string.action_cancel),
                    onClick = onDismiss,
                    enabled = !state.isSaving,
                )
            }
        }
    }
}

/**
 * One fact of the payment, as an icon, a label and the value underneath it.
 *
 * Stacked rather than set out as a label column and a value column, because these values - a date and a
 * weekday, a name - are too long to sit in half a sheet's width without wrapping into something that no
 * longer looks like a table.
 */
@Composable
private fun SheetFactRow(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp),
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/** A rounded notice: an icon and a sentence, in one of the theme's containers. */
@Composable
private fun NoticeBanner(
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * The skip confirmation: an irreversible decision, stated plainly, with nothing written until it is
 * confirmed.
 *
 * It is a confirmation rather than an immediate action because a skip is final - the date is kept, it
 * is never generated again, and no entry will ever be recorded for it - so a stray tap must not be able
 * to do it.
 */
@Composable
internal fun SkipOccurrenceDialog(
    state: SkipOccurrenceState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val failure = state.failure?.message()

    AlertDialog(
        onDismissRequest = { if (!state.isSaving) onDismiss() },
        modifier = modifier,
        title = { Text(text = stringResource(R.string.recurring_skip_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(
                        R.string.recurring_skip_message,
                        state.scheduleName,
                        DateFormatter.format(state.dueDate),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )

                if (failure != null) {
                    Text(
                        text = failure,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !state.isSaving && state.failure == null,
            ) {
                Text(
                    text = if (state.isSaving) {
                        stringResource(R.string.recurring_skip_working)
                    } else {
                        stringResource(R.string.recurring_skip_confirm)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.isSaving) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}

/** The sentence for one settle failure. Both settle flows share it, so the two cannot disagree. */
@Composable
private fun SettleFailure.message(): String = when (this) {
    SettleFailure.OccurrenceNotFound -> stringResource(R.string.recurring_settle_failure_missing)
    SettleFailure.AlreadySettled -> stringResource(R.string.recurring_settle_failure_settled)
    is SettleFailure.Failed -> stringResource(R.string.recurring_settle_failure_generic)
}

/**
 * Both settle flows, driven straight from the controller's two state flows.
 *
 * Kept as one composable because the details screen and the occurrence history offer the same two
 * actions on the same kind of row, and one of them rendering a stale sheet differently from the other
 * is exactly the kind of drift that a shared entry point prevents. A flow whose state is `null` is
 * simply not composed, so "the sheet is closed" has one representation and not two.
 */
@Composable
internal fun OccurrenceDialogs(
    pay: PayOccurrenceState?,
    skip: SkipOccurrenceState?,
    onPayAmountChange: (String) -> Unit,
    onConfirmPay: () -> Unit,
    onDismissPay: () -> Unit,
    onConfirmSkip: () -> Unit,
    onDismissSkip: () -> Unit,
) {
    if (pay != null) {
        PayOccurrenceSheet(
            state = pay,
            onAmountChange = onPayAmountChange,
            onConfirm = onConfirmPay,
            onDismiss = onDismissPay,
        )
    }

    if (skip != null) {
        SkipOccurrenceDialog(
            state = skip,
            onConfirm = onConfirmSkip,
            onDismiss = onDismissSkip,
        )
    }
}
