package com.homefinance.app.feature.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homefinance.app.R
import com.homefinance.app.core.ui.component.SettingsChoiceRow
import com.homefinance.app.core.ui.component.SettingsDivider
import com.homefinance.app.core.ui.component.SettingsFrame
import com.homefinance.app.core.ui.component.SettingsNote
import com.homefinance.app.core.ui.component.SettingsSection
import com.homefinance.app.core.ui.component.StatusPane
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.TextButton
import com.homefinance.app.core.util.DateFormatStyle
import com.homefinance.app.core.util.DateFormatter
import com.homefinance.app.data.model.AppCurrency
import com.homefinance.app.data.model.AppLanguage
import com.homefinance.app.data.model.AppPreferences
import com.homefinance.app.core.ui.theme.ThemeMode
import java.time.LocalDate
import java.util.Locale

/**
 * Theme, Language, Currency and Date format, as four small screens sharing one view model.
 *
 * Each is a single choice from a short list, so each draws the same shape: an explanation, a card of
 * radio rows with the current value selected, and - where there is a limitation worth stating - a note
 * that says it plainly rather than leaving the household to discover it.
 */

@Composable
fun ThemeSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory()),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ThemeSettingsContent(
        uiState = uiState,
        onSelect = viewModel::setThemeMode,
        onRetry = viewModel::retry,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun ThemeSettingsContent(
    uiState: SettingsUiState,
    onSelect: (ThemeMode) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PreferenceFrame(
        title = stringResource(R.string.theme_title),
        intro = stringResource(R.string.theme_intro),
        uiState = uiState,
        onRetry = onRetry,
        onBack = onBack,
        modifier = modifier,
    ) { selected ->
        SettingsSection(title = stringResource(R.string.settings_theme)) {
            ThemeMode.entries.forEachIndexed { index, mode ->
                SettingsChoiceRow(
                    title = stringResource(themeLabel(mode)),
                    subtitle = stringResource(themeSummary(mode)),
                    selected = mode == selected.themeMode,
                    onClick = { onSelect(mode) },
                )
                if (index != ThemeMode.entries.lastIndex) SettingsDivider()
            }
        }
    }
}

@Composable
fun LanguageSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory()),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LanguageSettingsContent(
        uiState = uiState,
        onSelect = viewModel::setLanguage,
        onRetry = viewModel::retry,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun LanguageSettingsContent(
    uiState: SettingsUiState,
    onSelect: (AppLanguage) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PreferenceFrame(
        title = stringResource(R.string.language_title),
        intro = stringResource(R.string.language_intro),
        uiState = uiState,
        onRetry = onRetry,
        onBack = onBack,
        modifier = modifier,
    ) { selected ->
        SettingsSection(title = stringResource(R.string.settings_language)) {
            AppLanguage.entries.forEachIndexed { index, language ->
                SettingsChoiceRow(
                    title = language.displayName,
                    subtitle = stringResource(R.string.language_english_summary),
                    selected = language == selected.language,
                    onClick = { onSelect(language) },
                )
                if (index != AppLanguage.entries.lastIndex) SettingsDivider()
            }
        }
        SettingsNote(text = stringResource(R.string.language_supported_note))
    }
}

@Composable
fun CurrencySettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory()),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    CurrencySettingsContent(
        uiState = uiState,
        onSelect = viewModel::setCurrency,
        onRetry = viewModel::retry,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun CurrencySettingsContent(
    uiState: SettingsUiState,
    onSelect: (AppCurrency) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PreferenceFrame(
        title = stringResource(R.string.currency_title),
        intro = stringResource(R.string.currency_intro),
        uiState = uiState,
        onRetry = onRetry,
        onBack = onBack,
        modifier = modifier,
    ) { selected ->
        SettingsSection(title = stringResource(R.string.settings_currency)) {
            AppCurrency.entries.forEachIndexed { index, currency ->
                SettingsChoiceRow(
                    title = currency.displayName,
                    subtitle = "${currency.symbol} (${currency.code}) · " +
                        stringResource(R.string.currency_inr_summary),
                    selected = currency == selected.currency,
                    onClick = { onSelect(currency) },
                )
                if (index != AppCurrency.entries.lastIndex) SettingsDivider()
            }
        }
        SettingsNote(text = stringResource(R.string.currency_no_conversion_note))
    }
}

@Composable
fun DateFormatSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory()),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    DateFormatSettingsContent(
        uiState = uiState,
        onSelect = viewModel::setDateFormat,
        onRetry = viewModel::retry,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun DateFormatSettingsContent(
    uiState: SettingsUiState,
    onSelect: (DateFormatStyle) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    PreferenceFrame(
        title = stringResource(R.string.date_format_title),
        intro = stringResource(R.string.date_format_intro),
        uiState = uiState,
        onRetry = onRetry,
        onBack = onBack,
        modifier = modifier,
    ) { selected ->
        SettingsSection(title = stringResource(R.string.settings_date_format)) {
            DateFormatStyle.entries.forEachIndexed { index, style ->
                SettingsChoiceRow(
                    title = styleName(style),
                    subtitle = stringResource(
                        R.string.settings_value_date_format,
                        DateFormatter.format(today, style, Locale.ENGLISH),
                    ),
                    selected = style == selected.dateFormat,
                    onClick = { onSelect(style) },
                )
                if (index != DateFormatStyle.entries.lastIndex) SettingsDivider()
            }
        }
    }
}

/**
 * The shared frame of the four preference screens.
 *
 * It draws the title and explanation, then either the choice list (when the preferences are loaded) or
 * the loading / failure pane. Putting that branch here means each screen's body only has to describe
 * its own list.
 */
@Composable
private fun PreferenceFrame(
    title: String,
    intro: String,
    uiState: SettingsUiState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (AppPreferences) -> Unit,
) {
    SettingsFrame(title = title, onBack = onBack, modifier = modifier) {
        Text(
            text = intro,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (uiState) {
            SettingsUiState.Loading -> Text(
                text = stringResource(R.string.settings_loading),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            is SettingsUiState.Content -> content(uiState.preferences)
        }
    }
}

/** The one-line explanation under a theme option. */
@Composable
private fun themeSummary(mode: ThemeMode): Int = when (mode) {
    ThemeMode.LIGHT -> R.string.theme_light_summary
    ThemeMode.DARK -> R.string.theme_dark_summary
    ThemeMode.SYSTEM -> R.string.theme_system_summary
}

/** The name of a date layout; the row's subtitle shows it working on today's date. */
@Composable
private fun styleName(style: DateFormatStyle): String = when (style) {
    DateFormatStyle.DAY_MONTH_YEAR -> stringResource(R.string.date_format_name_day_month_year)
    DateFormatStyle.DAY_MONTH_NUMERIC -> stringResource(R.string.date_format_name_numeric)
    DateFormatStyle.ISO -> stringResource(R.string.date_format_name_iso)
}
