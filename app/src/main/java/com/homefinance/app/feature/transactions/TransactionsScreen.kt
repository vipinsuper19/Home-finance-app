package com.homefinance.app.feature.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
import com.homefinance.app.feature.addtransaction.CategoryMark
import java.time.LocalDate
import java.time.YearMonth

/**
 * The ledger: every recorded income and expense, newest first, grouped under the day it happened.
 *
 * ### Where the data comes from
 *
 * The screen renders [TransactionsViewModel.uiState] and nothing else. It never touches a DAO or
 * a repository, never launches a coroutine and never queries anything while composing - the
 * database work happens in the view model, and Room pushes a new list when a row changes. Searching
 * and filtering are no exception: they travel out as callbacks and come back as a new state.
 *
 * ### Insets and the shell
 *
 * The bottom bar and the system-bar padding belong to the application shell's single `Scaffold`,
 * and [HomeFinanceTopBar] zeroes its own insets for the same reason. Adding padding for them here
 * as well would double them, as it would on the Home screen.
 *
 * The screen title lives in the top bar only. The body leads with its state, so "Transactions"
 * is never printed twice.
 *
 * There is deliberately no back arrow. This is a top-level tab that the bottom bar already owns;
 * an arrow that navigated back to the tab it came from would be a control that does nothing.
 */
@Composable
fun TransactionsScreen(
    onAddTransaction: () -> Unit,
    onOpenTransaction: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TransactionsViewModel = viewModel(factory = TransactionsViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    TransactionsContent(
        uiState = uiState,
        onRetry = viewModel::retry,
        onQueryChange = viewModel::onQueryChange,
        onTypeFilterChange = viewModel::onTypeFilterChange,
        onMonthChange = viewModel::onMonthChange,
        onClearFilters = viewModel::clearFilters,
        onAddTransaction = onAddTransaction,
        onOpenTransaction = onOpenTransaction,
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
    onQueryChange: (String) -> Unit,
    onTypeFilterChange: (TransactionTypeFilter) -> Unit,
    onMonthChange: (YearMonth) -> Unit,
    onClearFilters: () -> Unit,
    onAddTransaction: () -> Unit,
    onOpenTransaction: (Long) -> Unit,
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

                is TransactionsUiState.Content -> ContentBody(
                    state = uiState,
                    onQueryChange = onQueryChange,
                    onTypeFilterChange = onTypeFilterChange,
                    onMonthChange = onMonthChange,
                    onClearFilters = onClearFilters,
                    onOpenTransaction = onOpenTransaction,
                )
            }

            // Offered in every state, including the empty one: recording the household's first
            // transaction is exactly what an empty ledger should invite. The shell's Scaffold
            // already insets the content for the bottom bar, so this clears it without extra work.
            FloatingActionButton(
                onClick = onAddTransaction,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.transactions_add),
                )
            }
        }
    }
}

/**
 * The loaded screen: the controls, then the rows they leave.
 *
 * The controls are hidden on a ledger with nothing in it, because there is nothing for them to act
 * on and three filters over a blank page invite the household to look for a problem that does not
 * exist. They stay put when a filter excludes every row, because there the message below them is
 * *about* them.
 */
@Composable
private fun ContentBody(
    state: TransactionsUiState.Content,
    onQueryChange: (String) -> Unit,
    onTypeFilterChange: (TransactionTypeFilter) -> Unit,
    onMonthChange: (YearMonth) -> Unit,
    onClearFilters: () -> Unit,
    onOpenTransaction: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (!state.ledgerIsEmpty) {
            TransactionsFilterBar(
                filters = state.filters,
                monthOptions = state.monthOptions,
                onQueryChange = onQueryChange,
                onTypeFilterChange = onTypeFilterChange,
                onMonthChange = onMonthChange,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when {
                state.ledgerIsEmpty -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    // Loaded successfully and there is nothing to show: a success, and a
                    // different thing from a failed read.
                    StatusMessage(
                        icon = Icons.AutoMirrored.Outlined.List,
                        title = stringResource(R.string.transactions_empty_title),
                        message = stringResource(R.string.transactions_empty_message),
                    )
                }

                state.groups.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    // A loaded ledger whose rows were all filtered out. Also a success - and the
                    // one empty screen that has to explain itself, because the household is looking
                    // at a blank page that their own search produced.
                    StatusMessage(
                        icon = Icons.Filled.Search,
                        title = stringResource(R.string.transactions_no_matches_title),
                        message = stringResource(R.string.transactions_no_matches_message),
                        accentColor = MaterialTheme.colorScheme.surfaceVariant,
                        accentContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        action = {
                            TextButton(onClick = onClearFilters) {
                                Text(text = stringResource(R.string.transactions_clear_filters))
                            }
                        },
                    )
                }

                else -> TransactionList(
                    groups = state.groups,
                    onOpenTransaction = onOpenTransaction,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** The search box and the three chips that decide what the list below shows. */
@Composable
private fun TransactionsFilterBar(
    filters: TransactionFilters,
    monthOptions: List<YearMonth>,
    onQueryChange: (String) -> Unit,
    onTypeFilterChange: (TransactionTypeFilter) -> Unit,
    onMonthChange: (YearMonth) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SearchField(
            query = filters.query,
            onQueryChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )

        FilterBar(
            filters = filters,
            monthOptions = monthOptions,
            onTypeFilterChange = onTypeFilterChange,
            onMonthChange = onMonthChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
    }
}

/**
 * The search box.
 *
 * A filled `TextField` rather than an outlined one: the field is a filter over the list, not a form
 * to be completed, and removing the box around it keeps it from competing with the rows for
 * attention. The clear button appears only when there is something to clear, so the trailing space
 * is not spent on a control that would do nothing.
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = { Text(text = stringResource(R.string.transactions_search_placeholder)) },
        leadingIcon = {
            Icon(imageVector = Icons.Filled.Search, contentDescription = null)
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.transactions_search_clear),
                    )
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.large,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

/**
 * The direction chips and the month selector, in one row that scrolls sideways.
 *
 * Scrolling rather than wrapping: at three chips plus a month label the row already overflows a
 * small phone in a large font, and a second line of filters that appears and disappears would move
 * the list every time the household changed device settings.
 */
@Composable
private fun FilterBar(
    filters: TransactionFilters,
    monthOptions: List<YearMonth>,
    onTypeFilterChange: (TransactionTypeFilter) -> Unit,
    onMonthChange: (YearMonth) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypeFilterChip(
            label = stringResource(R.string.transactions_filter_all),
            filter = TransactionTypeFilter.ALL,
            selected = filters.type,
            onTypeFilterChange = onTypeFilterChange,
        )
        TypeFilterChip(
            label = stringResource(R.string.transactions_filter_income),
            filter = TransactionTypeFilter.INCOME,
            selected = filters.type,
            onTypeFilterChange = onTypeFilterChange,
        )
        TypeFilterChip(
            label = stringResource(R.string.transactions_filter_expense),
            filter = TransactionTypeFilter.EXPENSE,
            selected = filters.type,
            onTypeFilterChange = onTypeFilterChange,
        )
        MonthFilterChip(
            month = filters.month,
            monthOptions = monthOptions,
            onMonthChange = onMonthChange,
        )
    }
}

@Composable
private fun TypeFilterChip(
    label: String,
    filter: TransactionTypeFilter,
    selected: TransactionTypeFilter,
    onTypeFilterChange: (TransactionTypeFilter) -> Unit,
) {
    FilterChip(
        selected = filter == selected,
        onClick = { onTypeFilterChange(filter) },
        label = { Text(text = label) },
    )
}

/**
 * The month the list is scoped to.
 *
 * The chip is never in the *selected* style: it is not a toggle but a value, and the value is
 * already written on it. It is drawn filled rather than outlined so it reads as a control that
 * belongs to the same group as the chips beside it, and the menu lists only months that have
 * entries - offering an empty month would be offering the household a blank page.
 */
@Composable
private fun MonthFilterChip(
    month: YearMonth,
    monthOptions: List<YearMonth>,
    onMonthChange: (YearMonth) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        FilterChip(
            selected = false,
            onClick = { expanded = true },
            label = { Text(text = DateFormatter.formatMonth(month)) },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = stringResource(R.string.transactions_filter_month_description),
                )
            },
            colors = FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                labelColor = MaterialTheme.colorScheme.onSurface,
            ),
        )

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            monthOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text = DateFormatter.formatMonth(option)) },
                    onClick = {
                        expanded = false
                        onMonthChange(option)
                    },
                )
            }
        }
    }
}

/**
 * The rows, newest day first.
 *
 * Each day is a heading followed by its own rows, all in one `LazyColumn` so a long ledger is
 * still virtualised - only the days on screen are composed. Rows are keyed by their own id and
 * headings by their date, so a change to one entry animates that row instead of every row below it
 * being treated as new.
 */
@Composable
private fun TransactionList(
    groups: List<TransactionDateGroup>,
    onOpenTransaction: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
    ) {
        groups.forEach { group ->
            item(key = "heading-${group.date}") {
                DateHeading(group = group)
            }
            items(items = group.items, key = { it.id }) { item ->
                TransactionRow(
                    item = item,
                    onClick = { onOpenTransaction(item.id) },
                )
            }
        }
    }
}

/** The day a run of rows belongs to, qualified as today or yesterday when it is one of them. */
@Composable
private fun DateHeading(
    group: TransactionDateGroup,
    modifier: Modifier = Modifier,
) {
    val date = DateFormatter.format(group.date)
    val label = when (group.relation) {
        DateRelation.TODAY -> stringResource(
            R.string.transactions_date_with_relation,
            date,
            stringResource(R.string.transactions_date_today),
        )

        DateRelation.YESTERDAY -> stringResource(
            R.string.transactions_date_with_relation,
            date,
            stringResource(R.string.transactions_date_yesterday),
        )

        DateRelation.EARLIER -> date
    }

    Text(
        text = label,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * One ledger entry, and the way into it.
 *
 * The mark is a circle holding the glyph for the category, and the text block takes the leftover
 * width (`weight(1f)`) while the amount keeps its intrinsic width - so a long category name or
 * description is ellipsised instead of pushing the amount off the row or wrapping it onto a second
 * line where it would read as a different number.
 *
 * The whole row is the control that opens the entry's details. It is one tap target the width of the
 * row rather than a chevron at its edge, because tapping a row to see more of it is what a list is
 * for, and a target that is the full row is easier to hit than a 24dp glyph. `Role.Button` is what
 * tells a screen reader the row can be activated; no `contentDescription` is added, because the row's
 * own words - category, description, amount - are the description, and replacing them would hide the
 * figures the household came to read.
 */
@Composable
private fun TransactionRow(
    item: TransactionListItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = item.description

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryMark(
            iconKey = item.categoryIconKey,
            categoryName = item.categoryName,
            type = item.type,
            size = 44.dp,
        )

        Spacer(modifier = Modifier.width(14.dp))

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
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = item.amountText(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = amountColorFor(item.type),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Centred icon, headline and supporting sentence, with an optional action underneath.
 *
 * Shared by the empty, error and no-matches states so they can never drift apart visually; only the
 * accent colours differ, because a failed read must not be dressed in the same reassuring green as
 * an empty ledger.
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
            Spacer(modifier = Modifier.height(16.dp))
            action()
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

private val PREVIEW_TODAY: LocalDate = LocalDate.of(2026, 10, 17)

@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun TransactionsContentPreview() {
    HomeFinanceTheme {
        TransactionsContent(
            uiState = TransactionsUiState.Content(
                groups = listOf(
                    TransactionDateGroup(
                        date = PREVIEW_TODAY,
                        relation = DateRelation.TODAY,
                        items = listOf(
                            TransactionListItem(
                                id = 1L,
                                categoryName = "Milk",
                                description = "1.5 litres",
                                transactionDate = PREVIEW_TODAY,
                                amountPaise = 9_000L,
                                type = TransactionType.EXPENSE,
                            ),
                            TransactionListItem(
                                id = 2L,
                                categoryName = "Groceries",
                                description = "Vegetables and fruits",
                                transactionDate = PREVIEW_TODAY,
                                amountPaise = 32_000L,
                                type = TransactionType.EXPENSE,
                            ),
                        ),
                    ),
                    TransactionDateGroup(
                        date = PREVIEW_TODAY.minusDays(2),
                        relation = DateRelation.EARLIER,
                        items = listOf(
                            TransactionListItem(
                                id = 3L,
                                categoryName = "Electricity bill",
                                description = "Online payment",
                                transactionDate = PREVIEW_TODAY.minusDays(2),
                                amountPaise = 180_000L,
                                type = TransactionType.EXPENSE,
                            ),
                        ),
                    ),
                ),
                filters = TransactionFilters(
                    query = "",
                    type = TransactionTypeFilter.ALL,
                    month = YearMonth.of(2026, 10),
                ),
                monthOptions = listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 9)),
                ledgerIsEmpty = false,
            ),
            onRetry = {},
            onQueryChange = {},
            onTypeFilterChange = {},
            onMonthChange = {},
            onClearFilters = {},
            onAddTransaction = {},
            onOpenTransaction = {},
        )
    }
}
