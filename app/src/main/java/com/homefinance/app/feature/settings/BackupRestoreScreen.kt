package com.homefinance.app.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homefinance.app.R
import com.homefinance.app.core.ui.component.SettingsActionRow
import com.homefinance.app.core.ui.component.SettingsDivider
import com.homefinance.app.core.ui.component.SettingsFrame
import com.homefinance.app.core.ui.component.SettingsInfoRow
import com.homefinance.app.core.ui.component.SettingsNote
import com.homefinance.app.core.ui.component.SettingsSection
import com.homefinance.app.core.util.DateFormatter
import com.homefinance.app.data.model.AppPreferences
import com.homefinance.app.data.repository.RestoreInspection
import com.homefinance.app.data.repository.RestoreSummary
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Backup & Restore, wired to the Storage Access Framework.
 *
 * A backup is written to a destination the household picks; the app never invents a path and never
 * asks for a storage permission. A restore is read, validated and summarised first, and replaces the
 * data only after the confirmation dialog names what will be replaced.
 *
 * The screen reports only facts it has: the last backup's real time and size, and the rows a restore
 * actually installed. Automatic backups are stated as unavailable rather than shown as a switch with
 * nothing behind it.
 */
@Composable
fun BackupRestoreScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupRestoreViewModel = viewModel(factory = BackupRestoreViewModel.factory()),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val appVersion = appVersionLabel()

    val createBackup = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) viewModel.createBackup(uri.toString(), appVersion)
    }

    val pickRestore = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.onRestoreFilePicked(uri.toString())
    }

    val snackbarMessage = when (val message = uiState.message) {
        BackupMessage.Created -> stringResource(R.string.backup_created)
        BackupMessage.CreateFailed -> stringResource(R.string.backup_create_failed)
        BackupMessage.ReadFailed -> stringResource(R.string.backup_read_failed)
        BackupMessage.RestoreFailed -> stringResource(R.string.backup_restore_failed)
        is BackupMessage.Rejected, is BackupMessage.Restored, null -> null
    }

    LaunchedEffect(snackbarMessage) {
        if (snackbarMessage != null) {
            snackbarHostState.showSnackbar(snackbarMessage)
            viewModel.onMessageShown()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        BackupRestoreContent(
            preferences = preferences,
            isWorking = uiState.isWorking,
            workingLabel = when (uiState.work) {
                BackupWork.Reading -> stringResource(R.string.backup_reading)
                BackupWork.Restoring -> stringResource(R.string.backup_restoring)
                BackupWork.Creating, null -> stringResource(R.string.backup_creating)
            },
            onBack = onBack,
            onCreateBackup = {
                createBackup.launch(suggestedBackupName(LocalDate.now()))
            },
            onRestoreBackup = {
                pickRestore.launch(
                    arrayOf("application/json", "application/octet-stream", "text/plain"),
                )
            },
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    uiState.pendingRestore?.let { pending ->
        RestoreConfirmationDialog(
            inspection = pending,
            onConfirm = viewModel::confirmRestore,
            onDismiss = viewModel::dismissRestore,
        )
    }

    when (val message = uiState.message) {
        is BackupMessage.Rejected -> ProblemDialog(
            title = stringResource(R.string.backup_restore_rejected_title),
            lines = message.errors,
            onDismiss = viewModel::onMessageShown,
        )
        is BackupMessage.Restored -> RestoredDialog(
            summary = message.summary,
            onDismiss = viewModel::onMessageShown,
        )
        else -> Unit
    }
}

@Composable
internal fun BackupRestoreContent(
    preferences: AppPreferences,
    isWorking: Boolean,
    workingLabel: String,
    onBack: () -> Unit,
    onCreateBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsFrame(
        title = stringResource(R.string.backup_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.backup_intro),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (isWorking) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    text = workingLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp))
        }

        SettingsSection(title = stringResource(R.string.backup_info_title)) {
            SettingsInfoRow(
                label = stringResource(R.string.backup_last_backup),
                value = preferences.lastBackupAt?.let { at ->
                    DateFormatter.formatTimestamp(at)
                } ?: stringResource(R.string.backup_never),
            )
            if (preferences.lastBackupBytes != null) {
                SettingsDivider()
                SettingsInfoRow(
                    label = stringResource(R.string.backup_size),
                    value = backupSizeLabel(preferences.lastBackupBytes),
                )
            }
            SettingsDivider()
            SettingsInfoRow(
                label = stringResource(R.string.backup_includes),
                value = stringResource(R.string.backup_includes_value),
            )
        }

        SettingsSection(title = stringResource(R.string.settings_section_data)) {
            SettingsActionRow(
                icon = Icons.Outlined.Backup,
                title = stringResource(R.string.backup_create),
                subtitle = stringResource(R.string.backup_create_summary),
                onClick = onCreateBackup,
            )
            SettingsDivider()
            SettingsActionRow(
                icon = Icons.Outlined.Restore,
                title = stringResource(R.string.backup_restore),
                subtitle = stringResource(R.string.backup_restore_summary),
                onClick = onRestoreBackup,
            )
        }

        SettingsNote(text = stringResource(R.string.backup_auto_unavailable))
    }
}

@Composable
private fun RestoreConfirmationDialog(
    inspection: RestoreInspection.Valid,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val summary = inspection.summary

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(text = stringResource(R.string.backup_restore_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = stringResource(R.string.backup_restore_confirm_message))
                Text(
                    text = stringResource(
                        R.string.backup_restore_confirm_summary,
                        summary.transactions,
                        summary.categories,
                        summary.schedules,
                        summary.occurrences,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.backup_restore_action),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun RestoredDialog(
    summary: RestoreSummary,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.backup_restore_done_title)) },
        text = {
            Text(
                text = stringResource(
                    R.string.backup_restore_done_message,
                    summary.transactions,
                    summary.categories,
                    summary.schedules,
                    summary.occurrences,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_ok))
            }
        },
    )
}

@Composable
private fun ProblemDialog(
    title: String,
    lines: List<String>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lines.take(MAX_SHOWN_PROBLEMS).forEach { line ->
                    Text(text = "• $line", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_ok))
            }
        },
    )
}

/** The suggested file name: sortable, dated, and recognisably this app's. */
internal fun suggestedBackupName(today: LocalDate): String = "home-finance-backup-$today.json"

/**
 * A readable size for a backup file, from the exact byte count the screen recorded.
 *
 * Kilobytes and megabytes are one-decimal strings rounded half-up from `BigDecimal`, never `Float`, for
 * the same reason money never is: a size is a number the household reads, and it must not print
 * `0.30000000000000004 KB`.
 */
@Composable
private fun backupSizeLabel(bytes: Long): String = when {
    bytes < BYTES_PER_KILOBYTE -> stringResource(R.string.backup_size_bytes, bytes)
    bytes < BYTES_PER_MEGABYTE -> stringResource(
        R.string.backup_size_kilobytes,
        BigDecimal(bytes).divide(BigDecimal(BYTES_PER_KILOBYTE), 1, RoundingMode.HALF_UP).toPlainString(),
    )
    else -> stringResource(
        R.string.backup_size_megabytes,
        BigDecimal(bytes).divide(BigDecimal(BYTES_PER_MEGABYTE), 1, RoundingMode.HALF_UP).toPlainString(),
    )
}

private const val MAX_SHOWN_PROBLEMS = 8
private const val BYTES_PER_KILOBYTE = 1024L
private const val BYTES_PER_MEGABYTE = 1024L * 1024L
