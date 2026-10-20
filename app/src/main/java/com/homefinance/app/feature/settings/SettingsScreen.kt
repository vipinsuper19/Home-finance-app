package com.homefinance.app.feature.settings

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homefinance.app.R
import com.homefinance.app.core.ui.component.SettingsDivider
import com.homefinance.app.core.ui.component.SettingsFrame
import com.homefinance.app.core.ui.component.SettingsNavRow
import com.homefinance.app.core.ui.component.SettingsSection
import com.homefinance.app.core.ui.component.SettingsSwitchRow
import com.homefinance.app.core.ui.component.StatusPane
import com.homefinance.app.core.ui.theme.ThemeMode
import com.homefinance.app.data.model.AppPreferences

/**
 * The Settings dashboard.
 *
 * Its four groups mirror the shipped design: how the app looks, how its data is protected, the two
 * taxonomies the household owns, and the switches and destructive action that change behaviour.
 *
 * ### What is deliberately absent
 *
 * There is no profile header, because the app has no accounts and no profile screen to open. There
 * are no payment-reminder or daily-summary switches, because the app has no notification workflow to
 * drive them: a switch that stores a value nothing reads would be a fake control. Both are listed as
 * future work in the README.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenLanguage: () -> Unit,
    onOpenCurrency: () -> Unit,
    onOpenDateFormat: () -> Unit,
    onOpenBackupRestore: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenManageCategories: () -> Unit,
    onOpenRecurring: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory()),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val event by viewModel.events.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val message = event?.let { settingsEventText(it) }

    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.onEventShown()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        SettingsContent(
            uiState = uiState,
            onRetry = viewModel::retry,
            onOpenTheme = onOpenTheme,
            onOpenLanguage = onOpenLanguage,
            onOpenCurrency = onOpenCurrency,
            onOpenDateFormat = onOpenDateFormat,
            onOpenBackupRestore = onOpenBackupRestore,
            onOpenExport = onOpenExport,
            onOpenImport = onOpenImport,
            onOpenManageCategories = onOpenManageCategories,
            onOpenRecurring = onOpenRecurring,
            onOpenAppSettings = onOpenAppSettings,
            onShowBalanceChange = viewModel::setShowBalanceOnHome,
            onConfirmDeleteChange = viewModel::setConfirmBeforeDelete,
            onHapticsChange = viewModel::setHapticFeedback,
            onBack = onBack,
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** The dashboard's body, taking its state as a parameter so every state can be rendered directly. */
@Composable
internal fun SettingsContent(
    uiState: SettingsUiState,
    onRetry: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenLanguage: () -> Unit,
    onOpenCurrency: () -> Unit,
    onOpenDateFormat: () -> Unit,
    onOpenBackupRestore: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenManageCategories: () -> Unit,
    onOpenRecurring: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onShowBalanceChange: (Boolean) -> Unit,
    onConfirmDeleteChange: (Boolean) -> Unit,
    onHapticsChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsFrame(
        title = stringResource(R.string.title_settings),
        subtitle = stringResource(R.string.settings_subtitle),
        onBack = onBack,
        modifier = modifier,
    ) {
        when (uiState) {
            SettingsUiState.Loading -> LoadingRow()
            is SettingsUiState.Failed -> StatusPane(
                icon = Icons.Outlined.Settings,
                title = stringResource(R.string.settings_load_error),
                accentColor = MaterialTheme.colorScheme.errorContainer,
                accentContentColor = MaterialTheme.colorScheme.onErrorContainer,
                action = {
                    TextButton(onClick = onRetry) {
                        Text(text = stringResource(R.string.settings_retry))
                    }
                },
            )
            is SettingsUiState.Content -> Dashboard(
                preferences = uiState.preferences,
                onOpenTheme = onOpenTheme,
                onOpenLanguage = onOpenLanguage,
                onOpenCurrency = onOpenCurrency,
                onOpenDateFormat = onOpenDateFormat,
                onOpenBackupRestore = onOpenBackupRestore,
                onOpenExport = onOpenExport,
                onOpenImport = onOpenImport,
                onOpenManageCategories = onOpenManageCategories,
                onOpenRecurring = onOpenRecurring,
                onOpenAppSettings = onOpenAppSettings,
                onShowBalanceChange = onShowBalanceChange,
                onConfirmDeleteChange = onConfirmDeleteChange,
                onHapticsChange = onHapticsChange,
            )
        }
    }
}

@Composable
private fun LoadingRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(
            text = stringResource(R.string.settings_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Dashboard(
    preferences: AppPreferences,
    onOpenTheme: () -> Unit,
    onOpenLanguage: () -> Unit,
    onOpenCurrency: () -> Unit,
    onOpenDateFormat: () -> Unit,
    onOpenBackupRestore: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenManageCategories: () -> Unit,
    onOpenRecurring: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onShowBalanceChange: (Boolean) -> Unit,
    onConfirmDeleteChange: (Boolean) -> Unit,
    onHapticsChange: (Boolean) -> Unit,
) {
    SettingsSection(title = stringResource(R.string.settings_section_appearance)) {
        SettingsNavRow(
            icon = Icons.Outlined.Palette,
            title = stringResource(R.string.settings_theme),
            value = stringResource(themeLabel(preferences.themeMode)),
            onClick = onOpenTheme,
        )
        SettingsDivider()
        SettingsNavRow(
            icon = Icons.Outlined.Translate,
            title = stringResource(R.string.settings_language),
            value = preferences.language.displayName,
            onClick = onOpenLanguage,
        )
        SettingsDivider()
        SettingsNavRow(
            icon = Icons.Outlined.Payments,
            title = stringResource(R.string.settings_currency),
            value = stringResource(
                R.string.settings_value_currency,
                preferences.currency.displayName,
                preferences.currency.code,
            ),
            onClick = onOpenCurrency,
        )
        SettingsDivider()
        SettingsNavRow(
            icon = Icons.Outlined.Event,
            title = stringResource(R.string.settings_date_format),
            value = dateFormatLabel(preferences),
            onClick = onOpenDateFormat,
        )
    }

    SettingsSection(title = stringResource(R.string.settings_section_data)) {
        SettingsNavRow(
            icon = Icons.Outlined.Backup,
            title = stringResource(R.string.settings_backup_restore),
            value = stringResource(R.string.settings_backup_restore_summary),
            onClick = onOpenBackupRestore,
        )
        SettingsDivider()
        SettingsNavRow(
            icon = Icons.Outlined.UploadFile,
            title = stringResource(R.string.settings_export_data),
            value = stringResource(R.string.settings_export_data_summary),
            onClick = onOpenExport,
        )
        SettingsDivider()
        SettingsNavRow(
            icon = Icons.Outlined.Download,
            title = stringResource(R.string.settings_import_data),
            value = stringResource(R.string.settings_import_data_summary),
            onClick = onOpenImport,
        )
    }

    SettingsSection(title = stringResource(R.string.settings_section_manage)) {
        SettingsNavRow(
            icon = Icons.Outlined.Category,
            title = stringResource(R.string.settings_manage_categories),
            value = stringResource(R.string.settings_manage_categories_summary),
            onClick = onOpenManageCategories,
        )
        SettingsDivider()
        SettingsNavRow(
            icon = Icons.Outlined.Repeat,
            title = stringResource(R.string.settings_manage_recurring),
            value = stringResource(R.string.settings_manage_recurring_summary),
            onClick = onOpenRecurring,
        )
    }

    SettingsSection(title = stringResource(R.string.settings_section_app)) {
        SettingsSwitchRow(
            icon = Icons.Outlined.VisibilityOff,
            title = stringResource(R.string.settings_show_balance),
            subtitle = stringResource(R.string.settings_show_balance_summary),
            checked = preferences.showBalanceOnHome,
            onCheckedChange = onShowBalanceChange,
        )
        SettingsDivider()
        SettingsSwitchRow(
            icon = Icons.Outlined.DeleteOutline,
            title = stringResource(R.string.settings_confirm_delete),
            subtitle = stringResource(R.string.settings_confirm_delete_summary),
            checked = preferences.confirmBeforeDelete,
            onCheckedChange = onConfirmDeleteChange,
        )
        SettingsDivider()
        SettingsSwitchRow(
            icon = Icons.Outlined.Vibration,
            title = stringResource(R.string.settings_haptics),
            subtitle = stringResource(R.string.settings_haptics_summary),
            checked = preferences.hapticFeedback,
            onCheckedChange = onHapticsChange,
        )
        SettingsDivider()
        SettingsNavRow(
            icon = Icons.Outlined.Settings,
            title = stringResource(R.string.app_settings_title),
            value = appVersionLabel(),
            onClick = onOpenAppSettings,
        )
    }
}

/** Maps a theme mode to its label. */
internal fun themeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.LIGHT -> R.string.settings_value_light
    ThemeMode.DARK -> R.string.settings_value_dark
    ThemeMode.SYSTEM -> R.string.settings_value_system
}

/**
 * The date-format row's value: a worked example in the chosen layout, using today's date.
 *
 * Showing the layout rather than its name ("Day month year") is the only description that needs no
 * translation in the reader's head - the household sees exactly what a date will look like.
 */
@Composable
internal fun dateFormatLabel(preferences: AppPreferences): String {
    val preview = com.homefinance.app.core.util.DateFormatter.format(
        date = java.time.LocalDate.now(),
        style = preferences.dateFormat,
    )
    return stringResource(R.string.settings_value_date_format, preview)
}

/** The app's real version name and code, read from the installed package. */
@Composable
internal fun appVersionLabel(): String {
    val context = LocalContext.current

    return remember(context) {
        runCatching {
            val manager = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                manager.getPackageInfo(context.packageName, 0)
            }

            val name = info.versionName ?: "1.0"
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "$name ($code)"
        }.getOrElse {
            // Reading the package cannot normally fail; if it does, say nothing rather than invent a
            // version number.
            ""
        }
    }
}

/** The snackbar copy for a one-off settings outcome. */
@Composable
private fun settingsEventText(event: SettingsEvent): String = when (event) {
    SettingsEvent.WriteFailed -> stringResource(R.string.settings_write_error)
    SettingsEvent.DataCleared -> stringResource(R.string.settings_clear_done)
    SettingsEvent.DataClearFailed -> stringResource(R.string.settings_clear_failed)
}
