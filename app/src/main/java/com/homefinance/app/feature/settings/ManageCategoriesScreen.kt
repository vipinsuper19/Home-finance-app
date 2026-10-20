package com.homefinance.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homefinance.app.R
import com.homefinance.app.core.ui.component.SettingsDivider
import com.homefinance.app.core.ui.component.SettingsFrame
import com.homefinance.app.core.ui.component.SettingsNote
import com.homefinance.app.core.ui.component.SettingsSection
import com.homefinance.app.core.ui.component.StatusPane
import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.feature.addtransaction.CategoryMark

/**
 * The category management screen: rename, retire and restore.
 *
 * It reuses the same category mark the ledger and the picker draw, so a category looks the same here
 * as it does where the household actually meets it. Retired categories are listed rather than hidden,
 * because they are still attached to history and the household may want one back.
 */
@Composable
fun ManageCategoriesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ManageCategoriesViewModel = viewModel(factory = ManageCategoriesViewModel.factory()),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val renameDialog by viewModel.renameDialog.collectAsStateWithLifecycle()
    val actionFailed by viewModel.actionFailed.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val failureMessage = stringResource(R.string.manage_categories_error_failed)

    LaunchedEffect(actionFailed) {
        if (actionFailed) {
            snackbarHostState.showSnackbar(failureMessage)
            viewModel.onActionFailureShown()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        ManageCategoriesContent(
            uiState = uiState,
            onRename = viewModel::onRenameRequested,
            onSetActive = viewModel::setCategoryActive,
            onRetry = viewModel::retry,
            onBack = onBack,
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    val dialog = renameDialog
    if (dialog != null) {
        RenameCategoryDialog(
            dialog = dialog,
            onNameChange = viewModel::onNameChange,
            onConfirm = viewModel::onRenameConfirm,
            onDismiss = viewModel::onRenameDismiss,
        )
    }
}

@Composable
internal fun ManageCategoriesContent(
    uiState: ManageCategoriesUiState,
    onRename: (CategoryEntity) -> Unit,
    onSetActive: (CategoryEntity, Boolean) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsFrame(
        title = stringResource(R.string.manage_categories_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.manage_categories_intro),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (uiState) {
            ManageCategoriesUiState.Loading -> Text(
                text = stringResource(R.string.manage_categories_loading),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is ManageCategoriesUiState.Failed -> StatusPane(
                icon = Icons.Outlined.Category,
                title = stringResource(R.string.manage_categories_load_error),
                accentColor = MaterialTheme.colorScheme.errorContainer,
                accentContentColor = MaterialTheme.colorScheme.onErrorContainer,
                action = {
                    TextButton(onClick = onRetry) {
                        Text(text = stringResource(R.string.settings_retry))
                    }
                },
            )

            is ManageCategoriesUiState.Content -> {
                if (uiState.isEmpty) {
                    StatusPane(
                        icon = Icons.Outlined.Category,
                        title = stringResource(R.string.manage_categories_empty),
                    )
                } else {
                    CategoryGroup(
                        title = stringResource(R.string.manage_categories_expense),
                        categories = uiState.expense,
                        onRename = onRename,
                        onSetActive = onSetActive,
                    )
                    CategoryGroup(
                        title = stringResource(R.string.manage_categories_income),
                        categories = uiState.income,
                        onRename = onRename,
                        onSetActive = onSetActive,
                    )
                    SettingsNote(text = stringResource(R.string.manage_categories_tap_note))
                }
            }
        }
    }
}

@Composable
private fun CategoryGroup(
    title: String,
    categories: List<CategoryEntity>,
    onRename: (CategoryEntity) -> Unit,
    onSetActive: (CategoryEntity, Boolean) -> Unit,
) {
    if (categories.isEmpty()) return

    SettingsSection(title = title) {
        categories.forEachIndexed { index, category ->
            CategoryRow(
                category = category,
                onRename = { onRename(category) },
                onSetActive = { active -> onSetActive(category, active) },
            )
            if (index != categories.lastIndex) SettingsDivider()
        }
    }
}

@Composable
private fun CategoryRow(
    category: CategoryEntity,
    onRename: () -> Unit,
    onSetActive: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRename)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryMark(
            iconKey = category.iconKey,
            categoryName = category.name,
            type = category.type,
        )

        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = category.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (category.isActive) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = stringResource(
                    if (category.isActive) {
                        R.string.manage_categories_active
                    } else {
                        R.string.manage_categories_retired
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TextButton(onClick = { onSetActive(!category.isActive) }) {
            Text(
                text = stringResource(
                    if (category.isActive) {
                        R.string.manage_categories_retire_action
                    } else {
                        R.string.manage_categories_restore_action
                    },
                ),
            )
        }
    }
}

@Composable
private fun RenameCategoryDialog(
    dialog: RenameCategoryDialogState,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.manage_categories_rename_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = dialog.nameInput,
                    onValueChange = onNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = stringResource(R.string.manage_categories_name_label)) },
                    singleLine = true,
                    isError = dialog.error != null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                if (dialog.error != null) {
                    Text(
                        text = stringResource(
                            when (dialog.error) {
                                RenameCategoryError.Blank -> R.string.manage_categories_error_blank
                                RenameCategoryError.Duplicate -> R.string.manage_categories_error_duplicate
                                RenameCategoryError.Failed -> R.string.manage_categories_error_failed
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !dialog.isSaving) {
                Text(text = stringResource(R.string.settings_action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !dialog.isSaving) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}
