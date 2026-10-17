package com.homefinance.app.feature.addtransaction

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homefinance.app.R
import com.homefinance.app.core.ui.component.HomeFinanceTopBar
import com.homefinance.app.core.ui.theme.HomeFinanceTheme
import com.homefinance.app.core.util.DateConverter
import com.homefinance.app.core.util.DateFormatter
import com.homefinance.app.core.util.MoneyParseResult
import com.homefinance.app.data.model.TransactionType
import java.time.LocalDate

/**
 * The add-income / add-expense form.
 *
 * ### Where the data comes from
 *
 * The screen renders [AddTransactionViewModel.uiState] and nothing else. It never touches a
 * repository, never launches a coroutine and never queries anything while composing: the database
 * work happens in the view model, and the form is wired to it through lambdas so the states can be
 * rendered and asserted directly (see [AddTransactionContent]).
 *
 * ### Leaving the screen
 *
 * [onSaved] is called only once [AddTransactionUiState.savedTransactionId] exists, which is only
 * after the repository has written the row. The effect is keyed on that id, so a configuration
 * change re-runs it against the id the view model kept rather than losing the navigation.
 *
 * ### Insets and the shell
 *
 * The bottom bar and the system-bar padding belong to the application shell's single `Scaffold`,
 * and [HomeFinanceTopBar] zeroes its own insets, so nothing here adds padding for them.
 */
@Composable
fun AddTransactionScreen(
    initialType: TransactionType,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddTransactionViewModel = viewModel(
        factory = AddTransactionViewModel.factory(initialType),
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.savedTransactionId) {
        if (uiState.savedTransactionId != null) onSaved()
    }

    AddTransactionContent(
        uiState = uiState,
        onBack = onBack,
        onTypeChange = viewModel::onTypeChange,
        onAmountChange = viewModel::onAmountChange,
        onCategorySelected = viewModel::onCategorySelected,
        onDateChange = viewModel::onDateChange,
        onDescriptionChange = viewModel::onDescriptionChange,
        onSave = viewModel::save,
        onRetryCategories = viewModel::retryCategories,
        onOpenCategoryDialog = viewModel::openCategoryDialog,
        onCategoryNameChange = viewModel::onCategoryNameChange,
        onCreateCategory = viewModel::createCategory,
        onDismissCategoryDialog = viewModel::dismissCategoryDialog,
        modifier = modifier,
    )
}

/**
 * The form's body, taking its state and every action as parameters.
 *
 * Split out from [AddTransactionScreen] the same way [com.homefinance.app.feature.transactions]
 * splits its screen, so every state - including a failed category read and a failed save that a
 * real database will not produce on demand - can be rendered and asserted directly.
 */
@Composable
internal fun AddTransactionContent(
    uiState: AddTransactionUiState,
    onBack: () -> Unit,
    onTypeChange: (TransactionType) -> Unit,
    onAmountChange: (String) -> Unit,
    onCategorySelected: (Long) -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onSave: () -> Unit,
    onRetryCategories: () -> Unit,
    onOpenCategoryDialog: () -> Unit,
    onCategoryNameChange: (String) -> Unit,
    onCreateCategory: () -> Unit,
    onDismissCategoryDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDatePicker by remember { mutableStateOf(false) }

    val amountError = uiState.amountError
    val saveError = uiState.saveError
    val categoryDialog = uiState.categoryDialog

    Column(modifier = modifier.fillMaxSize()) {
        HomeFinanceTopBar(
            title = stringResource(
                when (uiState.type) {
                    TransactionType.INCOME -> R.string.add_transaction_title_income
                    TransactionType.EXPENSE -> R.string.add_transaction_title_expense
                },
            ),
            onNavigationClick = onBack,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TypeSelector(selected = uiState.type, onTypeChange = onTypeChange)

            AmountField(
                value = uiState.amountInput,
                error = amountError,
                onValueChange = onAmountChange,
            )

            CategorySection(
                categories = uiState.categories,
                selectedCategoryId = uiState.categoryId,
                showError = uiState.showCategoryError,
                onCategorySelected = onCategorySelected,
                onRetry = onRetryCategories,
                onAddCategory = onOpenCategoryDialog,
            )

            DateField(date = uiState.date, onClick = { showDatePicker = true })

            DescriptionField(value = uiState.description, onValueChange = onDescriptionChange)

            if (saveError != null) {
                Text(
                    text = stringResource(saveError.messageRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            SaveButton(
                isSaving = uiState.isSaving,
                saved = uiState.savedTransactionId != null,
                onClick = onSave,
            )
        }
    }

    if (showDatePicker) {
        DatePickerSheet(
            date = uiState.date,
            onDateSelected = onDateChange,
            onDismiss = { showDatePicker = false },
        )
    }

    if (categoryDialog != null) {
        AddCategoryDialog(
            dialog = categoryDialog,
            onNameChange = onCategoryNameChange,
            onConfirm = onCreateCategory,
            onDismiss = onDismissCategoryDialog,
        )
    }
}

/** Income or expense, chosen explicitly rather than inferred from the amount's sign. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeSelector(
    selected: TransactionType,
    onTypeChange: (TransactionType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.add_transaction_type_label),
            style = MaterialTheme.typography.titleSmall,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = selected == TransactionType.INCOME,
                onClick = { onTypeChange(TransactionType.INCOME) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                label = { Text(text = stringResource(R.string.add_transaction_type_income)) },
            )
            SegmentedButton(
                selected = selected == TransactionType.EXPENSE,
                onClick = { onTypeChange(TransactionType.EXPENSE) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                label = { Text(text = stringResource(R.string.add_transaction_type_expense)) },
            )
        }
    }
}

/**
 * The amount field.
 *
 * A decimal keyboard is asked for, and the rupee symbol is a prefix rather than part of the value,
 * so the number the household types is exactly the number [MoneyInput] parses - no symbol to strip
 * and no grouping separators to guess at. The error is shown under the field, in place, instead of
 * in a snackbar that could be missed.
 */
@Composable
private fun AmountField(
    value: String,
    error: MoneyParseResult.Problem?,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(text = stringResource(R.string.add_transaction_amount_label)) },
        prefix = { Text(text = stringResource(R.string.add_transaction_amount_currency_symbol)) },
        singleLine = true,
        isError = error != null,
        supportingText = if (error == null) {
            null
        } else {
            { Text(text = stringResource(error.messageRes())) }
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Next,
        ),
    )
}

/**
 * The category picker.
 *
 * Retired categories never reach here - the view model filters them out - so every chip is
 * something a new transaction may legitimately be filed under. When there is nothing to pick, the
 * form offers the one action that unblocks it rather than showing an empty row.
 */
@Composable
private fun CategorySection(
    categories: CategoriesUiState,
    selectedCategoryId: Long?,
    showError: Boolean,
    onCategorySelected: (Long) -> Unit,
    onRetry: () -> Unit,
    onAddCategory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.add_transaction_category_label),
            style = MaterialTheme.typography.titleSmall,
        )

        when (categories) {
            CategoriesUiState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.add_transaction_category_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is CategoriesUiState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.add_transaction_category_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onRetry) {
                    Text(text = stringResource(R.string.add_transaction_category_retry))
                }
            }

            is CategoriesUiState.Ready -> if (categories.options.isEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.add_transaction_category_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onAddCategory) {
                        Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.add_category_action))
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    categories.options.forEach { option ->
                        FilterChip(
                            selected = option.id == selectedCategoryId,
                            onClick = { onCategorySelected(option.id) },
                            label = { Text(text = option.name) },
                        )
                    }
                }
            }
        }

        if (showError) {
            Text(
                text = stringResource(R.string.add_transaction_category_required),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * The date, shown as a read-only field so it reads like the other inputs, with an invisible
 * clickable layer over it that opens the picker.
 *
 * A `readOnly` field is not clickable by itself, and marking the field itself clickable would fight
 * its own focus handling; an overlay is the standard recipe and keeps the tap target the whole
 * field. The overlay carries a button role and the "Date" description so a screen reader announces
 * it as an action rather than an edit box.
 */
@Composable
private fun DateField(
    date: LocalDate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.add_transaction_date_label)

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = DateFormatter.format(date),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(text = description) },
            trailingIcon = {
                Icon(imageVector = Icons.Outlined.DateRange, contentDescription = null)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description },
        )
    }
}

@Composable
private fun DescriptionField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(text = stringResource(R.string.add_transaction_description_label)) },
        minLines = 2,
        maxLines = 4,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    )
}

/**
 * The primary action.
 *
 * Disabled while a save is in flight and after one has landed ([saved]) - the *button* refusing a
 * second tap and [AddTransactionViewModel.save] refusing it as well, because a disabled button is a
 * hint to the user and a guard in the view model is a guarantee.
 */
@Composable
private fun SaveButton(
    isSaving: Boolean,
    saved: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = !isSaving && !saved,
        modifier = modifier.fillMaxWidth(),
    ) {
        if (isSaving) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = stringResource(R.string.add_transaction_saving))
        } else {
            Text(text = stringResource(R.string.add_transaction_save))
        }
    }
}

/**
 * The Material 3 date picker, wrapping the epoch-millisecond representation in [DateConverter] so
 * the calendar day cannot shift by one on a device's timezone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerSheet(
    date: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = DateConverter.toUtcEpochMillis(date),
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        onDateSelected(DateConverter.fromUtcEpochMillis(millis))
                    }
                    onDismiss()
                },
            ) {
                Text(text = stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    ) {
        DatePicker(state = pickerState)
    }
}

/**
 * The minimal "new category" dialog.
 *
 * It exists because a fresh install has no categories at all, which would leave the form with
 * nothing to file the household's first transaction under. It creates the category for whichever
 * type the form is showing and selects it, so the entry can be finished in one pass. Full category
 * management is a later task, not this one.
 */
@Composable
private fun AddCategoryDialog(
    dialog: CategoryDialogState,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val error = dialog.error

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.add_category_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = dialog.nameInput,
                    onValueChange = onNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = stringResource(R.string.add_category_name_label)) },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                if (error != null) {
                    Text(
                        text = stringResource(error.messageRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !dialog.isSaving) {
                Text(text = stringResource(R.string.add_category_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !dialog.isSaving) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}

/** Maps the reason an amount was refused to the sentence that explains it. */
@StringRes
private fun MoneyParseResult.Problem.messageRes(): Int = when (this) {
    MoneyParseResult.Problem.Blank -> R.string.add_transaction_amount_error_blank
    MoneyParseResult.Problem.NotANumber -> R.string.add_transaction_amount_error_not_a_number
    MoneyParseResult.Problem.TooManyDecimalPlaces -> R.string.add_transaction_amount_error_too_precise
    MoneyParseResult.Problem.NotPositive -> R.string.add_transaction_amount_error_not_positive
    MoneyParseResult.Problem.TooLarge -> R.string.add_transaction_amount_error_too_large
}

/** Maps the reason a save was refused to the sentence that explains it. */
@StringRes
private fun SaveError.messageRes(): Int = when (this) {
    SaveError.CategoryUnavailable -> R.string.add_transaction_save_error_category
    is SaveError.Failed -> R.string.add_transaction_save_error_generic
}

/** Maps the reason a category could not be created to the sentence that explains it. */
@StringRes
private fun CategoryDialogError.messageRes(): Int = when (this) {
    CategoryDialogError.BlankName -> R.string.add_category_error_blank
    CategoryDialogError.Duplicate -> R.string.add_category_error_duplicate
    is CategoryDialogError.Failed -> R.string.add_category_error_failed
}

@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun AddTransactionContentPreview() {
    HomeFinanceTheme {
        AddTransactionContent(
            uiState = AddTransactionUiState(
                type = TransactionType.EXPENSE,
                date = LocalDate.of(2026, 10, 15),
                amountInput = "250.50",
                categoryId = 2L,
                categories = CategoriesUiState.Ready(
                    options = listOf(
                        CategoryOption(id = 1L, name = "Electricity"),
                        CategoryOption(id = 2L, name = "Groceries"),
                        CategoryOption(id = 3L, name = "Milk"),
                    ),
                ),
            ),
            onBack = {},
            onTypeChange = {},
            onAmountChange = {},
            onCategorySelected = {},
            onDateChange = {},
            onDescriptionChange = {},
            onSave = {},
            onRetryCategories = {},
            onOpenCategoryDialog = {},
            onCategoryNameChange = {},
            onCreateCategory = {},
            onDismissCategoryDialog = {},
        )
    }
}
