package com.homefinance.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.homefinance.app.data.model.TransactionType
import com.homefinance.app.feature.addtransaction.AddTransactionScreen
import com.homefinance.app.feature.home.HomeScreen
import com.homefinance.app.feature.recurring.RecurringScreen
import com.homefinance.app.feature.reports.ReportsScreen
import com.homefinance.app.feature.settings.SettingsScreen
import com.homefinance.app.feature.transactions.TransactionsScreen

/**
 * Maps the centralised routes to their screens.
 *
 * Screens never receive the [NavHostController] itself: they get lambdas, so navigation stays
 * in this one file and the screens remain easy to preview and test.
 */
@Composable
fun HomeFinanceNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        modifier = modifier,
    ) {
        composable<HomeRoute> {
            HomeScreen(onOpenSettings = { navController.navigate(SettingsRoute) })
        }
        composable<TransactionsRoute> {
            TransactionsScreen(
                onAddTransaction = { navController.navigate(AddTransactionRoute) },
            )
        }
        composable<RecurringRoute> {
            RecurringScreen()
        }
        composable<ReportsRoute> {
            ReportsScreen()
        }
        composable<SettingsRoute> {
            SettingsScreen(onBack = { navController.navigateUp() })
        }
        composable<AddTransactionRoute> {
            AddTransactionScreen(
                initialType = TransactionType.EXPENSE,
                onBack = { navController.navigateUp() },
                onSaved = { navController.navigateUp() },
            )
        }
    }
}
