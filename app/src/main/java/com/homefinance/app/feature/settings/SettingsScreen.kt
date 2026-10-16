package com.homefinance.app.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.homefinance.app.R
import com.homefinance.app.core.ui.component.HomeFinanceTopBar
import com.homefinance.app.core.ui.component.PlaceholderContent

/**
 * Reached from the Home toolbar. Intentionally empty in Task 1.1: preferences such as
 * currency, categories and reminders arrive in a later task.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        HomeFinanceTopBar(
            title = stringResource(R.string.title_settings),
            onNavigationClick = onBack,
        )
        PlaceholderContent(
            icon = Icons.Outlined.Settings,
            // The screen title is already in the top bar; the body leads with the state.
            title = stringResource(R.string.placeholder_coming_soon),
            message = stringResource(R.string.settings_placeholder),
            modifier = Modifier.weight(1f),
        )
    }
}
