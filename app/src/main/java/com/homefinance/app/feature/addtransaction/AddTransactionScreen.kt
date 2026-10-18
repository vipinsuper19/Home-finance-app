package com.homefinance.app.feature.addtransaction

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homefinance.app.R
import com.homefinance.app.core.ui.component.HomeFinanceTopBar
import com.homefinance.app.core.ui.theme.HomeFinanceTheme
import com.homefinance.app.core.util.CurrencyFormatter
import com.homefinance.app.core.util.DateConverter
import com.homefinance.app.core.util.DateFormatter
import com.homefinance.app.core.util.MoneyParseResult
import com.homefinance.app.core.util.QuantityParseResult
import com.homefinance.app.data.model.TransactionType
import java.time.LocalDate

/**
 * The form that records an income or an expense.
 *
 * This is the add half of the form; [EditTransactionScreen] is the other, and both draw the same
 * [AddTransactionContent].
 *
 * ### Where the data comes from
 *
 * The screen renders [AddTransactionViewModel.uiState] and nothing else. It never touches a
 * repository, never launches a coroutine and never queries anything while composing: the database
 * work happens in the view model, and the form is wired to it through lambdas so the states can be
 * rendered and asserted directly (see [AddTransactionContent]).
 *
 * ### The category is chosen on another screen
 *
 * This form lists nothing and offers no way to create a category. The Category row is a single
 * control that opens [CategoryPickerScreen], which reads the categories from the database and is the
 * one place a category can be created. Keeping the two apart is what stops a form with six fields
 * from also being a category manager, and it is why the row can simply *show* the chosen category -
 * the view model remembers the choice, not the form.
 *
 * ### The amount, or a quantity and a rate
 *
 * The switch records the same entry two ways. With it off, the amount is typed. With it on, the
 * amount is *derived* from a quantity and a rate, shown in the same field but read-only, so the
 * household always sees the figure that will actually be saved - and every message about it lives
 * under it.
 *
 * ### Leaving the screen
 *
 * [onSaved] is called only once [AddTransactionUiState.savedTransactionId] exists, which is only
 * after the repository has written the row. The effect is keyed on that id, so a configuration
 * change re-runs it against the id the view model kept rather than losing the navigation.
 *
 * ### Insets and the shell
 *
 * This is one of the two flows the shell draws no bottom bar for - the other is
 * [EditTransactionScreen] - see [com.homefinance.app.navigation.HomeFinanceShell] - so the form takes
 * the whole screen and its Save sits at the foot of it. System-bar padding still belongs to the
 * shell's single `Scaffold`, and [HomeFinanceTopBar] zeroes its own insets, so nothing here adds
 * padding for them.
 */
@Composable
fun AddTransactionScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onPickCategory: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddTransactionViewModel = viewModel(
        factory = AddTransactionViewModel.factory(TransactionType.EXPENSE),
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.savedTransactionId) {
        if (uiState.savedTransactionId != null) onSaved()
    }

    AddTransactionContent(
        uiState = uiState,
        title = stringResource(R.string.add_transaction_title),
        actionLabel = stringResource(R.string.add_transaction_save),
        progressLabel = stringResource(R.string.add_transaction_saving),
        onBack = onBack,
        onTypeChange = viewModel::onTypeChange,
        onAmountChange = viewModel::onAmountChange,
        onPickCategory = onPickCategory,
        onDateChange = viewModel::onDateChange,
        onDescriptionChange = viewModel::onDescriptionChange,
        onNotesChange = viewModel::onNotesChange,
        onQuantityAndRateToggled = viewModel::onQuantityAndRateToggled,
        onQuantityChange = viewModel::onQuantityChange,
        onRateChange = viewModel::onRateChange,
        onSave = viewModel::save,
        modifier = modifier,
    )
}

/**
 * The form's body, taking its state, its wording and every action as parameters.
 *
 * Split out from [AddTransactionScreen] the same way [com.homefinance.app.feature.transactions]
 * splits its screen, so every state - including a failed save that a real database will not produce
 * on demand - can be rendered and asserted directly.
 *
 * The three labels are arguments rather than constants because the same body is both screens: it
 * records a new entry and it changes an existing one, and only the words around it differ. Passing
 * them in keeps the body free of any notion of which job it is doing, which is what makes it the
 * same form in both cases rather than two forms that look alike.
 */
@Composable
internal fun AddTransactionContent(
    uiState: AddTransactionUiState,
    title: String,
    actionLabel: String,
    progressLabel: String,
    onBack: () -> Unit,
    onTypeChange: (TransactionType) -> Unit,
    onAmountChange: (String) -> Unit,
    onPickCategory: () -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onQuantityAndRateToggled: (Boolean) -> Unit,
    onQuantityChange: (String) -> Unit,
    onRateChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDatePicker by remember { mutableStateOf(false) }

    val amountError = uiState.amountError
    val saveError = uiState.saveError
    val amountIsDerived = uiState.useQuantityAndRate

    Column(modifier = modifier.fillMaxSize()) {
        HomeFinanceTopBar(
            title = title,
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
                value = uiState.amountFieldText(),
                derived = amountIsDerived,
                error = amountError?.let { amountErrorMessage(it, amountIsDerived) },
                onValueChange = onAmountChange,
            )

            CategoryField(
                category = uiState.category,
                type = uiState.type,
                showError = uiState.showCategoryError,
                onClick = onPickCategory,
            )

            LabelledTextField(
                label = stringResource(R.string.add_transaction_description_label),
                value = uiState.description,
                onValueChange = onDescriptionChange,
                placeholder = stringResource(R.string.add_transaction_description_placeholder),
                minLines = 2,
                maxLines = 3,
                imeAction = ImeAction.Done,
            )

            DateField(date = uiState.date, onClick = { showDatePicker = true })

            QuantityAndRate(
                useQuantityAndRate = uiState.useQuantityAndRate,
                quantityInput = uiState.quantityInput,
                quantityError = uiState.quantityError,
                rateInput = uiState.rateInput,
                rateError = uiState.rateError,
                onToggle = onQuantityAndRateToggled,
                onQuantityChange = onQuantityChange,
                onRateChange = onRateChange,
            )

            LabelledTextField(
                label = stringResource(R.string.add_transaction_notes_label),
                value = uiState.notes,
                onValueChange = onNotesChange,
                placeholder = stringResource(R.string.add_transaction_notes_placeholder),
                minLines = 2,
                maxLines = 3,
                imeAction = ImeAction.Done,
            )
        }

        SaveFooter(
            saveError = saveError?.let { stringResource(it.messageRes()) },
            isSaving = uiState.isSaving,
            saved = uiState.savedTransactionId != null,
            actionLabel = actionLabel,
            progressLabel = progressLabel,
            onClick = onSave,
        )
    }

    if (showDatePicker) {
        DatePickerSheet(
            date = uiState.date,
            onDateSelected = onDateChange,
            onDismiss = { showDatePicker = false },
        )
    }
}

/**
 * The Save action, held at the foot of the screen rather than left at the end of the form.
 *
 * Turning the quantity-and-rate switch on makes the form taller than most screens, and a primary
 * action that has to be scrolled to is a primary action the household will not find. Holding it here
 * also keeps the complaint that follows a refused save - "your amount is not a whole number of
 * paise", "you have not chosen a category" - beside the button that caused it, instead of somewhere
 * above that has already scrolled out of sight.
 *
 * When the form does fit, this sits exactly where the design puts it, as the last thing on the
 * screen.
 */
@Composable
internal fun SaveFooter(
    saveError: String?,
    isSaving: Boolean,
    saved: Boolean,
    actionLabel: String,
    progressLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (saveError != null) {
            Text(
                text = saveError,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        SaveButton(
            isSaving = isSaving,
            saved = saved,
            actionLabel = actionLabel,
            progressLabel = progressLabel,
            onClick = onClick,
        )
    }
}

/**
 * The text the amount field shows.
 *
 * When the amount is derived it is formatted here rather than stored pre-formatted, matching how the
 * ledger renders its figures - and it is shown with the shared formatter, so the preview and the
 * saved row cannot disagree about what a crore looks like. A product that rounds away to nothing
 * shows as an empty field rather than as `₹0.00`, because nothing is what would be saved.
 */
private fun AddTransactionUiState.amountFieldText(): String =
    if (useQuantityAndRate) {
        derivedAmountPaise?.takeIf { it > 0L }?.let(CurrencyFormatter::formatInr).orEmpty()
    } else {
        amountInput
    }

/**
 * Income or expense, chosen explicitly rather than inferred from the amount's sign.
 *
 * Two pills rather than a switch: the household is choosing one of exactly two things, and each one
 * carries its own colour - emerald for money in, red for money out - so the answer is readable before
 * the label is. The colours are the theme's own `primary` and `error`, the same two the ledger uses
 * for its amounts.
 *
 * The pills are `selectable` within a `selectableGroup` rather than a plain row of clickable boxes:
 * that is what tells a screen reader this is one choice with two options instead of two buttons.
 */
@Composable
private fun TypeSelector(
    selected: TransactionType,
    onTypeChange: (TransactionType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TypePill(
            label = stringResource(R.string.add_transaction_type_expense),
            selected = selected == TransactionType.EXPENSE,
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
            onClick = { onTypeChange(TransactionType.EXPENSE) },
            modifier = Modifier.weight(1f),
        )
        TypePill(
            label = stringResource(R.string.add_transaction_type_income),
            selected = selected == TransactionType.INCOME,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            onClick = { onTypeChange(TransactionType.INCOME) },
            modifier = Modifier.weight(1f),
        )
    }
}

/** One half of [TypeSelector]: filled in its own colour when chosen, grey when not. */
@Composable
private fun TypePill(
    label: String,
    selected: Boolean,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (selected) containerColor else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) contentColor else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.height(48.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * The amount.
 *
 * A filled field rather than an outlined one, and larger than the rest: it is the figure the whole
 * screen exists to record, and it is the only field whose value can be *computed* rather than typed.
 * The rupee symbol is part of the label, so the number in the field is exactly the number
 * [MoneyInput] parses - no symbol to strip and no grouping separators to guess at.
 *
 * The error is shown under the field, in place, instead of in a snackbar that could be missed.
 * [derived] only changes where the value comes from and whether it can be edited; the field, and
 * therefore the thing the household is looking at, is the same either way.
 */
@Composable
private fun AmountField(
    value: String,
    derived: Boolean,
    error: String?,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.add_transaction_amount_label)
    val amountTextStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(text = label)

        TextField(
            value = value,
            onValueChange = onValueChange,
            // The label above is a sibling, so it has to be named here as well for a screen reader
            // to know which box the figure belongs to.
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = label },
            placeholder = {
                Text(
                    text = stringResource(R.string.add_transaction_amount_placeholder),
                    style = amountTextStyle,
                )
            },
            textStyle = amountTextStyle,
            singleLine = true,
            readOnly = derived,
            isError = error != null,
            supportingText = if (error == null) null else { { Text(text = error) } },
            shape = MaterialTheme.shapes.large,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Next,
            ),
            colors = TextFieldDefaults.colors(
                // The container keeps its colour in every state so the field does not announce a
                // problem by changing shape: the sentence underneath is what says what is wrong.
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                errorContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                errorIndicatorColor = Color.Transparent,
            ),
        )
    }
}

/**
 * The single Category control: a mark, a name and a chevron, opening the picker.
 *
 * It is deliberately not a chip list. A household can have twenty categories and one is being
 * chosen, so the row states *what* was chosen and the list is asked for on its own screen - where it
 * can scroll, be searched by eye, and be added to.
 *
 * The row stays colourless about whether anything is chosen yet: an empty row says "Select category"
 * in the same muted tone the other fields' placeholders use, and the red border and sentence appear
 * only when a save was refused for want of one.
 */
@Composable
internal fun CategoryField(
    category: CategoryOption?,
    type: TransactionType,
    showError: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(text = stringResource(R.string.add_transaction_category_label))

        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                width = 1.dp,
                color = if (showError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (category != null) {
                    CategoryMark(categoryName = category.name, type = type)
                    Spacer(modifier = Modifier.width(12.dp))
                }

                Text(
                    text = category?.name
                        ?: stringResource(R.string.add_transaction_category_placeholder),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (category == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (showError) {
            Text(
                text = stringResource(R.string.add_transaction_category_required),
                style = MaterialTheme.typography.bodySmall,
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
 *
 * @param label what the field is called. It defaults to the ledger's "Date" because that is the only
 *   date a transaction has; a recurring schedule has two, so it names them itself.
 */
@Composable
internal fun DateField(
    date: LocalDate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.add_transaction_date_label),
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(text = label)

        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = DateFormatter.format(date),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.DateRange,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(role = Role.Button, onClick = onClick)
                    .semantics { contentDescription = label },
            )
        }
    }
}

/**
 * The optional "quantity and rate" mode: one switch, and the two fields it reveals directly beneath
 * it.
 *
 * The fields appear next to the switch that asked for them rather than up beside the amount, because
 * that is where the household is looking when they turn it on. The amount they work out to is shown
 * where the amount always is, so the two are never both editable and never both silent.
 */
@Composable
private fun QuantityAndRate(
    useQuantityAndRate: Boolean,
    quantityInput: String,
    quantityError: QuantityParseResult.Problem?,
    rateInput: String,
    rateError: MoneyParseResult.Problem?,
    onToggle: (Boolean) -> Unit,
    onQuantityChange: (String) -> Unit,
    onRateChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(checked = useQuantityAndRate, onCheckedChange = onToggle)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.add_transaction_quantity_rate_switch),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
        }

        if (useQuantityAndRate) {
            LabelledTextField(
                label = stringResource(R.string.add_transaction_quantity_label),
                value = quantityInput,
                onValueChange = onQuantityChange,
                placeholder = stringResource(R.string.add_transaction_quantity_placeholder),
                error = quantityError?.let { stringResource(it.messageRes()) },
                keyboardType = KeyboardType.Decimal,
            )

            LabelledTextField(
                label = stringResource(R.string.add_transaction_rate_label),
                value = rateInput,
                onValueChange = onRateChange,
                placeholder = stringResource(R.string.add_transaction_rate_placeholder),
                error = rateError?.let { stringResource(it.messageRes()) },
                keyboardType = KeyboardType.Decimal,
            )
        }
    }
}

/** A bold caption above a field, so the label survives the placeholder being typed over. */
@Composable
internal fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * An outlined field with its label above it.
 *
 * The label is a sibling rather than the field's own floating label: the fields here carry
 * placeholders that say what to type (`e.g. Vegetables, Fruits`) as well as a name, and a floating
 * label would replace one with the other as soon as something was typed.
 *
 * The label is also set as the field's `contentDescription`, because a sibling caption is *not* part
 * of the field as far as a screen reader is concerned: without this the field would announce its
 * value and nothing else, leaving the household to guess which box they are in.
 */
@Composable
internal fun LabelledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(text = label)

        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = label },
            placeholder = { Text(text = placeholder) },
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            isError = error != null,
            supportingText = if (error == null) null else { { Text(text = error) } },
            shape = MaterialTheme.shapes.large,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        )
    }
}

/**
 * The primary action.
 *
 * Disabled while a save is in flight and after one has landed ([saved]) - the *button* refusing a
 * second tap and [AddTransactionViewModel.save] refusing it as well, because a disabled button is a
 * hint to the user and a guard in the view model is a guarantee.
 *
 * Its wording is an argument for the same reason the title is: recording says "Save Transaction" and
 * changing one says "Update Transaction", and neither screen should have to know the other's words.
 */
@Composable
internal fun SaveButton(
    isSaving: Boolean,
    saved: Boolean,
    actionLabel: String,
    progressLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = !isSaving && !saved,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        if (isSaving) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = progressLabel)
        } else {
            Text(text = actionLabel)
        }
    }
}

/**
 * The Material 3 date picker, wrapping the epoch-millisecond representation in [DateConverter] so
 * the calendar day cannot shift by one on a device's timezone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatePickerSheet(
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
 * The sentence under the amount field.
 *
 * A typed amount explains itself with its own reason. A *derived* one can only fail in two ways -
 * the product is too small to be money, or too large to be stored - so it gets copy that names the
 * two figures the household actually typed rather than talking about an "amount" they did not type.
 */
@Composable
private fun amountErrorMessage(error: MoneyParseResult.Problem, derived: Boolean): String = when {
    !derived -> stringResource(error.messageRes())
    error == MoneyParseResult.Problem.NotPositive ->
        stringResource(R.string.add_transaction_amount_error_derived_too_small)

    else -> stringResource(R.string.add_transaction_amount_error_derived_too_large)
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

/**
 * Maps the reason a quantity was refused to the sentence that explains it.
 *
 * The rate uses the *amount* sentences: a rate is money per unit, and its rules - two decimal places,
 * positive, and so on - are the amount's rules exactly.
 */
@StringRes
private fun QuantityParseResult.Problem.messageRes(): Int = when (this) {
    QuantityParseResult.Problem.Blank -> R.string.add_transaction_quantity_error_blank
    QuantityParseResult.Problem.NotANumber -> R.string.add_transaction_quantity_error_not_a_number
    QuantityParseResult.Problem.TooManyDecimalPlaces ->
        R.string.add_transaction_quantity_error_too_precise

    QuantityParseResult.Problem.NotPositive -> R.string.add_transaction_quantity_error_not_positive
    QuantityParseResult.Problem.TooLarge -> R.string.add_transaction_quantity_error_too_large
}

/** Maps the reason a save was refused to the sentence that explains it. */
@StringRes
private fun SaveError.messageRes(): Int = when (this) {
    SaveError.CategoryUnavailable -> R.string.add_transaction_save_error_category
    SaveError.TransactionMissing -> R.string.add_transaction_save_error_missing
    is SaveError.Failed -> R.string.add_transaction_save_error_generic
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
                category = CategoryOption(id = 2L, name = "Groceries"),
                description = "Vegetables",
                notes = "Bought at the Sunday market",
            ),
            title = "Add Transaction",
            actionLabel = "Save Transaction",
            progressLabel = "Saving...",
            onBack = {},
            onTypeChange = {},
            onAmountChange = {},
            onPickCategory = {},
            onDateChange = {},
            onDescriptionChange = {},
            onNotesChange = {},
            onQuantityAndRateToggled = {},
            onQuantityChange = {},
            onRateChange = {},
            onSave = {},
        )
    }
}
