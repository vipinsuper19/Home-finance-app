package com.homefinance.app.feature.home

import androidx.compose.runtime.Immutable

/**
 * Immutable snapshot of everything the Home screen renders.
 *
 * [balanceInPaise], [incomeInPaise] and [expenseInPaise] are the current month's sums read from the
 * household's own ledger - exact `Long` paise, summed by SQLite over the transactions that have
 * actually moved. They are **not** sample figures and there is no badge pretending otherwise.
 *
 * ### Expected money is not actual money
 *
 * [recurring] holds *expectations*: a date that is coming, or one that is late, is not a payment and
 * has moved nothing. It is therefore kept apart from the monthly figures above. Nothing in the
 * section is ever added to them - the household's totals stay exactly what the transactions say, and a
 * bill that has not been paid is not allowed to look like one that has.
 */
@Immutable
data class HomeUiState(
    val balanceInPaise: Long,
    val incomeInPaise: Long,
    val expenseInPaise: Long,
    /**
     * Whether the balance card may draw its amount.
     *
     * A display preference, not a data change: when it is off the amount is not rendered at all - it
     * is not merely hidden behind a colour - and everything else on the card and the dashboard stays
     * exactly where it was. Defaults to `true`, which is the behaviour that shipped before Settings.
     */
    val showBalance: Boolean = true,
    /** What the recurring section is showing, on its own, so its failure cannot blank the dashboard. */
    val recurring: HomeRecurringUiState = HomeRecurringUiState.Loading,
)
