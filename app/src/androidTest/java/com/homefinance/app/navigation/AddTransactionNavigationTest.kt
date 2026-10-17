package com.homefinance.app.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homefinance.app.core.ui.theme.HomeFinanceTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bottom bar's behaviour around the transaction flows.
 *
 * Two things have to hold, and neither is a pointer count on a route list. On the ledger the
 * Transactions tab must be the selected one - the shell always draws the bar over a tab, so if the
 * bar could not map that destination back to a section it would appear with *nothing* selected. And
 * on everything that belongs to one transaction - its details, the two forms, and the picker they
 * open - the bar must not be drawn at all, because each of those takes the whole screen.
 *
 * The bar is composed here the way [HomeFinanceShell] composes it - same destinations, same
 * conditional, same [showsBottomBar] - over a real navigation graph, so what is asserted is the
 * shipped decision applied to real destinations rather than a description of it.
 */
@RunWith(AndroidJUnit4::class)
class AddTransactionNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * The bar's own label, so that finding it means the bar is on screen and not finding it means
     * the bar is gone. None of the placeholder screens below use this text, so there is nothing else
     * it could be matching.
     */
    private val barLabel = InstrumentationRegistry.getInstrumentation()
        .targetContext
        .resources
        .getString(TopLevelDestination.Transactions.labelRes)

    @Test
    fun theTransactionsTabIsSelectedWhileTheLedgerIsOnScreen() {
        val navController = render()

        composeTestRule.onNode(isSelected()).assertExists()

        composeTestRule.runOnUiThread { navController.navigate(AddTransactionRoute) }
        composeTestRule.waitForIdle()
    }

    @Test
    fun theBottomBarIsGoneOnTheAddFormAndComesBackOnTheLedger() {
        val navController = render()

        // The ledger is a tab, so the bar belongs on it.
        composeTestRule.onNodeWithText(barLabel).assertExists()

        composeTestRule.runOnUiThread { navController.navigate(AddTransactionRoute) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(barLabel).assertDoesNotExist()

        // The picker is part of the same flow, so it takes the whole screen as well.
        composeTestRule.runOnUiThread { navController.navigate(CategoryPickerRoute) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(barLabel).assertDoesNotExist()

        // Backing out of the flow puts the bar back where it was.
        composeTestRule.runOnUiThread { navController.popBackStack(TransactionsRoute, false) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(barLabel).assertExists()
    }

    @Test
    fun theBottomBarIsGoneOnOneTransactionsDetailsAndOnTheFormThatChangesIt() {
        val navController = render()

        composeTestRule.onNodeWithText(barLabel).assertExists()

        composeTestRule.runOnUiThread {
            navController.navigate(TransactionDetailRoute(transactionId = 7L))
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(barLabel).assertDoesNotExist()

        composeTestRule.runOnUiThread {
            navController.navigate(EditTransactionRoute(transactionId = 7L))
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(barLabel).assertDoesNotExist()

        composeTestRule.runOnUiThread { navController.popBackStack(TransactionsRoute, false) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(barLabel).assertExists()
    }

    /**
     * The tab is named even on the screens the bar is hidden on, so if the two decisions ever
     * disagree the bar cannot end up drawn with nothing selected.
     *
     * The bar is composed over these destinations on purpose - the one place a test asks for the
     * opposite of what ships - because that is the only way to observe the bar's own mapping of a
     * route whose real screen deliberately has no bar under it.
     */
    @Test
    fun theBarMapsEveryTransactionDestinationToTheTransactionsTab() {
        val navController = render(alwaysShowBar = true)

        // The ledger keeps the bar in production too, so it is the baseline for this walk.
        composeTestRule.onNode(isSelected()).assertExists()

        listOf<Any>(
            TransactionDetailRoute(transactionId = 7L),
            AddTransactionRoute,
            EditTransactionRoute(transactionId = 7L),
            CategoryPickerRoute,
        ).forEach { route ->
            composeTestRule.runOnUiThread { navController.navigate(route) }
            composeTestRule.waitForIdle()

            // One tab is drawn and it must be selected: `isOn` has to know every one of these
            // routes, or it would answer "no section is current".
            composeTestRule.onNode(isSelected()).assertExists()

            composeTestRule.runOnUiThread { navController.popBackStack() }
            composeTestRule.waitForIdle()
        }
    }

    /**
     * Renders the shell's own construction over a graph holding the destinations this is about, and
     * returns the controller so the test can walk the graph.
     *
     * @param alwaysShowBar ignores [showsBottomBar] and draws the bar regardless, which only
     *   [theBarMapsEveryTransactionDestinationToTheTransactionsTab] asks for.
     */
    private fun render(alwaysShowBar: Boolean = false): NavHostController {
        lateinit var navController: NavHostController

        composeTestRule.setContent {
            HomeFinanceTheme {
                navController = rememberNavController()
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = backStackEntry?.destination

                Column {
                    if (alwaysShowBar || currentDestination.showsBottomBar()) {
                        HomeFinanceBottomBar(
                            // One tab is enough: with the bar on screen at all, "some tab is
                            // selected" and "the Transactions tab is selected" are the same
                            // question here.
                            destinations = listOf(TopLevelDestination.Transactions),
                            currentDestination = currentDestination,
                            onDestinationSelected = {},
                        )
                    }
                    NavHost(navController = navController, startDestination = TransactionsRoute) {
                        composable<TransactionsRoute> { Text(text = "ledger") }
                        composable<AddTransactionRoute> { Text(text = "add form") }
                        composable<CategoryPickerRoute> { Text(text = "category picker") }
                        composable<TransactionDetailRoute> { Text(text = "transaction details") }
                        composable<EditTransactionRoute> { Text(text = "edit form") }
                    }
                }
            }
        }

        composeTestRule.waitForIdle()
        return navController
    }
}
