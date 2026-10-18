package com.homefinance.app.feature.recurring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.homefinance.app.R
import com.homefinance.app.core.util.CurrencyFormatter
import com.homefinance.app.core.util.DateFormatter
import com.homefinance.app.feature.addtransaction.LabelledTextField

/**
 * The "mark as paid" dialog: what is being paid, what was expected, and what was actually handed over.
 *
 * ### Nothing is written until the confirm button
 *
 * Opening this dialog is [OccurrenceActionsController.requestPay], which writes nothing at all. A
 * household looking at a due date - or a household who taps the row and changes their mind - must not
 * end up with an expense they never agreed to; that is the whole reason the details screen can offer
 * "Mark as paid" as a button rather than treating a due date as a payment.
 *
 * ### Expected and actual are two different figures, and are labelled so
 *
 * The expected amount is what the schedule committed to on that date, and it is shown as a fact. The
 * amount paid is what the household actually handed over, and it starts at the expected figure because
 * that is the common case. The two are never merged into one number on screen: a bill that came to
 * more than planned has to be recordable, and the household has to be able to see which is which.
 *
 * When the repository refuses the write, the reason replaces the hint and the confirm button goes dead,
 * because the only two refusals that are not catastrophic - the occurrence was already settled, or it
 * has gone - cannot be fixed by tapping again.
 */
@Composable
internal fun PayOccurrenceDialog(
    state: PayOccurrenceState,
    onAmountChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = { if (!state.isSaving) onDismiss() },
        modifier = modifier,
        title = { Text(text = stringResource(R.string.recurring_pay_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = state.scheduleName,
                    style = MaterialTheme.typography.titleMedium,
                )

                RecurringDetailRow(
                    label = stringResource(R.string.recurring_detail_category_label),
                    value = state.categoryName,
                )
                RecurringDetailRow(
                    label = stringResource(R.string.recurring_detail_due_date_label),
                    value = DateFormatter.format(state.dueDate),
                )
                RecurringDetailRow(
                    label = stringResource(R.string.recurring_pay_expected_label),
                    value = CurrencyFormatter.formatInr(state.expectedAmountPaise),
                )

                LabelledTextField(
                    label = stringResource(R.string.recurring_pay_amount_label),
                    value = state.amountInput,
                    onValueChange = onAmountChange,
                    placeholder = stringResource(R.string.add_transaction_amount_placeholder),
                    error = state.amountError?.let { stringResource(it.messageRes()) },
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                )

                val failure = state.failure?.message()
                Text(
                    text = failure ?: stringResource(R.string.recurring_pay_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failure == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !state.isSaving && state.failure == null,
            ) {
                Text(
                    text = if (state.isSaving) {
                        stringResource(R.string.recurring_pay_working)
                    } else {
                        stringResource(R.string.recurring_pay_confirm)
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

/**
 * The skip confirmation: an irreversible decision, stated plainly, with nothing written until it is
 * confirmed.
 *
 * It is a confirmation rather than an immediate action because a skip is final - the date is kept, it
 * is never generated again, and no expense will ever be recorded for it - so a stray tap must not be
 * able to do it.
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

/** The sentence for one settle failure. All three are shared by the pay and skip dialogs. */
@Composable
private fun SettleFailure.message(): String = when (this) {
    SettleFailure.OccurrenceNotFound -> stringResource(R.string.recurring_settle_failure_missing)
    SettleFailure.AlreadySettled -> stringResource(R.string.recurring_settle_failure_settled)
    is SettleFailure.Failed -> stringResource(R.string.recurring_settle_failure_generic)
}

/**
 * Both settle dialogs, driven straight from the controller's two state flows.
 *
 * Kept as one composable because the details screen and the occurrence history offer the same two
 * actions on the same kind of row, and one of them rendering a stale dialog differently from the other
 * is exactly the kind of drift that a shared entry point prevents. A dialog whose state is `null` is
 * simply not composed, so "the dialog is closed" has one representation and not two.
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
        PayOccurrenceDialog(
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
