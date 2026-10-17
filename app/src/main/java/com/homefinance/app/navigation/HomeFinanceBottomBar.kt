package com.homefinance.app.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy

/**
 * Material 3 bottom navigation for the four top-level destinations.
 *
 * @param currentDestination the destination currently on screen, used to highlight the
 *   selected tab. Null before the nav host has composed for the first time.
 */
@Composable
fun HomeFinanceBottomBar(
    destinations: List<TopLevelDestination>,
    currentDestination: NavDestination?,
    onDestinationSelected: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier) {
        destinations.forEach { destination ->
            val selected = currentDestination.isOn(destination)
            NavigationBarItem(
                selected = selected,
                onClick = { onDestinationSelected(destination) },
                icon = {
                    Icon(
                        imageVector = if (selected) {
                            destination.selectedIcon
                        } else {
                            destination.unselectedIcon
                        },
                        contentDescription = null,
                    )
                },
                label = { Text(text = stringResource(destination.labelRes)) },
            )
        }
    }
}

/**
 * True when [destination] is the tab currently shown, including while a detail destination
 * opened from that tab is on top of it. The `when` is exhaustive, so adding a tab cannot
 * silently lose its highlight state.
 *
 * It covers every route the app has, including the add-transaction flow that
 * [HomeFinanceShell] currently hides the bar on, so that the bar is correct on its own terms: it can
 * always answer which section is current, and it never has to rely on somebody else having decided
 * not to draw it.
 */
private fun NavDestination?.isOn(destination: TopLevelDestination): Boolean =
    this?.hierarchy?.any { navDestination ->
        when (destination) {
            TopLevelDestination.Home ->
                // Settings is opened from the Home toolbar, so Home stays highlighted while it
                // is on screen and the bar is never shown with no section selected.
                navDestination.hasRoute<HomeRoute>() || navDestination.hasRoute<SettingsRoute>()
            TopLevelDestination.Transactions ->
                // The add form and the category picker it opens are both reached from this tab, so
                // this tab is the one to name if either ever has the bar above it.
                navDestination.hasRoute<TransactionsRoute>() ||
                    navDestination.hasRoute<AddTransactionRoute>() ||
                    navDestination.hasRoute<CategoryPickerRoute>()
            TopLevelDestination.Recurring -> navDestination.hasRoute<RecurringRoute>()
            TopLevelDestination.Reports -> navDestination.hasRoute<ReportsRoute>()
        }
    } == true
