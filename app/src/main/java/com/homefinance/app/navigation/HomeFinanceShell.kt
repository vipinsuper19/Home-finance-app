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
            // The bar is left off the transaction flows entirely - see [showsBottomBar] - and when
            // it is missing the scaffold's own padding falls back to the system bar inset, so a
            // form still stops above the navigation bar rather than under it.
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
 * Everything to do with one transaction takes the whole screen: the entry's details, the forms that
 * record and change it, and the category picker they open. Recording or changing an entry is a single
 * uninterrupted piece of work with its own action at the bottom of it, and offering four other places
 * to go halfway through is the one thing those screens should not do; the designs they were built
 * from have no navigation bar under them. Taking the bar away is also what lets a form fit on one
 * screen rather than hiding its own action below the fold. The tab a transaction belongs to stays
 * named meanwhile - see [HomeFinanceBottomBar] - so the bar can never appear with nothing selected.
 *
 * A recurring schedule's details, its occurrence history and the forms that create and change it take
 * the whole screen for exactly the same reasons.
 *
 * The ledger itself is a tab, so it keeps its bar - and so does the recurring list, which is the tab.
 *
 * Null - before the nav host has composed for the first time - shows the bar, because the start
 * destination is always Home and the bar cannot flash away for a route that is not yet known.
 */
internal fun NavDestination?.showsBottomBar(): Boolean = !(this?.hierarchy?.any { destination ->
    destination.hasRoute<TransactionDetailRoute>() ||
        destination.hasRoute<AddTransactionRoute>() ||
        destination.hasRoute<EditTransactionRoute>() ||
        destination.hasRoute<CategoryPickerRoute>() ||
        destination.hasRoute<RecurringDetailRoute>() ||
        destination.hasRoute<RecurringOccurrencesRoute>() ||
        destination.hasRoute<AddRecurringRoute>() ||
        destination.hasRoute<EditRecurringRoute>() ||
        destination.hasRoute<RecurringCategoryPickerRoute>()
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
