package com.homefinance.app.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homefinance.app.core.ui.theme.HomeFinanceTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bottom bar's highlight while a detail destination opened from a tab is on screen.
 *
 * The shell always draws the bottom bar, so if the add form were not mapped back to the
 * Transactions tab the bar would appear with *nothing* selected - the one regression a
 * pointer-count check on the routes cannot catch. This renders the real bar over a real navigation
 * graph and asks it which tab it thinks is current.
 */
@RunWith(AndroidJUnit4::class)
class AddTransactionNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun theTransactionsTabStaysSelectedWhileTheAddFormIsOnScreen() {
        lateinit var navController: NavHostController

        composeTestRule.setContent {
            HomeFinanceTheme {
                navController = rememberNavController()
                val backStackEntry by navController.currentBackStackEntryAsState()

                Column {
                    // Only the Transactions tab is rendered, so "some tab is selected" and "the
                    // Transactions tab is selected" are the same question here.
                    HomeFinanceBottomBar(
                        destinations = listOf(TopLevelDestination.Transactions),
                        currentDestination = backStackEntry?.destination,
                        onDestinationSelected = {},
                    )
                    AddFormGraph(navController)
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNode(isSelected()).assertExists()

        composeTestRule.runOnUiThread { navController.navigate(AddTransactionRoute) }
        composeTestRule.waitForIdle()

        composeTestRule.onNode(isSelected()).assertExists()
    }

    /** The smallest graph that contains the two destinations this test is about. */
    @Composable
    private fun AddFormGraph(navController: NavHostController) {
        NavHost(navController = navController, startDestination = TransactionsRoute) {
            composable<TransactionsRoute> { Text(text = "ledger") }
            composable<AddTransactionRoute> { Text(text = "add form") }
        }
    }
}
