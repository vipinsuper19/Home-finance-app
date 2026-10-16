package com.homefinance.app.feature.transactions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homefinance.app.R
import com.homefinance.app.core.ui.component.HomeFinanceTopBar
import com.homefinance.app.core.ui.theme.HomeFinanceTheme
import com.homefinance.app.core.util.DateFormatter
import com.homefinance.app.data.model.TransactionType
import java.time.LocalDate

/**
 * The ledger: every recorded income and expense, newest first.
 *
 * ### Where the data comes from
 *
 * The screen renders [TransactionsViewModel.uiState] and nothing else. It never touches a DAO or
 * a repository, never launches a coroutine and never queries anything while composing - the
 * database work happens in the view model, and Room pushes a new list when a row changes.
 *
 * ### Insets and the shell
 *
 * The bottom bar and the system-bar padding belong to the application shell's single `Scaffold`,
 * and [HomeFinanceTopBar] zeroes its own insets for the same reason. Adding padding for them here
 * as well would double them, as it would on the Home screen.
 *
 * The screen title lives in the top bar only. The body leads with its state, so "Transactions"
 * is never printed twice.
 */
@Composable
fun TransactionsScreen(
    modifier: Modifier = Modifier,
    viewModel: TransactionsViewModel = viewModel(factory = TransactionsViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    TransactionsContent(
        uiState = uiState,
        onRetry = viewModel::retry,
        modifier = modifier,
    )
}

/**
 * The screen's body, taking its state as a parameter.
 *
 * Split out from [TransactionsScreen] so every state - including a failure that a real database
 * will not produce on demand - can be rendered and asserted directly.
 */
@Composable
internal fun TransactionsContent(
    uiState: TransactionsUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        HomeFinanceTopBar(title = stringResource(R.string.title_transactions))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when (uiState) {
                TransactionsUiState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )

                is TransactionsUiState.Error -> StatusMessage(
                    icon = Icons.Outlined.Warning,
                    title = stringResource(R.string.transactions_error_title),
                    message = stringResource(R.string.transactions_error_message),
                    accentColor = MaterialTheme.colorScheme.errorContainer,
                    accentContentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.align(Alignment.Center),
                    action = {
                        Button(onClick = onRetry) {
                            Icon(imageVector = Icons.Filled.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = stringResource(R.string.transactions_error_retry))
                        }
                    },
                )

                is TransactionsUiState.Content -> if (uiState.items.isEmpty()) {
                    // Loaded successfully and there is nothing to show: a success, and a
                    // different thing from a failed read.
                    StatusMessage(
                        icon = Icons.AutoMirrored.Outlined.List,
                        title = stringResource(R.string.transactions_empty_title),
                        message = stringResource(R.string.transactions_empty_message),
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    TransactionList(
                        items = uiState.items,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * Centred icon, headline and supporting sentence, with an optional action underneath.
 *
 * Shared by the empty and error states so the two can never drift apart visually; only the accent
 * colours differ, because an error must not be dressed in the same reassuring green as an empty
 * ledger.
 */
@Composable
private fun StatusMessage(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primaryContainer,
    accentContentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    action: (@Composable () -> Unit)? = null,
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
        if (action != null) {
            Spacer(modifier = Modifier.height(24.dp))
            action()
        }
    }
}

@Composable
private fun TransactionList(
    items: List<TransactionListItem>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Keyed by the row's own id: stable keys mean a change to one entry animates that row
        // instead of every row below it being treated as new.
        items(items = items, key = { it.id }) { item ->
            TransactionRow(item = item)
        }
    }
}

/**
 * One ledger entry.
 *
 * The text block takes the leftover width (`weight(1f)`) while the amount keeps its intrinsic
 * width, so a long category name or description is ellipsised instead of pushing the amount off
 * the edge or overflowing the card.
 */
@Composable
private fun TransactionRow(
    item: TransactionListItem,
    modifier: Modifier = Modifier,
) {
    val description = item.description

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = item.categoryName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = DateFormatter.format(item.transactionDate),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = item.signedAmountText(),
                style = MaterialTheme.typography.titleMedium,
                color = amountColorFor(item.type),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * Income is emerald and expenses are red - the same convention the Home screen's summary cards
 * use, so an amount keeps its meaning wherever it appears.
 */
@Composable
private fun amountColorFor(type: TransactionType): Color = when (type) {
    TransactionType.INCOME -> MaterialTheme.colorScheme.primary
    TransactionType.EXPENSE -> MaterialTheme.colorScheme.error
}

@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun TransactionsContentPreview() {
    HomeFinanceTheme {
        TransactionsContent(
            uiState = TransactionsUiState.Content(
                items = listOf(
                    TransactionListItem(
                        id = 1L,
                        categoryName = "Salary",
                        description = null,
                        transactionDate = LocalDate.of(2026, 10, 1),
                        amountPaise = 4_850_000L,
                        type = TransactionType.INCOME,
                    ),
                    TransactionListItem(
                        id = 2L,
                        categoryName = "Groceries",
                        description = "Weekly shop at the market",
                        transactionDate = LocalDate.of(2026, 10, 3),
                        amountPaise = 273_405L,
                        type = TransactionType.EXPENSE,
                    ),
                ),
            ),
            onRetry = {},
        )
    }
}
