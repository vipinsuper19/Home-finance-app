package com.homefinance.app.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.ui.graphics.vector.ImageVector
import com.homefinance.app.R

/**
 * The four tabs of the bottom navigation bar.
 *
 * [route] is the type-safe route from [Routes] consumed by Navigation Compose; it is typed as
 * [Any] because each route is its own serializable object. Each tab carries both a filled and
 * an outlined icon so the selected tab is visually distinct.
 */
enum class TopLevelDestination(
    val route: Any,
    @StringRes val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    Home(
        route = HomeRoute,
        labelRes = R.string.title_home,
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home,
    ),
    Transactions(
        route = TransactionsRoute,
        labelRes = R.string.title_transactions,
        selectedIcon = Icons.AutoMirrored.Filled.List,
        unselectedIcon = Icons.AutoMirrored.Outlined.List,
    ),
    Recurring(
        route = RecurringRoute,
        labelRes = R.string.title_recurring,
        selectedIcon = Icons.Filled.Refresh,
        unselectedIcon = Icons.Outlined.Refresh,
    ),
    Reports(
        route = ReportsRoute,
        labelRes = R.string.title_reports,
        selectedIcon = Icons.Filled.DateRange,
        unselectedIcon = Icons.Outlined.DateRange,
    ),
}
