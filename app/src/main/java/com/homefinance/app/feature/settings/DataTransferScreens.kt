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
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import com.homefinance.app.core.ui.component.SettingsActionRow
import com.homefinance.app.core.ui.component.SettingsFrame
import com.homefinance.app.core.ui.component.SettingsInfoRow
import com.homefinance.app.core.ui.component.SettingsNote
import com.homefinance.app.core.ui.component.SettingsSection
import com.homefinance.app.data.repository.ImportPlan
import java.time.LocalDate

/**
 * Export Data: writes the ledger as the documented CSV to a destination the household picks.
 *
 * The screen states the contract - columns, ISO dates, plain amounts, and the escaping rule - before
 * anything is written, because a file is only useful if the person who keeps it knows what is in it.
 */
@Composable
fun ExportDataScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ExportViewModel = viewModel(factory = ExportViewModel.factory()),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val createFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) viewModel.export(uri.toString())
    }

    val message = when (val current = uiState.message) {
        is ExportMessage.Done -> stringResource(R.string.export_done, current.rows)
        ExportMessage.Failed -> stringResource(R.string.export_failed)
        null -> null
    }

    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.onMessageShown()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        SettingsFrame(title = stringResource(R.string.export_title), onBack = onBack) {
            Text(
                text = stringResource(R.string.export_intro),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (uiState.isWorking) WorkingRow(text = stringResource(R.string.export_working))

            SettingsSection(title = stringResource(R.string.settings_section_data)) {
                SettingsActionRow(
                    icon = Icons.Outlined.UploadFile,
                    title = stringResource(R.string.export_action),
                    onClick = { createFile.launch(suggestedExportName(LocalDate.now())) },
                )
            }

            SettingsSection(title = stringResource(R.string.export_format_title)) {
                SettingsInfoRow(
                    label = stringResource(R.string.export_columns_title),
                    value = stringResource(R.string.export_columns_value),
                )
            }

            SettingsNote(text = stringResource(R.string.export_contract_note))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * Import Data: reads a CSV, validates every row, shows what would happen, and writes only on confirm.
 */
@Composable
fun ImportDataScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ImportViewModel = viewModel(factory = ImportViewModel.factory()),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showConfirmation by remember { mutableStateOf(false) }
    var showResult by remember { mutableStateOf<ImportResultMessage?>(null) }

    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.onFilePicked(uri.toString())
    }

    val snackbarMessage = when (uiState.message) {
        ImportMessage.Failed -> stringResource(R.string.import_failed)
        ImportMessage.ReadFailed -> stringResource(R.string.import_read_failed)
        is ImportMessage.Done, null -> null
    }

    LaunchedEffect(snackbarMessage) {
        if (snackbarMessage != null) {
            snackbarHostState.showSnackbar(snackbarMessage)
            viewModel.onMessageShown()
        }
    }

    LaunchedEffect(uiState.message) {
        val done = uiState.message as? ImportMessage.Done ?: return@LaunchedEffect
        showResult = ImportResultMessage(done.result.inserted, done.result.skippedDuplicates)
        viewModel.onMessageShown()
    }

    Box(modifier = modifier.fillMaxSize()) {
        SettingsFrame(title = stringResource(R.string.import_title), onBack = onBack) {
            Text(
                text = stringResource(R.string.import_intro),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (uiState.isWorking) WorkingRow(text = stringResource(R.string.import_reading))

            SettingsSection(title = stringResource(R.string.settings_section_data)) {
                SettingsActionRow(
                    icon = Icons.Outlined.Download,
                    title = stringResource(R.string.import_action),
                    onClick = {
                        pickFile.launch(
                            arrayOf(
                                "text/csv",
                                "text/comma-separated-values",
                                "text/plain",
                                "application/vnd.ms-excel",
                                "application/octet-stream",
                            ),
                        )
                    },
                )
            }

            val plan = uiState.plan
            when {
                plan?.unreadable != null -> SettingsNote(
                    text = plan.unreadable,
                    emphasised = true,
                )

                plan != null -> ImportPlanCard(
                    plan = plan,
                    onImport = { showConfirmation = true },
                )
            }

            SettingsNote(text = stringResource(R.string.import_duplicate_note))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    val plan = uiState.plan
    if (showConfirmation && plan != null) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text(text = stringResource(R.string.import_confirm_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.import_confirm_message,
                        plan.rows.size,
                        plan.duplicates,
                        plan.problems.size,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmation = false
                        viewModel.confirmImport()
                    },
                ) {
                    Text(text = stringResource(R.string.import_commit, plan.rows.size))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmation = false }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }

    showResult?.let { result ->
        AlertDialog(
            onDismissRequest = { showResult = null },
            title = { Text(text = stringResource(R.string.import_done_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.import_done_message,
                        result.inserted,
                        result.duplicates,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { showResult = null }) {
                    Text(text = stringResource(R.string.action_ok))
                }
            },
        )
    }
}

/** The preview card: what is ready, what is already there, and what cannot be imported. */
@Composable
private fun ImportPlanCard(
    plan: ImportPlan,
    onImport: () -> Unit,
) {
    SettingsSection(title = stringResource(R.string.import_summary_title)) {
        SettingsInfoRow(
            label = stringResource(R.string.import_ready),
            value = plan.rows.size.toString(),
        )
        if (plan.duplicates > 0) {
            SettingsInfoRow(
                label = stringResource(R.string.import_duplicates),
                value = plan.duplicates.toString(),
            )
        }
        if (plan.problems.isNotEmpty()) {
            SettingsInfoRow(
                label = stringResource(R.string.import_problems),
                value = plan.problems.size.toString(),
            )
        }
    }

    if (plan.problems.isNotEmpty()) {
        SettingsSection(title = stringResource(R.string.import_problems_title)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                plan.reportedProblems.forEach { problem ->
                    Text(
                        text = "• $problem",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (plan.rows.isEmpty()) {
        SettingsNote(text = stringResource(R.string.import_nothing))
    } else {
        Button(
            onClick = onImport,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        ) {
            Text(text = stringResource(R.string.import_commit, plan.rows.size))
        }
    }
}

/** The result of a completed import, held by the screen only long enough to be dismissed. */
private data class ImportResultMessage(val inserted: Int, val duplicates: Int)

/** A working indicator with the sentence that describes what is happening. */
@Composable
private fun WorkingRow(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The suggested export file name: sortable, dated, and recognisably this app's. */
internal fun suggestedExportName(today: LocalDate): String =
    "home-finance-transactions-$today.csv"
