package com.homefinance.app.feature.addtransaction

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.homefinance.app.R

/**
 * The "new category" dialog.
 *
 * ### Why it lives beside the picker rather than in the form
 *
 * Filing an entry and inventing a place to file it are two different jobs, so the add form offers no
 * way to do the second one - it simply opens [CategoryPickerScreen], which is where categories are
 * listed and where one can be added. The dialog is still indispensable there, because a database
 * that starts empty is the normal case on a fresh install: without it there would be nothing at all
 * to file the household's first transaction under.
 *
 * The dialog creates a category for whichever type the form is currently showing, and selecting it is
 * part of creating it - see [AddTransactionViewModel.createCategory] - so an entry can be finished in
 * one pass.
 *
 * Split out from either screen because both the picker's top bar and its empty state open it, and
 * neither of them should own the copy.
 */
@Composable
internal fun AddCategoryDialog(
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

/** Maps the reason a category could not be created to the sentence that explains it. */
@StringRes
private fun CategoryDialogError.messageRes(): Int = when (this) {
    CategoryDialogError.BlankName -> R.string.add_category_error_blank
    CategoryDialogError.Duplicate -> R.string.add_category_error_duplicate
    is CategoryDialogError.Failed -> R.string.add_category_error_failed
}
