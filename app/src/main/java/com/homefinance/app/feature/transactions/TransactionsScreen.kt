package com.homefinance.app.feature.transactions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.homefinance.app.R
import com.homefinance.app.core.ui.component.HomeFinanceTopBar
import com.homefinance.app.core.ui.component.PlaceholderContent

/**
 * Bottom-navigation destination. Intentionally empty in Task 1.1: capturing and browsing
 * income and expenses arrives with the storage tasks.
 */
@Composable
fun TransactionsScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        HomeFinanceTopBar(title = stringResource(R.string.title_transactions))
        PlaceholderContent(
            icon = Icons.AutoMirrored.Outlined.List,
            // The screen title is already in the top bar; the body leads with the state.
            title = stringResource(R.string.placeholder_coming_soon),
            message = stringResource(R.string.transactions_placeholder),
            modifier = Modifier.weight(1f),
        )
    }
}
