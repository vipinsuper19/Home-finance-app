package com.homefinance.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homefinance.app.R
import com.homefinance.app.core.ui.component.SettingsDestructiveRow
import com.homefinance.app.core.ui.component.SettingsDivider
import com.homefinance.app.core.ui.component.SettingsFrame
import com.homefinance.app.core.ui.component.SettingsInfoRow
import com.homefinance.app.core.ui.component.SettingsNavRow
import com.homefinance.app.core.ui.component.SettingsNote
import com.homefinance.app.core.ui.component.SettingsSection

/**
 * The app-level screen: the version, the legal documents, and the one destructive action.
 *
 * "Clear app data" never runs from a row tap. The row opens a dialog that states what will be deleted,
 * and only the dialog's own confirm button performs the work - the same rule that protects a restore,
 * and deliberately *not* governed by the "Confirm before delete" switch, which is about one entry.
 */
@Composable
fun AppSettingsScreen(
    onBack: () -> Unit,
    onOpenLegal: (LegalDocument) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory()),
) {
    val clearState by viewModel.clearState.collectAsStateWithLifecycle()
    val event by viewModel.events.collectAsStateWithLifecycle()

    var showClearDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val message = event?.let { settingsMessage(it) }

    LaunchedEffect(event) {
        if (event != null) {
            if (event == SettingsEvent.DataCleared) showClearDialog = false
            if (message != null) snackbarHostState.showSnackbar(message)
            viewModel.onEventShown()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        SettingsFrame(title = stringResource(R.string.app_settings_title), onBack = onBack) {
            Text(
                text = stringResource(R.string.app_settings_intro),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SettingsSection(title = stringResource(R.string.settings_section_app)) {
                SettingsInfoRow(
                    label = stringResource(R.string.settings_app_version),
                    value = appVersionLabel(),
                )
                SettingsDivider()
                SettingsNavRow(
                    icon = Icons.Outlined.PrivacyTip,
                    title = stringResource(R.string.settings_privacy),
                    onClick = { onOpenLegal(LegalDocument.PRIVACY) },
                )
                SettingsDivider()
                SettingsNavRow(
                    icon = Icons.Outlined.Description,
                    title = stringResource(R.string.settings_terms),
                    onClick = { onOpenLegal(LegalDocument.TERMS) },
                )
                SettingsDivider()
                SettingsNavRow(
                    icon = Icons.AutoMirrored.Outlined.HelpOutline,
                    title = stringResource(R.string.settings_help),
                    onClick = { onOpenLegal(LegalDocument.HELP) },
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_data)) {
                SettingsDestructiveRow(
                    icon = Icons.Outlined.DeleteForever,
                    title = stringResource(R.string.settings_clear_data),
                    subtitle = stringResource(R.string.settings_clear_data_summary),
                    onClick = { showClearDialog = true },
                )
                SettingsDivider()
                SettingsNote(text = stringResource(R.string.settings_clear_keeps_categories_note))
            }

            SettingsNote(
                text = stringResource(R.string.app_settings_privacy_note),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (showClearDialog) {
        ClearDataDialog(
            state = clearState,
            onConfirm = viewModel::clearAppData,
            onDismiss = {
                if (!clearState.isWorking) {
                    showClearDialog = false
                    viewModel.dismissClearError()
                }
            },
        )
    }
}

@Composable
private fun ClearDataDialog(
    state: ClearDataState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.DeleteForever,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(text = stringResource(R.string.settings_clear_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = stringResource(R.string.settings_clear_dialog_message))
                Text(
                    text = stringResource(R.string.settings_clear_keeps_categories_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.hasFailed) {
                    Text(
                        text = stringResource(R.string.settings_clear_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !state.isWorking) {
                if (state.isWorking) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        text = stringResource(R.string.settings_clear_dialog_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.isWorking) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}

/** The snackbar copy for this screen. */
@Composable
private fun settingsMessage(event: SettingsEvent): String = when (event) {
    SettingsEvent.WriteFailed -> stringResource(R.string.settings_write_error)
    SettingsEvent.DataCleared -> stringResource(R.string.settings_clear_done)
    SettingsEvent.DataClearFailed -> stringResource(R.string.settings_clear_failed)
}

/** A shipped document, shown in full. */
@Composable
fun LegalScreen(
    document: LegalDocument,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsFrame(
        title = stringResource(document.titleRes),
        onBack = onBack,
        modifier = modifier,
    ) {
        SettingsSection(title = stringResource(document.titleRes)) {
            Text(
                text = stringResource(document.bodyRes),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        SettingsNote(text = stringResource(R.string.legal_in_app_note))
    }
}
