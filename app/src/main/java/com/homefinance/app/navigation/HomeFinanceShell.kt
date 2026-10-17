package com.homefinance.app.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

/**
 * The application shell: a Material 3 scaffold holding the bottom navigation bar and the
 * navigation host.
 *
 * System-bar insets are consumed here, once, so that feature screens never have to deal with
 * them (their top app bar carries a back arrow where it needs one, and zeroes the same insets).
 */
@Composable
fun HomeFinanceShell(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // The bar is left off the add-transaction flow entirely - see [showsBottomBar] - and
            // when it is missing the scaffold's own padding falls back to the system bar inset, so
            // the form still stops above the navigation bar rather than under it.
            if (currentDestination.showsBottomBar()) {
                HomeFinanceBottomBar(
                    destinations = TopLevelDestination.entries,
                    currentDestination = currentDestination,
                    onDestinationSelected = navController::switchTo,
                )
            }
        },
    ) { innerPadding ->
        HomeFinanceNavHost(
            navController = navController,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

/**
 * True when the bottom bar belongs on screen for [this] destination.
 *
 * The add-transaction flow is the one part of the app that takes the whole screen. Recording an
 * entry is a single uninterrupted piece of work with its own Save at the bottom of it, and offering
 * four other places to go halfway through is the one thing that screen should not do; the design it
 * was built from has no navigation bar under it. Taking the bar away is also what lets the form fit
 * on one screen rather than hiding its own Save below the fold.
 *
 * Null - before the nav host has composed for the first time - shows the bar, because the start
 * destination is always Home and the bar cannot flash away for a route that is not yet known.
 */
internal fun NavDestination?.showsBottomBar(): Boolean = !(this?.hierarchy?.any { destination ->
    destination.hasRoute<AddTransactionRoute>() || destination.hasRoute<CategoryPickerRoute>()
} == true)

/**
 * Standard bottom-navigation behaviour: one instance per tab, the back stack is rewound to the
 * start destination, and each tab keeps its own scroll and input state.
 */
private fun NavHostController.switchTo(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
