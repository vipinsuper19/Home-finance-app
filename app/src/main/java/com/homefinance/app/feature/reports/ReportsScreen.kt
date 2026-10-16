package com.homefinance.app.feature.reports

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.homefinance.app.R
import com.homefinance.app.core.ui.component.HomeFinanceTopBar
import com.homefinance.app.core.ui.component.PlaceholderContent

/**
 * Bottom-navigation destination. Intentionally empty in Task 1.1: monthly summaries and
 * category breakdowns arrive once there is data to report on.
 */
@Composable
fun ReportsScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        HomeFinanceTopBar(title = stringResource(R.string.title_reports))
        PlaceholderContent(
            icon = Icons.Outlined.DateRange,
            title = stringResource(R.string.title_reports),
            message = stringResource(R.string.reports_placeholder),
            modifier = Modifier.weight(1f),
        )
    }
}
