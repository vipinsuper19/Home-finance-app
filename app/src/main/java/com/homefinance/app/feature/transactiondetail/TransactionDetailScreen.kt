package com.homefinance.app.feature.transactiondetail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homefinance.app.R
import com.homefinance.app.core.ui.component.HomeFinanceTopBar
import com.homefinance.app.core.ui.theme.HomeFinanceTheme
import com.homefinance.app.core.util.CurrencyFormatter
import com.homefinance.app.core.util.DateFormatter
import com.homefinance.app.core.util.QuantityInput
import com.homefinance.app.data.local.relation.TransactionWithCategory
import com.homefinance.app.data.model.TransactionType
import com.homefinance.app.feature.addtransaction.CategoryMark
import java.time.Instant
import java.time.LocalDate

/**
 * One transaction, in full: what it was for, how much, when, and everything else recorded with it.
 *
 * ### Where the data comes from
 *
 * The screen renders [TransactionDetailViewModel.uiState] and nothing else. It never touches a
 * repository, never launches a coroutine and never queries anything while composing: the read happens
 * in the view model, and the screen is wired to it through lambdas so every state - including the two
 * a real database will not produce on demand - can be rendered and asserted directly (see
 * [TransactionDetailContent]).
 *
 * ### Why it is a screen of its own
 *
 * A ledger row has room for four things, and a transaction is more than four things. Rather than
 * crowding every row with buttons, a row opens this: the figures with their labels, and the two
 * actions that change them. [onEdit] and the delete confirmation are the only ways to alter a
 * recorded transaction anywhere in the app.
 *
 * ### Leaving after a deletion
 *
 * [onDeleted] is called only once [TransactionDetailUiState.Content.deleted] is set, which is only
 * after the database has removed the row. The effect is keyed on that flag, so a configuration change
 * re-runs it against the answer the view model kept rather than losing the navigation.
 *
 * @param transactionId the transaction to show. Passed in rather than read from the view model so the
 *   screen can be rendered directly in a test; production gives it the argument the route carried.
 */
@Composable
fun TransactionDetailScreen(
    transactionId: Long,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TransactionDetailViewModel = viewModel(
        factory = TransactionDetailViewModel.factory(transactionId),
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // The deletion lives inside Content, because it is a fact about a transaction rather than a
    // fourth kind of screen, so the screen asks the one variant that can carry it.
    val deleted = (uiState as? TransactionDetailUiState.Content)?.deleted == true
    LaunchedEffect(deleted) {
        if (deleted) onDeleted()
    }

    TransactionDetailContent(
        uiState = uiState,
        onBack = onBack,
        onEdit = onEdit,
        onDeleteRequested = viewModel::onDeleteRequested,
        onDeleteDismissed = viewModel::onDeleteDismissed,
        onConfirmDelete = viewModel::confirmDelete,
        onRetry = viewModel::retry,
        modifier = modifier,
    )
}

/**
 * The screen's body, taking its state and every action as parameters.
 *
 * Split out from [TransactionDetailScreen] the same way the other feature screens are split, so every
 * state - a transaction that has been deleted, a read that failed - can be rendered and asserted
 * directly rather than only through whatever the database happens to hold.
 */
@Composable
internal fun TransactionDetailContent(
    uiState: TransactionDetailUiState,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDeleteRequested: () -> Unit,
    onDeleteDismissed: () -> Unit,
    onConfirmDelete: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        HomeFinanceTopBar(
            title = stringResource(R.string.transaction_detail_title),
            onNavigationClick = onBack,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when (uiState) {
                TransactionDetailUiState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )

                TransactionDetailUiState.NotFound -> DetailNotice(
                    icon = Icons.AutoMirrored.Outlined.List,
                    title = stringResource(R.string.transaction_detail_not_found_title),
                    message = stringResource(R.string.transaction_detail_not_found_message),
                    actionLabel = stringResource(R.string.transaction_detail_not_found_action),
                    onAction = onBack,
                    modifier = Modifier.align(Alignment.Center),
                )

                is TransactionDetailUiState.Failed -> DetailNotice(
                    icon = Icons.Outlined.Warning,
                    title = stringResource(R.string.transaction_detail_error_title),
                    message = stringResource(R.string.transaction_detail_error_message),
                    accentColor = MaterialTheme.colorScheme.errorContainer,
                    accentContentColor = MaterialTheme.colorScheme.onErrorContainer,
                    actionLabel = stringResource(R.string.transaction_detail_retry),
                    onAction = onRetry,
                    modifier = Modifier.align(Alignment.Center),
                )

                is TransactionDetailUiState.Content -> LoadedTransaction(
                    state = uiState,
                    onEdit = onEdit,
                    onDeleteRequested = onDeleteRequested,
                )
            }
        }
    }

    val content = uiState as? TransactionDetailUiState.Content
    if (content != null && content.deleteConfirmationVisible) {
        DeleteConfirmation(
            isDeleting = content.isDeleting,
            failed = content.deleteError != null,
            onConfirm = onConfirmDelete,
            onDismiss = onDeleteDismissed,
        )
    }
}

/**
 * The transaction itself: who it was with and what it was for, then every figure recorded with it,
 * then the two things that can be done to it.
 */
@Composable
private fun LoadedTransaction(
    state: TransactionDetailUiState.Content,
    onEdit: () -> Unit,
    onDeleteRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val transaction = state.transaction
    val amountColor = amountColorFor(transaction.type)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        TransactionSummary(transaction = transaction, amountColor = amountColor)

        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                DetailRow(
                    label = stringResource(R.string.transaction_detail_type_label),
                    value = stringResource(typeNameRes(transaction.type)),
                    valueColor = amountColor,
                    leading = {
                        Icon(
                            imageVector = directionIconFor(transaction.type),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = amountColor,
                        )
                    },
                )

                DetailDivider()

                DetailRow(
                    label = stringResource(R.string.transaction_detail_category_label),
                    value = transaction.categoryName,
                    leading = {
                        CategoryMark(
                            iconKey = transaction.categoryIconKey,
                            categoryName = transaction.categoryName,
                            type = transaction.type,
                            size = 24.dp,
                        )
                    },
                )

                DetailDivider()

                DetailRow(
                    label = stringResource(R.string.transaction_detail_date_label),
                    value = DateFormatter.format(transaction.transactionDate),
                    leading = {
                        Icon(
                            imageVector = Icons.Outlined.DateRange,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )

                DetailDivider()

                DetailRow(
                    label = stringResource(R.string.transaction_detail_amount_label),
                    value = CurrencyFormatter.formatInr(transaction.amountPaise),
                    valueColor = amountColor,
                )

                // Only when the entry has them: a transaction recorded as a plain amount has no
                // quantity to report, and an empty row would say "0" about something never entered.
                val quantityText = quantityAndRateText(transaction)
                if (quantityText != null) {
                    DetailDivider()

                    DetailRow(
                        label = stringResource(R.string.transaction_detail_quantity_label),
                        value = quantityText,
                    )
                }

                transaction.notes?.let { notes ->
                    DetailDivider()

                    DetailRow(
                        label = stringResource(R.string.transaction_detail_notes_label),
                        value = notes,
                    )
                }

                DetailDivider()

                DetailRow(
                    label = stringResource(R.string.transaction_detail_created_label),
                    value = DateFormatter.formatTimestamp(transaction.createdAt),
                )

                DetailDivider()

                DetailRow(
                    label = stringResource(R.string.transaction_detail_updated_label),
                    value = DateFormatter.formatTimestamp(transaction.updatedAt),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onEdit,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
            ) {
                Text(text = stringResource(R.string.transaction_detail_edit))
            }

            Button(
                onClick = onDeleteRequested,
                // The destructive action carries the theme's own error colour rather than a second
                // red picked here, so it matches the colour an expense amount is already drawn in.
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
            ) {
                Text(text = stringResource(R.string.transaction_detail_delete))
            }
        }
    }
}

/**
 * The head of the screen: the category's mark, its name, the description and the amount.
 *
 * This is the summary a household recognises the entry by, so it is centred and set larger than the
 * labelled rows beneath it - which is also why the amount is coloured the way the ledger colours its
 * rows rather than being left in the body colour.
 */
@Composable
private fun TransactionSummary(
    transaction: TransactionWithCategory,
    amountColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CategoryMark(
            iconKey = transaction.categoryIconKey,
            categoryName = transaction.categoryName,
            type = transaction.type,
            size = 72.dp,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = transaction.categoryName,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        transaction.description?.let { description ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = CurrencyFormatter.formatInr(transaction.amountPaise),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = amountColor,
        )
    }
}

/**
 * One labelled figure.
 *
 * The label keeps the left column and the value takes the wider right one, right-aligned, so a column
 * of these reads as two columns of text rather than as a ragged list - and a long note wraps inside
 * its own column instead of pushing its label off the row.
 */
@Composable
private fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Row(
            modifier = Modifier.weight(2f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = valueColor,
                textAlign = TextAlign.End,
            )
        }
    }
}

/** A hairline between two rows of the figures card, inset so it does not touch the card's edge. */
@Composable
private fun DetailDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * The delete confirmation.
 *
 * Deleting a recorded transaction cannot be undone and cannot be got back, so it is asked once, in
 * words that say exactly that, and it is the *only* way the deletion happens. The dialog stays up
 * while the deletion is in flight, with both of its actions disabled, because a question the
 * household has already answered should not look like a question still waiting for an answer.
 *
 * A failure is reported inside the dialog, beside the button that caused it, and the transaction is
 * left exactly as it was - so tapping Delete again after a failure is a retry rather than a second
 * attempt at something that may have half-happened.
 */
@Composable
private fun DeleteConfirmation(
    isDeleting: Boolean,
    failed: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = { if (!isDeleting) onDismiss() },
        modifier = modifier,
        title = { Text(text = stringResource(R.string.transaction_delete_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = stringResource(R.string.transaction_delete_dialog_message))
                if (failed) {
                    Text(
                        text = stringResource(R.string.transaction_delete_error),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isDeleting,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                if (isDeleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.transaction_delete_dialog_deleting))
                } else {
                    Text(text = stringResource(R.string.transaction_delete_dialog_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isDeleting) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * Centred glyph, headline, supporting sentence and one action, for the states with nothing to show.
 *
 * Mirrors the ledger's status message and the picker's, deliberately: a transaction that is no longer
 * there and a read that failed should look like the same kind of thing wherever they happen, and only
 * the accent colour separates "there is nothing here" from "this did not work".
 */
@Composable
private fun DetailNotice(
    icon: ImageVector,
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    accentContentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = accentColor,
            contentColor = accentContentColor,
        ) {
            Box(
                modifier = Modifier.size(72.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = icon, contentDescription = null)
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onAction) {
            Text(text = actionLabel)
        }
    }
}

/**
 * The "1.5 × ₹60.00" line for an entry that was recorded by quantity and rate, or `null` for one that
 * was recorded as a plain amount.
 *
 * The quantity is rendered exactly as the household would have typed it - the same function the edit
 * form fills its field with - rather than as a number rounded for display, so the figure here and the
 * figure there cannot disagree. The rate is money, so it uses the money formatter.
 *
 * There is no unit named. The database stores a quantity as a count of thousandths and says nothing
 * about thousandths of *what*, so calling it litres or kilograms here would be an invention - and an
 * invented unit attached to a real figure is worse than no unit at all.
 *
 * The multiplication sign itself lives in a string resource rather than in this file, so the way the
 * two figures are joined can be adjusted for a reader without touching the arithmetic-free code that
 * assembles them.
 */
@Composable
private fun quantityAndRateText(transaction: TransactionWithCategory): String? {
    val quantityThousandths = transaction.quantityThousandths ?: return null
    val ratePaise = transaction.ratePaise ?: return null

    return stringResource(
        R.string.transaction_detail_quantity_value,
        QuantityInput.toEditableText(quantityThousandths),
        CurrencyFormatter.formatInr(ratePaise),
    )
}

/**
 * Income is emerald and expenses are red - the same convention the ledger and the Home screen's
 * summary cards use, so an amount keeps its meaning wherever it appears.
 */
@Composable
private fun amountColorFor(type: TransactionType): Color = when (type) {
    TransactionType.INCOME -> MaterialTheme.colorScheme.primary
    TransactionType.EXPENSE -> MaterialTheme.colorScheme.error
}

/** Money out points down and money in points up, so the direction is legible without the colour. */
private fun directionIconFor(type: TransactionType): ImageVector = when (type) {
    TransactionType.INCOME -> Icons.Filled.ArrowUpward
    TransactionType.EXPENSE -> Icons.Filled.ArrowDownward
}

private fun typeNameRes(type: TransactionType): Int = when (type) {
    TransactionType.INCOME -> R.string.add_transaction_type_income
    TransactionType.EXPENSE -> R.string.add_transaction_type_expense
}

private val PREVIEW_TRANSACTION_DATE: LocalDate = LocalDate.of(2026, 10, 14)

@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun TransactionDetailContentPreview() {
    HomeFinanceTheme {
        TransactionDetailContent(
            uiState = TransactionDetailUiState.Content(
                transaction = TransactionWithCategory(
                    id = 7L,
                    type = TransactionType.EXPENSE,
                    amountPaise = 32_000L,
                    categoryId = 2L,
                    description = "Vegetables and fruits",
                    notes = "Fresh vegetables",
                    quantityThousandths = null,
                    ratePaise = null,
                    transactionDate = PREVIEW_TRANSACTION_DATE,
                    createdAt = Instant.parse("2026-10-14T05:00:00Z"),
                    updatedAt = Instant.parse("2026-10-14T05:00:00Z"),
                    categoryName = "Groceries",
                ),
            ),
            onBack = {},
            onEdit = {},
            onDeleteRequested = {},
            onDeleteDismissed = {},
            onConfirmDelete = {},
            onRetry = {},
        )
    }
}
