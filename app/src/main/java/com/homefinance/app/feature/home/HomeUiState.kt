package com.homefinance.app.feature.home

import androidx.compose.runtime.Immutable

/**
 * Immutable snapshot of everything the Home screen renders.
 *
 * Free of Android and database types on purpose, so it is trivial to test now and to build
 * from a Room query later without touching the UI.
 */
@Immutable
data class HomeUiState(
    val balanceInPaise: Long,
    val incomeInPaise: Long,
    val expenseInPaise: Long,
    /** True while the figures come from [com.homefinance.app.core.common.PlaceholderData]. */
    val isPlaceholderData: Boolean = true,
)
