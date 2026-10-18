package com.homefinance.app.feature.recurring

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.homefinance.app.core.ui.component.StatusPane
import com.homefinance.app.core.ui.theme.HomeFinanceTheme
import com.homefinance.app.core.util.DateFormatter
import com.homefinance.app.data.model.RecurrenceFrequency
import com.homefinance.app.data.model.TransactionType
import com.homefinance.app.feature.addtransaction.CategoriesUiState
import com.homefinance.app.feature.addtransaction.CategoryField
import com.homefinance.app.feature.addtransaction.CategoryOption
import com.homefinance.app.feature.addtransaction.CategoryPickerContent
import com.homefinance.app.feature.addtransaction.DateField
import com.homefinance.app.feature.addtransaction.DatePickerSheet
import com.homefinance.app.feature.addtransaction.EditLoadState
import com.homefinance.app.feature.addtransaction.FieldLabel
import com.homefinance.app.feature.addtransaction.LabelledTextField
import com.homefinance.app.feature.addtransaction.SaveFooter
import java.time.LocalDate

/**
 * The form that creates a recurring expense.
 *
 * See [RecurringFormContent] for the body, and [EditRecurringScreen] for the same form changing an
 * existing schedule. The two are one screen because they ask for one set of facts; only the words and
 * the id differ.
 *
 * @param onBack the form was dismissed. Backing out of the *category picker* does not reach here - it
 *   returns to the form, because the picker is inside the same flow.
 * @param onSaved a schedule was written and the form is finished with.
 * @param onPickCategory the category field was tapped.
 */
@Composable
fun AddRecurringScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onPickCategory: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecurringFormViewModel = viewModel(factory = RecurringFormViewModel.factory()),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.savedScheduleId) {
        if (uiState.savedScheduleId != null) onSaved()
    }

    RecurringFormContent(
        uiState = uiState,
        title = stringResource(R.string.recurring_form_add_title),
        actionLabel = stringResource(R.string.recurring_form_save),
        progressLabel = stringResource(R.string.recurring_form_saving),
        onBack = onBack,
        onNameChange = viewModel::onNameChange,
        onDescriptionChange = viewModel::onDescriptionChange,
        onAmountChange = viewModel::onAmountChange,
        onPickCategory = onPickCategory,
        onFrequencyChange = viewModel::onFrequencyChange,
        onIntervalChange = viewModel::onIntervalChange,
        onStartDateChange = viewModel::onStartDateChange,
        onEndDateChange = viewModel::onEndDateChange,
        onSave = viewModel::save,
        onRetryEditLoad = viewModel::retryEditLoad,
        modifier = modifier,
    )
}

/**
 * The form that changes an existing recurring expense.
 *
 * The id is carried as an argument by the route rather than held anywhere else, so the screen reads the
 * schedule the household actually tapped. The form refuses to draw until that row has been read: showing
 * an empty form under an edit title, even for one frame, would invite the household to fill in figures
 * that were about to be overwritten by the row they came to change.
 */
@Composable
fun EditRecurringScreen(
    scheduleId: Long,
    onBack: () -> Unit,
    onFinished: () -> Unit,
    onPickCategory: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecurringFormViewModel = viewModel(
        factory = RecurringFormViewModel.factory(editingId = scheduleId),
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.savedScheduleId) {
        if (uiState.savedScheduleId != null) onFinished()
    }

    RecurringFormContent(
        uiState = uiState,
        title = stringResource(R.string.recurring_form_edit_title),
        actionLabel = stringResource(R.string.recurring_form_update),
        progressLabel = stringResource(R.string.recurring_form_saving),
        onBack = onBack,
        onNameChange = viewModel::onNameChange,
        onDescriptionChange = viewModel::onDescriptionChange,
        onAmountChange = viewModel::onAmountChange,
        onPickCategory = onPickCategory,
        onFrequencyChange = viewModel::onFrequencyChange,
        onIntervalChange = viewModel::onIntervalChange,
        onStartDateChange = viewModel::onStartDateChange,
        onEndDateChange = viewModel::onEndDateChange,
        onSave = viewModel::save,
        onRetryEditLoad = viewModel::retryEditLoad,
        modifier = modifier,
    )
}

/**
 * The category picker a recurring schedule opens.
 *
 * The **same** [CategoryPickerContent] the ledger's forms open, driven by the same kind of state, so a
 * household choosing the category for a bill is offered exactly the list, the ordering, the tick and the
 * "new category" dialog they already know. Its view model is the form's own, scoped to the form graph,
 * so a choice here *is* the form's choice and there is no result to pass back.
 *
 * The type is pinned to [TransactionType.EXPENSE]: a recurring schedule is an expense, so an income
 * category cannot be reached through this flow at all.
 */
@Composable
fun RecurringCategoryPickerScreen(
    onBack: () -> Unit,
    viewModel: RecurringFormViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    CategoryPickerContent(
        categories = uiState.categories,
        selectedId = uiState.category?.id,
        type = TransactionType.EXPENSE,
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
 * The form's body, taking its state, its wording and every action as parameters.
 *
 * Split out from the two screens so every state - a new schedule, a schedule being read, a failed read,
 * a schedule that has gone, and a failed save that a real database will not produce on demand - can be
 * rendered and asserted directly.
 *
 * ### Reading the schedule before drawing it
 *
 * When [RecurringFormUiState.editLoad] says the row is still being read, this draws a spinner and
 * nothing else - no fields, no save button. A form that appeared empty and then filled itself in would,
 * for that instant, be a form full of wrong figures, and the household might already be typing into it.
 *
 * ### Why the interval is a text field with a unit beside it
 *
 * "Repeat every" plus the plural of the chosen frequency ("Repeat every 2 months") says exactly what
 * `frequencyCount` plus `intervalCount` mean without either control having to explain the other. The
 * field itself is the only place in this form where a mistake is *expected* - everything else is picked
 * from a list - so it is also the only one that has to say "that is not a whole number" rather than
 * quietly storing something else.
 *
 * ### Why the start date is the anchor
 *
 * The day of the first occurrence is the day every later one falls on: a monthly schedule starting on
 * the 31st recurs on the last day of each month. There is deliberately no separate "day of month" list
 * to disagree with the start date - two controls for one fact is how a schedule ends up starting on the
 * 5th and being due on the 1st.
 *
 * ### Insets and the shell
 *
 * This flow draws no bottom bar - see [com.homefinance.app.navigation.HomeFinanceShell] - so the form
 * takes the whole screen and its Save sits at the foot of it. System-bar padding still belongs to the
 * shell's single `Scaffold`, and [HomeFinanceTopBar] zeroes its own insets.
 */
@Composable
internal fun RecurringFormContent(
    uiState: RecurringFormUiState,
    title: String,
    actionLabel: String,
    progressLabel: String,
    onBack: () -> Unit,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onPickCategory: () -> Unit,
    onFrequencyChange: (RecurrenceFrequency) -> Unit,
    onIntervalChange: (String) -> Unit,
    onStartDateChange: (LocalDate) -> Unit,
    onEndDateChange: (LocalDate?) -> Unit,
    onSave: () -> Unit,
    onRetryEditLoad: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        HomeFinanceTopBar(title = title, onNavigationClick = onBack)

        when (val load = uiState.editLoad) {
            EditLoadState.Loading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                RecurringLoadingSpinner()
            }

            is EditLoadState.Failed -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                StatusPane(
                    icon = Icons.Outlined.Warning,
                    title = stringResource(R.string.recurring_form_load_error_title),
                    message = stringResource(R.string.recurring_form_load_error_message),
                    accentColor = MaterialTheme.colorScheme.errorContainer,
                    accentContentColor = MaterialTheme.colorScheme.onErrorContainer,
                    action = {
                        Button(onClick = onRetryEditLoad) {
                            Text(text = stringResource(R.string.recurring_error_retry))
                        }
                    },
                )
            }

            EditLoadState.NotFound -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                StatusPane(
                    icon = Icons.Outlined.Warning,
                    title = stringResource(R.string.recurring_missing_title),
                    message = stringResource(R.string.recurring_missing_message),
                    accentColor = MaterialTheme.colorScheme.errorContainer,
                    accentContentColor = MaterialTheme.colorScheme.onErrorContainer,
                    action = {
                        Button(onClick = onBack) {
                            Text(text = stringResource(R.string.recurring_missing_action))
                        }
                    },
                )
            }

            EditLoadState.Ready -> FormBody(
                uiState = uiState,
                actionLabel = actionLabel,
                progressLabel = progressLabel,
                onNameChange = onNameChange,
                onDescriptionChange = onDescriptionChange,
                onAmountChange = onAmountChange,
                onPickCategory = onPickCategory,
                onFrequencyChange = onFrequencyChange,
                onIntervalChange = onIntervalChange,
                onStartDateChange = { date ->
                    onStartDateChange(date)
                    showStartPicker = false
                },
                onEndDateChange = { date ->
                    onEndDateChange(date)
                    showEndPicker = false
                },
                onOpenStartPicker = { showStartPicker = true },
                onOpenEndPicker = { showEndPicker = true },
                onClearEndDate = { onEndDateChange(null) },
                onSave = onSave,
            )
        }
    }

    if (showStartPicker) {
        DatePickerSheet(
            date = uiState.startDate,
            onDateSelected = onStartDateChange,
            onDismiss = { showStartPicker = false },
        )
    }

    if (showEndPicker) {
        DatePickerSheet(
            // The picker needs a day to open on; the first occurrence is the only sensible one, and
            // it is what the household is most likely to mean.
            date = uiState.endDate ?: uiState.startDate,
            onDateSelected = onEndDateChange,
            onDismiss = { showEndPicker = false },
        )
    }
}

/** The form itself, drawn only once the row being changed - if any - has been read. */
@Composable
private fun FormBody(
    uiState: RecurringFormUiState,
    actionLabel: String,
    progressLabel: String,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onPickCategory: () -> Unit,
    onFrequencyChange: (RecurrenceFrequency) -> Unit,
    onIntervalChange: (String) -> Unit,
    onStartDateChange: (LocalDate) -> Unit,
    onEndDateChange: (LocalDate?) -> Unit,
    onOpenStartPicker: () -> Unit,
    onOpenEndPicker: () -> Unit,
    onClearEndDate: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LabelledTextField(
                label = stringResource(R.string.recurring_form_name_label),
                value = uiState.name,
                onValueChange = onNameChange,
                placeholder = stringResource(R.string.recurring_form_name_placeholder),
                error = if (uiState.showNameError) {
                    stringResource(R.string.recurring_form_name_error)
                } else {
                    null
                },
            )

            LabelledTextField(
                label = stringResource(R.string.recurring_form_description_label),
                value = uiState.description,
                onValueChange = onDescriptionChange,
                placeholder = stringResource(R.string.recurring_form_description_placeholder),
                imeAction = ImeAction.Next,
            )

            CategoryField(
                category = uiState.category,
                type = TransactionType.EXPENSE,
                showError = uiState.showCategoryError,
                onClick = onPickCategory,
            )

            LabelledTextField(
                label = stringResource(R.string.recurring_form_amount_label),
                value = uiState.amountInput,
                onValueChange = onAmountChange,
                placeholder = stringResource(R.string.add_transaction_amount_placeholder),
                error = uiState.amountError?.let { stringResource(it.messageRes()) },
                keyboardType = KeyboardType.Decimal,
            )

            FrequencySelector(
                selected = uiState.frequency,
                onFrequencyChange = onFrequencyChange,
            )

            LabelledTextField(
                label = stringResource(
                    R.string.recurring_form_interval_label,
                    stringResource(uiState.frequency.unitRes()),
                ),
                value = uiState.intervalInput,
                onValueChange = onIntervalChange,
                placeholder = RecurringFormUiState.DEFAULT_INTERVAL_INPUT,
                error = uiState.intervalError?.let { stringResource(it.messageRes()) },
                keyboardType = KeyboardType.Number,
            )

            DateField(
                date = uiState.startDate,
                onClick = onOpenStartPicker,
                label = stringResource(R.string.recurring_start_label),
            )

            EndDateField(
                endDate = uiState.endDate,
                showError = uiState.showEndDateError,
                onClick = onOpenEndPicker,
                onClear = onClearEndDate,
            )
        }

        SaveFooter(
            saveError = uiState.saveError?.let { stringResource(it.messageRes()) },
            isSaving = uiState.isSaving,
            saved = uiState.savedScheduleId != null,
            actionLabel = actionLabel,
            progressLabel = progressLabel,
            onClick = onSave,
        )
    }
}

/**
 * How often the schedule repeats, as four chips.
 *
 * A grid of four rather than a dropdown: every option is a fact the household may want to compare, and a
 * menu would hide three of them behind a tap. Each chip is a real selectable control with a checkable
 * role, so a screen reader announces the choice rather than a row of coloured ovals.
 */
@Composable
private fun FrequencySelector(
    selected: RecurrenceFrequency,
    onFrequencyChange: (RecurrenceFrequency) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(text = stringResource(R.string.recurring_form_frequency_label))

        // Two rows of two, so every option is the same width whatever the label's length.
        listOf(
            listOf(RecurrenceFrequency.DAILY, RecurrenceFrequency.WEEKLY),
            listOf(RecurrenceFrequency.MONTHLY, RecurrenceFrequency.YEARLY),
        ).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { frequency ->
                    FilterChip(
                        selected = frequency == selected,
                        onClick = { onFrequencyChange(frequency) },
                        label = { Text(text = stringResource(frequency.labelRes())) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * The optional last occurrence.
 *
 * Its own composable rather than a use of [DateField] because it has one more state than a date field
 * has: *not set*, which is a real answer ("this repeats until it is paused") and not an empty date. It
 * shows that answer in words and offers a Clear action once a day has been chosen, because a household
 * who set a last occurrence by mistake needs a way to take it back that is not "pick a date far in the
 * future".
 */
@Composable
private fun EndDateField(
    endDate: LocalDate?,
    showError: Boolean,
    onClick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.recurring_end_optional_label)
    val shown = endDate?.let(DateFormatter::format)
        ?: stringResource(R.string.recurring_end_none)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(text = label)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = shown,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    isError = showError,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = if (!showError) {
                        null
                    } else {
                        { Text(text = stringResource(R.string.recurring_end_error)) }
                    },
                )

                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable(role = Role.Button, onClick = onClick)
                        .semantics { contentDescription = label },
                )
            }

            if (endDate != null) {
                TextButton(onClick = onClear) {
                    Text(text = stringResource(R.string.recurring_end_clear))
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun RecurringFormContentPreview() {
    HomeFinanceTheme {
        RecurringFormContent(
            uiState = RecurringFormUiState(
                name = "Shop rent",
                description = "Ground floor, paid by UPI",
                amountInput = "15000",
                category = CategoryOption(id = 1L, name = "Rent"),
                frequency = RecurrenceFrequency.MONTHLY,
                intervalInput = "1",
                startDate = LocalDate.of(2026, 11, 5),
                categories = CategoriesUiState.Ready(
                    options = listOf(CategoryOption(id = 1L, name = "Rent")),
                ),
            ),
            title = "New recurring expense",
            actionLabel = "Save schedule",
            progressLabel = "Saving...",
            onBack = {},
            onNameChange = {},
            onDescriptionChange = {},
            onAmountChange = {},
            onPickCategory = {},
            onFrequencyChange = {},
            onIntervalChange = {},
            onStartDateChange = {},
            onEndDateChange = {},
            onSave = {},
            onRetryEditLoad = {},
        )
    }
}
