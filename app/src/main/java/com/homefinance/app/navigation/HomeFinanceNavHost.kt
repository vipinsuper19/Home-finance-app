package com.homefinance.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import com.homefinance.app.data.model.TransactionType
import com.homefinance.app.feature.addtransaction.AddTransactionScreen
import com.homefinance.app.feature.addtransaction.AddTransactionViewModel
import com.homefinance.app.feature.addtransaction.CategoryPickerScreen
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

        addTransactionFlow(
            navController = navController,
            viewModelFactory = AddTransactionViewModel.factory(TransactionType.EXPENSE),
            onBack = { navController.navigateUp() },
            onSaved = { navController.navigateUp() },
        )
    }
}

/**
 * The add-transaction flow: the form, and the category picker it opens, as one nested graph.
 *
 * ### Why the two screens are a graph
 *
 * They share a single [AddTransactionViewModel] scoped to *this graph's* back stack entry rather than
 * to either destination. That is what makes the picker's choice the form's choice: there is no result
 * to pass back, no saved-state key to remember to read, and popping the picker cannot lose it. The
 * view model is created while the graph entry is on the stack and cleared with it, so leaving the
 * form after a save leaves nothing behind.
 *
 * ### Why this is a separate function
 *
 * One production caller, and that is normally reason enough not to extract anything. It is extracted
 * for the same reason `HomeFinanceDatabase.build` takes a database name: a device test can drive the
 * **real** graph - the real routes, the real shared scoping - while swapping only where the data
 * comes from, so the wiring the household actually walks is the wiring the test walks. A test that
 * rebuilt the graph for itself would prove the test's copy works, which is worth nothing.
 *
 * @param viewModelFactory how the shared view model is built. Production passes
 *   [AddTransactionViewModel.factory] and lets the application class supply the repositories; a test
 *   passes the overload that takes them directly, so no test ever opens the household's database.
 * @param onBack the form was dismissed without saving. The picker's own back step stays inside the
 *   flow, because backing out of the picker returns to the form rather than leaving it.
 * @param onSaved a transaction was written and the form is finished with.
 */
internal fun NavGraphBuilder.addTransactionFlow(
    navController: NavHostController,
    viewModelFactory: ViewModelProvider.Factory,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    navigation<AddTransactionGraph>(startDestination = AddTransactionRoute) {
        composable<AddTransactionRoute> { entry ->
            AddTransactionScreen(
                onBack = onBack,
                onSaved = onSaved,
                onPickCategory = { navController.navigate(CategoryPickerRoute) },
                viewModel = entry.sharedAddTransactionViewModel(navController, viewModelFactory),
            )
        }
        composable<CategoryPickerRoute> { entry ->
            CategoryPickerScreen(
                onBack = { navController.navigateUp() },
                viewModel = entry.sharedAddTransactionViewModel(navController, viewModelFactory),
            )
        }
    }
}

/**
 * The one [AddTransactionViewModel] the form and the picker both render.
 *
 * The store owner is the **graph's** back stack entry, not the current destination's, which is the
 * whole point: it is looked up from the graph's route, so both screens resolve to the same instance
 * no matter which of them is on top.
 *
 * [viewModelFactory] is only consulted the first time - the store holds the instance afterwards - but
 * it is passed on every call because that is the only way to hand a factory to `viewModel()`.
 */
@Composable
private fun NavBackStackEntry.sharedAddTransactionViewModel(
    navController: NavHostController,
    viewModelFactory: ViewModelProvider.Factory,
): AddTransactionViewModel {
    val graphEntry = remember(this) { navController.getBackStackEntry<AddTransactionGraph>() }

    return viewModel(
        viewModelStoreOwner = graphEntry,
        factory = viewModelFactory,
    )
}
