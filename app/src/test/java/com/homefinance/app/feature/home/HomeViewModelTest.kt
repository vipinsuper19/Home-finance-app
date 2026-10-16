package com.homefinance.app.feature.home

import com.homefinance.app.core.common.PlaceholderData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The view model is the seam where the Room-backed repository will plug in. These tests pin the
 * current contract: the initial state is the placeholder snapshot and is flagged as such, so the
 * Home screen's "Sample data" badge cannot be lost by accident.
 */
class HomeViewModelTest {

    @Test
    fun initialStateExposesThePlaceholderFigures() {
        val state = HomeViewModel().uiState.value

        assertEquals(PlaceholderData.balanceInPaise, state.balanceInPaise)
        assertEquals(PlaceholderData.incomeInPaise, state.incomeInPaise)
        assertEquals(PlaceholderData.expenseInPaise, state.expenseInPaise)
    }

    @Test
    fun initialStateIsFlaggedAsPlaceholderData() {
        assertTrue(HomeViewModel().uiState.value.isPlaceholderData)
    }
}
