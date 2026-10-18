package com.homefinance.app.feature.addtransaction

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homefinance.app.R
import com.homefinance.app.core.ui.component.HomeFinanceTopBar
import com.homefinance.app.core.ui.component.StatusPane
import com.homefinance.app.core.ui.theme.HomeFinanceTheme
import com.homefinance.app.data.model.TransactionType

/**
 * The category picker: every active category of the form's current type, and the one place a
 * category can be created.
 *
 * ### Why it is a screen of its own
 *
 * A household can have a dozen categories and the form is already six fields long. Listing them
 * inline - as chips, or in a dropdown - made the form about category management as much as about the
 * entry being recorded. Here the list has the whole screen, it can grow, and the "add" action has an
 * obvious home.
 *
 * ### What it shares with the form
 *
 * Both screens render the *same* [AddTransactionViewModel], scoped to the graph the two of them
 * live in - see [com.homefinance.app.navigation.HomeFinanceNavHost]. That is what makes the choice
 * on this screen the form's choice with nothing passed back through navigation: choosing calls
 * [AddTransactionViewModel.onCategorySelected] and pops, and the form re-renders from the state it
 * already had. It also means the list offered here is by construction the list the form's selection
 * came from.
 *
 * ### It never invents a category
 *
 * The list is whatever the database holds for the form's type, minus the retired ones. Opening this
 * screen must never write anything, so a household that changes their mind and backs out leaves the
 * database exactly as it was.
 *
 * ### Insets and the shell
 *
 * As everywhere else, the system-bar padding belongs to the application shell's single `Scaffold` and
 * [HomeFinanceTopBar] zeroes its own insets. There is no bottom bar under this screen either, because
 * it is part of the same full-screen flow as the form it returns to.
 */
@Composable
fun CategoryPickerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddTransactionViewModel = viewModel(
        factory = AddTransactionViewModel.factory(TransactionType.EXPENSE),
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    CategoryPickerContent(
        categories = uiState.categories,
        selectedId = uiState.category?.id,
        type = uiState.type,
        dialog = uiState.categoryDialog,
        onCategorySelected = { categoryId ->
            viewModel.onCategorySelected(categoryId)
            onBack()
        },
        onAddCategory = viewModel::openCategoryDialog,
        onCategoryNameChange = viewModel::onCategoryNameChange,
        onCreateCategory = viewModel::createCategory,
        onDismissDialog = viewModel::dismissCategoryDialog,
        onRetry = viewModel::retryCategories,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * The picker's body, taking exactly the facts it draws and every action as parameters.
 *
 * Split out from [CategoryPickerScreen] so each of the four states the list can be in - loading,
 * failed, empty, and populated - can be rendered and asserted directly, including the failure a real
 * database will not produce on demand.
 *
 * The parameters are primitives rather than a whole form state so that the *same* picker can serve a
 * second kind of form. A recurring schedule picks an expense category exactly as a transaction does,
 * and a picker that only understood `AddTransactionUiState` would have to be written a second time -
 * which is how two pickers end up disagreeing about which categories may be offered.
 *
 * @param categories the list as it stands, including its loading and failed states.
 * @param selectedId the id of the chosen category, so its row can show the tick, or `null`.
 * @param type whether income or expense categories are being chosen; it only decides the marks' colour
 *   and is never used to filter, because the list handed in is already filtered.
 * @param dialog the "new category" dialog's state, or `null` when it is closed.
 */
@Composable
internal fun CategoryPickerContent(
    categories: CategoriesUiState,
    selectedId: Long?,
    type: TransactionType,
    dialog: CategoryDialogState?,
    onCategorySelected: (Long) -> Unit,
    onAddCategory: () -> Unit,
    onCategoryNameChange: (String) -> Unit,
    onCreateCategory: () -> Unit,
    onDismissDialog: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        HomeFinanceTopBar(
            title = stringResource(R.string.category_picker_title),
            onNavigationClick = onBack,
            actions = {
                IconButton(onClick = onAddCategory) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.add_category_action),
                    )
                }
            },
        )

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (categories) {
                CategoriesUiState.Loading -> StatusPane(
                    icon = Icons.AutoMirrored.Outlined.List,
                    title = stringResource(R.string.category_picker_loading),
                    message = null,
                    modifier = Modifier.align(Alignment.Center),
                )

                is CategoriesUiState.Failed -> StatusPane(
                    icon = Icons.Outlined.Warning,
                    title = stringResource(R.string.category_picker_failed),
                    message = null,
                    accentColor = MaterialTheme.colorScheme.errorContainer,
                    accentContentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.align(Alignment.Center),
                    action = {
                        TextButton(onClick = onRetry) {
                            Text(text = stringResource(R.string.category_picker_retry))
                        }
                    },
                )

                is CategoriesUiState.Ready ->
                    if (categories.options.isEmpty()) {
                        StatusPane(
                            icon = Icons.Filled.Add,
                            title = stringResource(R.string.category_picker_empty),
                            message = null,
                            modifier = Modifier.align(Alignment.Center),
                            action = {
                                Button(
                                    onClick = onAddCategory,
                                    shape = MaterialTheme.shapes.large,
                                ) {
                                    Text(text = stringResource(R.string.category_picker_empty_action))
                                }
                            },
                        )
                    } else {
                        CategoryList(
                            options = categories.options,
                            selectedId = selectedId,
                            type = type,
                            onCategorySelected = onCategorySelected,
                        )
                    }
            }
        }
    }

    if (dialog != null) {
        AddCategoryDialog(
            dialog = dialog,
            onNameChange = onCategoryNameChange,
            onConfirm = onCreateCategory,
            onDismiss = onDismissDialog,
        )
    }
}

/**
 * The categories, alphabetically as the DAO returns them, with the chosen one ticked.
 *
 * The rows are `selectable` within a `selectableGroup`, the same construction the form's type pills
 * use: that is what tells a screen reader this is one choice among several rather than a list of
 * buttons, and it is what carries "selected" to someone who cannot see the tick. The tick is
 * therefore decorative - the semantics already say it - and the selection is never carried by colour
 * alone.
 */
@Composable
private fun CategoryList(
    options: List<CategoryOption>,
    selectedId: Long?,
    type: TransactionType,
    onCategorySelected: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .selectableGroup(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(items = options, key = { it.id }) { option ->
            CategoryRow(
                option = option,
                selected = option.id == selectedId,
                type = type,
                onClick = { onCategorySelected(option.id) },
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 68.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/** One category: its mark, its name, and whether it is the chosen one. */
@Composable
private fun CategoryRow(
    option: CategoryOption,
    selected: Boolean,
    type: TransactionType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryMark(categoryName = option.name, type = type)

        Spacer(modifier = Modifier.width(16.dp))

        Text(
            text = option.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun CategoryPickerContentPreview() {
    HomeFinanceTheme {
        CategoryPickerContent(
            categories = CategoriesUiState.Ready(
                options = listOf(
                    CategoryOption(id = 1L, name = "Electricity"),
                    CategoryOption(id = 2L, name = "Groceries"),
                    CategoryOption(id = 3L, name = "Milk"),
                ),
            ),
            selectedId = 2L,
            type = TransactionType.EXPENSE,
            dialog = null,
            onCategorySelected = {},
            onAddCategory = {},
            onCategoryNameChange = {},
            onCreateCategory = {},
            onDismissDialog = {},
            onRetry = {},
            onBack = {},
        )
    }
}
