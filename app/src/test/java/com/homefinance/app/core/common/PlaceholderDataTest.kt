package com.homefinance.app.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the sample figures the Home screen shows until the storage layer lands. They are the
 * only "data" in Task 1.1/1.2, so a regression here would silently ship a nonsense screenshot.
 */
class PlaceholderDataTest {

    @Test
    fun balanceIsIncomeMinusExpense() {
        assertEquals(
            PlaceholderData.incomeInPaise - PlaceholderData.expenseInPaise,
            PlaceholderData.balanceInPaise,
        )
    }

    @Test
    fun expensesStayWithinIncome() {
        assertTrue(PlaceholderData.incomeInPaise > 0)
        assertTrue(PlaceholderData.expenseInPaise > 0)
        // The Home balance card presents a positive figure, so the sample must stay positive.
        assertTrue(PlaceholderData.balanceInPaise > 0)
    }
}
