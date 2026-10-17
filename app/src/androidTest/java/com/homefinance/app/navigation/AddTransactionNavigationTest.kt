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
 * The bottom bar's behaviour around the add-transaction flow.
 *
 * Two things have to hold, and neither is a pointer count on a route list. On the ledger the
 * Transactions tab must be the selected one - the shell always draws the bar over a tab, so if the
 * bar could not map that destination back to a section it would appear with *nothing* selected. And
 * on the add form and its category picker the bar must not be drawn at all, because that flow takes
 * the whole screen.
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

    /**
     * Renders the shell's own construction over a graph holding the destinations this is about, and
     * returns the controller so the test can walk the graph.
     */
    private fun render(): NavHostController {
        lateinit var navController: NavHostController

        composeTestRule.setContent {
            HomeFinanceTheme {
                navController = rememberNavController()
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = backStackEntry?.destination

                Column {
                    if (currentDestination.showsBottomBar()) {
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
                    }
                }
            }
        }

        composeTestRule.waitForIdle()
        return navController
    }
}
