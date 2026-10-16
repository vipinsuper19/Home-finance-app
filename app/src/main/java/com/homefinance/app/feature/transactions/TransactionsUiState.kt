package com.homefinance.app.feature.transactions

import androidx.compose.runtime.Immutable
import com.homefinance.app.core.util.CurrencyFormatter
import com.homefinance.app.data.local.relation.TransactionWithCategory
import com.homefinance.app.data.model.TransactionType
import java.time.LocalDate

/**
 * Everything the Transactions screen renders, as one of exactly three mutually exclusive
 * snapshots.
 *
 * ### Why a sealed hierarchy rather than a `data class` of flags
 *
 * A class carrying `isLoading`, `items` and `errorMessage` can represent states that do not exist:
 * loading *and* an error, or an error *and* a list of rows. Worse, a failure could then leave the
 * previous rows on screen with a small error banner underneath, presenting stale figures as
 * current - precisely the "amt I saw five minutes ago" problem a ledger must not have.
 *
 * Here the three outcomes are an exhaustive `when`, so the compiler forces the screen to decide
 * what a failure looks like, and [Error] carries **no rows at all**. Showing stale data after a
 * failed query is therefore not something the screen has to remember not to do; it is something it
 * cannot express.
 *
 * [Content] with an empty list is the empty state. "Loaded successfully and there is nothing
 * there" is a success, not a failure, and keeping it inside [Content] means the empty view and the
 * list view can never disagree about which one owns the screen.
 */
sealed interface TransactionsUiState {

    /** The first load, before the database has answered. */
    data object Loading : TransactionsUiState

    /**
     * The ledger as it stands, newest first. An empty [items] means the household has not
     * recorded anything yet.
     */
    data class Content(val items: List<TransactionListItem>) : TransactionsUiState

    /**
     * The query failed.
     *
     * [cause] is kept so a test can prove *which* failure reached the screen and so a future crash
     * log has something to record. The screen itself never renders it: a SQLite exception message
     * is not something a household can act on, so the user-facing copy comes from string
     * resources instead.
     */
    data class Error(val cause: Throwable) : TransactionsUiState
}

/**
 * One row of the ledger, already resolved to everything the row draws.
 *
 * Amounts stay an exact [Long] count of paise here and are formatted by the composable through
 * [CurrencyFormatter], matching how `HomeScreen` renders its figures: the state never holds a
 * pre-formatted, locale-dependent string, so it stays trivially testable and cannot go stale when
 * the locale changes.
 */
@Immutable
data class TransactionListItem(
    val id: Long,
    val categoryName: String,
    val description: String?,
    val transactionDate: LocalDate,
    val amountPaise: Long,
    val type: TransactionType,
) {

    /**
     * The amount exactly as the row shows it: the magnitude with a leading sign taken from [type].
     *
     * The sign is derived from the type and never from the stored value, because amounts are
     * always positive - the direction of the movement lives in [type] (see
     * [com.homefinance.app.data.local.entity.TransactionEntity]). Reusing one definition means the
     * list cannot disagree with itself about which numbers add to the household balance.
     *
     * The sign is not decoration. Colour alone carries the income/expense distinction, and colour
     * alone is unreadable for anyone who cannot distinguish these two shades, so the direction is
     * also stated in text.
     */
    fun signedAmountText(): String =
        (if (type == TransactionType.INCOME) "+" else "-") + CurrencyFormatter.formatInr(amountPaise)
}

/** Builds the row the screen renders from the joined row the database returned. */
fun TransactionWithCategory.toListItem(): TransactionListItem = TransactionListItem(
    id = id,
    categoryName = categoryName,
    description = description,
    transactionDate = transactionDate,
    amountPaise = amountPaise,
    type = type,
)
