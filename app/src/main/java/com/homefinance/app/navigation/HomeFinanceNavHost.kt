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
import androidx.navigation.toRoute
import com.homefinance.app.data.model.TransactionType
import com.homefinance.app.feature.addtransaction.AddTransactionScreen
import com.homefinance.app.feature.addtransaction.AddTransactionViewModel
import com.homefinance.app.feature.addtransaction.CategoryPickerScreen
import com.homefinance.app.feature.addtransaction.EditTransactionScreen
import com.homefinance.app.feature.home.HomeScreen
import com.homefinance.app.feature.recurring.RecurringScreen
import com.homefinance.app.feature.reports.ReportsScreen
import com.homefinance.app.feature.settings.SettingsScreen
import com.homefinance.app.feature.transactiondetail.TransactionDetailScreen
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
                onOpenTransaction = { id -> navController.navigate(TransactionDetailRoute(id)) },
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
        composable<TransactionDetailRoute> { entry ->
            val transactionId = entry.toRoute<TransactionDetailRoute>().transactionId

            TransactionDetailScreen(
                transactionId = transactionId,
                onBack = { navController.navigateUp() },
                onEdit = { navController.navigate(EditTransactionRoute(transactionId)) },
                // Deleting leaves nothing behind to look at, so the whole details screen goes and
                // the ledger - which is already on the stack underneath - is what is left. Popping
                // to the ledger rather than stepping back once also makes this safe if an edit
                // screen is somehow still above this one.
                onDeleted = { navController.popBackStack(TransactionsRoute, inclusive = false) },
            )
        }

        transactionFormFlow(
            navController = navController,
            addViewModelFactory = AddTransactionViewModel.factory(TransactionType.EXPENSE),
            editViewModelFactory = { id ->
                AddTransactionViewModel.factory(TransactionType.EXPENSE, editingId = id)
            },
            onBack = { navController.navigateUp() },
            onSaved = { navController.navigateUp() },
            onEdited = { navController.navigateUp() },
        )
    }
}

/**
 * The transaction form flow: the two forms, and the category picker they both open, as one nested
 * graph.
 *
 * ### Why the screens are a graph
 *
 * Each form and the picker it opens share a single [AddTransactionViewModel] scoped to *this graph's*
 * back stack entry rather than to either destination. That is what makes the picker's choice the
 * form's choice: there is no result to pass back, no saved-state key to remember to read, and
 * popping the picker cannot lose it. The view model is created while the graph entry is on the stack
 * and cleared with it, so leaving a form - after a save, or after backing out - leaves nothing
 * behind.
 *
 * Both forms are in this one graph so that the picker's route is declared once. Entering either form
 * pushes a fresh graph entry, hence a fresh view model, and there is no route from one form to the
 * other, so the shared entry can never be holding the other one's figures.
 *
 * ### Why this is a separate function
 *
 * One production caller, and that is normally reason enough not to extract anything. It is extracted
 * for the same reason `HomeFinanceDatabase.build` takes a database name: a device test can drive the
 * **real** graph - the real routes, the real shared scoping - while swapping only where the data
 * comes from, so the wiring the household actually walks is the wiring the test walks. A test that
 * rebuilt the graph for itself would prove the test's copy works, which is worth nothing.
 *
 * @param addViewModelFactory how the form that records a new entry is built, and also the factory the
 *   picker is handed. Production passes [AddTransactionViewModel.factory] and lets the application
 *   class supply the repositories; a test passes the overload that takes them directly, so no test
 *   ever opens the household's database. The picker can never be the first of the three to compose -
 *   it is only reachable from a form, which will already have built the view model - so the factory
 *   it is given is never actually consulted.
 * @param editViewModelFactory how the form that changes an existing transaction is built, from the
 *   id it is changing.
 * @param onBack a form was dismissed without saving. The picker's own back step stays inside the
 *   flow, because backing out of the picker returns to the form rather than leaving it.
 * @param onSaved a new transaction was written and the add form is finished with.
 * @param onEdited an existing transaction was rewritten and the edit form is finished with.
 */
internal fun NavGraphBuilder.transactionFormFlow(
    navController: NavHostController,
    addViewModelFactory: ViewModelProvider.Factory,
    editViewModelFactory: (Long) -> ViewModelProvider.Factory,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onEdited: () -> Unit,
) {
    navigation<TransactionFormGraph>(startDestination = AddTransactionRoute) {
        composable<AddTransactionRoute> { entry ->
            AddTransactionScreen(
                onBack = onBack,
                onSaved = onSaved,
                onPickCategory = { navController.navigate(CategoryPickerRoute) },
                viewModel = entry.sharedFormViewModel(navController, addViewModelFactory),
            )
        }
        composable<EditTransactionRoute> { entry ->
            val transactionId = entry.toRoute<EditTransactionRoute>().transactionId

            EditTransactionScreen(
                transactionId = transactionId,
                onBack = onBack,
                onFinished = onEdited,
                onPickCategory = { navController.navigate(CategoryPickerRoute) },
                viewModel = entry.sharedFormViewModel(
                    navController = navController,
                    viewModelFactory = remember(transactionId) { editViewModelFactory(transactionId) },
                ),
            )
        }
        composable<CategoryPickerRoute> { entry ->
            CategoryPickerScreen(
                onBack = { navController.navigateUp() },
                viewModel = entry.sharedFormViewModel(navController, addViewModelFactory),
            )
        }
    }
}

/**
 * The one [AddTransactionViewModel] a form and the picker both render.
 *
 * The store owner is the **graph's** back stack entry, not the current destination's, which is the
 * whole point: it is looked up from the graph's route, so both screens resolve to the same instance
 * no matter which of them is on top - and, when the flow was entered at the edit form, that instance
 * is the edit form's own.
 *
 * [viewModelFactory] is only consulted the first time the entry builds a view model - the store holds
 * the instance afterwards - but it is passed on every call because that is the only way to hand a
 * factory to `viewModel()`.
 */
@Composable
private fun NavBackStackEntry.sharedFormViewModel(
    navController: NavHostController,
    viewModelFactory: ViewModelProvider.Factory,
): AddTransactionViewModel {
    val graphEntry = remember(this) { navController.getBackStackEntry<TransactionFormGraph>() }

    return viewModel(
        viewModelStoreOwner = graphEntry,
        factory = viewModelFactory,
    )
}
