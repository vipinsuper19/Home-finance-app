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
 * The bottom bar's behaviour around the recurring flows.
 *
 * Two decisions have to agree here and neither is obvious from either side. The recurring form graph and
 * the two detail screens deliberately draw **no** bottom bar - they take the whole screen, like the
 * ledger's own form flows - and yet the Recurring tab must still be the one the bar would mark as current
 * if it were drawn. The bar is composed over those destinations on purpose in one test, because that is the
 * only way to observe the bar's own mapping of a route whose real screen has no bar under it.
 *
 * The routes are the production ones and the graph is built from them, so a route that moved or was renamed
 * fails here rather than in the app.
 */
@RunWith(AndroidJUnit4::class)
class RecurringNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * The bar's own label, so finding it means the bar is on screen and not finding it means the bar is
     * gone. The placeholder screens below use other words, so there is nothing else it could be matching.
     */
    private val barLabel = InstrumentationRegistry.getInstrumentation()
        .targetContext
        .resources
        .getString(TopLevelDestination.Recurring.labelRes)

    @Test
    fun theRecurringTabIsSelectedWhileTheListIsOnScreen() {
        render()

        composeTestRule.onNode(isSelected()).assertExists()
    }

    @Test
    fun theBottomBarIsGoneOnTheAddFormAndComesBackOnTheList() {
        val navController = render()

        // The list is a tab, so the bar belongs on it.
        composeTestRule.onNodeWithText(barLabel).assertExists()

        composeTestRule.runOnUiThread { navController.navigate(AddRecurringRoute) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(barLabel).assertDoesNotExist()

        // The picker is part of the same flow, so it takes the whole screen as well.
        composeTestRule.runOnUiThread { navController.navigate(RecurringCategoryPickerRoute) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(barLabel).assertDoesNotExist()

        // Backing out of the flow puts the bar back where it was.
        composeTestRule.runOnUiThread { navController.popBackStack(RecurringRoute, false) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(barLabel).assertExists()
    }

    @Test
    fun theBottomBarIsGoneOnTheDetailsTheHistoryAndTheEditForm() {
        val navController = render()

        composeTestRule.onNodeWithText(barLabel).assertExists()

        listOf<Any>(
            RecurringDetailRoute(scheduleId = 7L),
            RecurringOccurrencesRoute(scheduleId = 7L),
            EditRecurringRoute(scheduleId = 7L),
        ).forEach { route ->
            composeTestRule.runOnUiThread { navController.navigate(route) }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText(barLabel).assertDoesNotExist()

            composeTestRule.runOnUiThread { navController.popBackStack() }
            composeTestRule.waitForIdle()
        }

        composeTestRule.onNodeWithText(barLabel).assertExists()
    }

    /**
     * The tab is named even on the screens the bar is hidden on.
     *
     * If the two decisions ever disagreed, the bar would be drawable with no section current - which is
     * exactly what [HomeFinanceBottomBar.TopLevelDestination.isOn] exists to prevent. Every recurring
     * destination is walked, because `isOn` has to know all of them.
     */
    @Test
    fun theBarMapsEveryRecurringDestinationToTheRecurringTab() {
        val navController = render(alwaysShowBar = true)

        // The list keeps the bar in production too, so it is the baseline for this walk.
        composeTestRule.onNode(isSelected()).assertExists()

        listOf<Any>(
            AddRecurringRoute,
            RecurringCategoryPickerRoute,
            RecurringDetailRoute(scheduleId = 7L),
            RecurringOccurrencesRoute(scheduleId = 7L),
            EditRecurringRoute(scheduleId = 7L),
        ).forEach { route ->
            composeTestRule.runOnUiThread { navController.navigate(route) }
            composeTestRule.waitForIdle()

            // One tab is drawn and it must be selected: a route it does not know would answer "no section
            // is current" and this would fail.
            composeTestRule.onNode(isSelected()).assertExists()

            composeTestRule.runOnUiThread { navController.popBackStack() }
            composeTestRule.waitForIdle()
        }
    }

    /**
     * Renders the shell's own construction over a graph holding the destinations this is about, and
     * returns the controller so the test can walk the graph.
     *
     * @param alwaysShowBar ignores `showsBottomBar()` and draws the bar regardless, which only
     *   [theBarMapsEveryRecurringDestinationToTheRecurringTab] asks for.
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
                            destinations = listOf(TopLevelDestination.Recurring),
                            currentDestination = currentDestination,
                            onDestinationSelected = {},
                        )
                    }
                    NavHost(navController = navController, startDestination = RecurringRoute) {
                        composable<RecurringRoute> { Text(text = "recurring list") }
                        composable<AddRecurringRoute> { Text(text = "recurring add form") }
                        composable<RecurringCategoryPickerRoute> { Text(text = "recurring category picker") }
                        composable<RecurringDetailRoute> { Text(text = "recurring details") }
                        composable<RecurringOccurrencesRoute> { Text(text = "recurring occurrences") }
                        composable<EditRecurringRoute> { Text(text = "recurring edit form") }
                    }
                }
            }
        }

        composeTestRule.waitForIdle()
        return navController
    }
}
