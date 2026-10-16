package com.homefinance.app.feature.recurring

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.homefinance.app.R
import com.homefinance.app.core.ui.component.HomeFinanceTopBar
import com.homefinance.app.core.ui.component.PlaceholderContent

/**
 * Bottom-navigation destination. Intentionally empty in Task 1.1: the recurring engine
 * (bills, subscriptions, milk deliveries) arrives in a later task.
 */
@Composable
fun RecurringScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        HomeFinanceTopBar(title = stringResource(R.string.title_recurring))
        PlaceholderContent(
            icon = Icons.Outlined.Refresh,
            title = stringResource(R.string.title_recurring),
            message = stringResource(R.string.recurring_placeholder),
            modifier = Modifier.weight(1f),
        )
    }
}
