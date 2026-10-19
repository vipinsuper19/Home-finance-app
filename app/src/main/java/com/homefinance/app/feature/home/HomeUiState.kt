package com.homefinance.app.feature.home

import androidx.compose.runtime.Immutable

/**
 * Immutable snapshot of everything the Home screen renders.
 *
 * The summary figures are still [com.homefinance.app.core.common.PlaceholderData] and are still
 * flagged as such - see [isPlaceholderData] - while [recurring] is read from the household's own
 * database. That mix is deliberate and temporary: the recurring section is real, and the monthly
 * figures become real in a later piece of work, so the badge keeps saying which is which rather than
 * quietly dressing sample numbers as the household's own.
 *
 * ### Expected money is not actual money
 *
 * [recurring] holds *expectations*: a date that is coming, or one that is late, is not a payment and
 * has moved nothing. It is therefore kept apart from [balanceInPaise], [incomeInPaise] and
 * [expenseInPaise], which are the figures that will one day be summed from the ledger. Nothing in the
 * section is ever added to them - the household's totals stay exactly what the transactions say, and a
 * bill that has not been paid is not allowed to look like one that has.
 */
@Immutable
data class HomeUiState(
    val balanceInPaise: Long,
    val incomeInPaise: Long,
    val expenseInPaise: Long,
    /** True while the figures above come from [com.homefinance.app.core.common.PlaceholderData]. */
    val isPlaceholderData: Boolean = true,
    /** What the recurring section is showing, on its own, so its failure cannot blank the dashboard. */
    val recurring: HomeRecurringUiState = HomeRecurringUiState.Loading,
)
