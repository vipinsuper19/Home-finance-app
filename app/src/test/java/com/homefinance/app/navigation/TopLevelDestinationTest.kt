package com.homefinance.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class TopLevelDestinationTest {

    @Test
    fun exposesTheFourBottomNavigationTabsInOrder() {
        assertEquals(
            listOf("Home", "Transactions", "Recurring", "Reports"),
            TopLevelDestination.entries.map { it.name },
        )
    }

    @Test
    fun everyTabHasItsOwnRoute() {
        val routes = TopLevelDestination.entries.map { it.route }

        assertEquals(routes.size, routes.distinct().size)
    }

    @Test
    fun everyTabHasItsOwnLabel() {
        val labels = TopLevelDestination.entries.map { it.labelRes }

        assertEquals(labels.size, labels.distinct().size)
    }
}
