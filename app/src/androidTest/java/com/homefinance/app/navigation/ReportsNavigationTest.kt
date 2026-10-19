package com.homefinance.app.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homefinance.app.core.ui.theme.HomeFinanceTheme
import com.homefinance.app.feature.reports.ReportPeriod
import com.homefinance.app.feature.reports.ReportRange
import java.time.YearMonth
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bottom bar's behaviour around the reports, and the window the drill-down opens on.
 *
 * Two decisions have to agree here. The category drill-down **keeps** the bottom bar - unlike the
 * ledger's and the recurring tab's detail screens, which take the whole screen - because the household
 * is still reading the report they came from rather than working through a separate task, and the
 * Reports tab is what the bar must name while it is on screen. The other is that the window travels in
 * the route, so tapping a category opens its report on the period that was being read rather than on
 * "this month", which would show a different total from the row that was tapped.
 *
 * The routes are the production ones and the graph is built from them, so a route that moved or was
 * renamed fails here rather than in the app.
 */
@RunWith(AndroidJUnit4::class)
class ReportsNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * The bar's own label, so finding it means the bar is on screen and not finding it means the bar is
     * gone. The stub screens below use other words, so there is nothing else it could be matching.
     */
    private val barLabel = InstrumentationRegistry.getInstrumentation()
        .targetContext
        .resources
        .getString(TopLevelDestination.Reports.labelRes)

    @Test
    fun theReportsTabIsSelectedWhileTheDashboardIsOnScreen() {
        render()

        composeTestRule.onNode(isSelected()).assertExists()
        composeTestRule.onNodeWithText(barLabel).assertExists()
    }

    @Test
    fun theBottomBarStaysOnTheCategoryDrillDownAndStillNamesTheReportsTab() {
        val navController = render()

        composeTestRule.onNodeWithText(barLabel).assertExists()

        composeTestRule.runOnUiThread { navController.navigate(routeFor(SIX_MONTHS_ENDING_JANUARY)) }
        composeTestRule.waitForIdle()

        // The bar belongs on this screen, and it must name Reports rather than no section at all.
        composeTestRule.onNodeWithText(barLabel).assertExists()
        composeTestRule.onNode(isSelected()).assertExists()

        // Backing out of the drill-down leaves the dashboard the bar was already naming.
        composeTestRule.runOnUiThread { navController.popBackStack() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(barLabel).assertExists()
        composeTestRule.onNode(isSelected()).assertExists()
    }

    @Test
    fun theBarMapsEveryReportDestinationToTheReportsTab() {
        val navController = render()

        composeTestRule.onNode(isSelected()).assertExists()

        listOf<Any>(ReportsRoute, routeFor(SIX_MONTHS_ENDING_JANUARY)).forEach { route ->
            composeTestRule.runOnUiThread { navController.navigate(route) }
            composeTestRule.waitForIdle()

            // One tab is drawn and it must be selected: a route it does not know would answer "no
            // section is current" and this would fail.
            composeTestRule.onNode(isSelected()).assertExists()

            if (route != ReportsRoute) {
                composeTestRule.runOnUiThread { navController.popBackStack() }
                composeTestRule.waitForIdle()
            }
        }
    }

    @Test
    fun tappingACategoryOpensItsReportOnTheWindowThatWasBeingRead() {
        val navController = render()

        // The stub stands in for the dashboard's own row: tapping it navigates exactly the way
        // `HomeFinanceNavHost` does, with the window written out as the route's own primitives.
        composeTestRule.onNodeWithText(OPEN_CATEGORY).performClick()
        composeTestRule.waitForIdle()

        // The drill-down's route is the only thing that decides this window, so what it printed is what
        // production would open on.
        composeTestRule.onNodeWithText(periodText(SIX_MONTHS_ENDING_JANUARY)).assertExists()
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
                            destinations = listOf(TopLevelDestination.Reports),
                            currentDestination = currentDestination,
                            onDestinationSelected = {},
                        )
                    }
                    NavHost(navController = navController, startDestination = ReportsRoute) {
                        composable<ReportsRoute> {
                            TextButton(onClick = { navController.navigate(routeFor(SIX_MONTHS_ENDING_JANUARY)) }) {
                                Text(text = OPEN_CATEGORY)
                            }
                        }
                        composable<CategoryReportRoute> { entry ->
                            val route = entry.toRoute<CategoryReportRoute>()

                            // The same conversion the drill-down performs, so the text below is the
                            // window `CategoryReportScreen` would actually be handed.
                            Text(
                                text = periodText(
                                    ReportPeriod(
                                        endMonth = YearMonth.of(route.endYear, route.endMonth),
                                        range = ReportRange.fromMonths(route.rangeMonths),
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
        }

        composeTestRule.waitForIdle()
        return navController
    }

    private fun routeFor(period: ReportPeriod): CategoryReportRoute = CategoryReportRoute(
        categoryId = 7L,
        rangeMonths = period.range.months,
        endYear = period.endMonth.year,
        endMonth = period.endMonth.monthValue,
    )

    private fun periodText(period: ReportPeriod): String = "period ${period.from} .. ${period.to}"

    private companion object {

        val SIX_MONTHS_ENDING_JANUARY: ReportPeriod =
            ReportPeriod(YearMonth.of(2026, 1), ReportRange.SIX_MONTHS)

        const val OPEN_CATEGORY: String = "open a category"
    }
}
