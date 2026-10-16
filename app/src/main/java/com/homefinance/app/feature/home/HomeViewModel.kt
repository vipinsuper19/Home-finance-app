package com.homefinance.app.feature.home

import androidx.lifecycle.ViewModel
import com.homefinance.app.core.common.PlaceholderData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Backs the Home screen.
 *
 * Task 1.1 has no database, so the initial state comes from [PlaceholderData]. The screen
 * already observes a [StateFlow], so when the Room-backed repository arrives only the source
 * of the state changes here, and no UI restructuring is needed.
 */
class HomeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(
        HomeUiState(
            balanceInPaise = PlaceholderData.balanceInPaise,
            incomeInPaise = PlaceholderData.incomeInPaise,
            expenseInPaise = PlaceholderData.expenseInPaise,
        ),
    )

    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
}
