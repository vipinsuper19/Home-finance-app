package com.homefinance.app.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

/**
 * The application shell: a Material 3 scaffold holding the bottom navigation bar and the
 * navigation host.
 *
 * System-bar insets are consumed here, once, so that feature screens never have to deal with
 * them (their top app bars zero the insets for the same reason).
 */
@Composable
fun HomeFinanceShell(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            HomeFinanceBottomBar(
                destinations = TopLevelDestination.entries,
                currentDestination = backStackEntry?.destination,
                onDestinationSelected = navController::switchTo,
            )
        },
    ) { innerPadding ->
        HomeFinanceNavHost(
            navController = navController,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

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
