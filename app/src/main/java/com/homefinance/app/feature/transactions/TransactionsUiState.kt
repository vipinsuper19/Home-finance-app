package com.homefinance.app.feature.transactions

import androidx.compose.runtime.Immutable
import com.homefinance.app.core.util.CurrencyFormatter
import com.homefinance.app.data.local.relation.TransactionWithCategory
import com.homefinance.app.data.model.TransactionType
import java.time.LocalDate
import java.time.YearMonth

/**
 * Everything the Transactions screen renders, as one of exactly three mutually exclusive
 * snapshots.
 *
 * ### Why a sealed hierarchy rather than a `data class` of flags
 *
 * A class carrying `isLoading`, `items` and `errorMessage` can represent states that do not exist:
 * loading *and* an error, or an error *and* a list of rows. Worse, a failure could then leave the
 * previous rows on screen with a small error banner underneath, presenting stale figures as
 * current - precisely the "amount I saw five minutes ago" problem a ledger must not have.
 *
 * Here the three outcomes are an exhaustive `when`, so the compiler forces the screen to decide
 * what a failure looks like, and [Error] carries **no rows at all**. Showing stale data after a
 * failed query is therefore not something the screen has to remember not to do; it is something it
 * cannot express.
 *
 * [Content] with no groups is the empty state. "Loaded successfully and there is nothing there" is
 * a success, not a failure, and keeping it inside [Content] means the empty view and the list view
 * can never disagree about which one owns the screen.
 */
sealed interface TransactionsUiState {

    /** The first load, before the database has answered. */
    data object Loading : TransactionsUiState

    /**
     * The ledger as the household has asked to see it, newest first and split under date headings.
     *
     * This carries no rows when a filter excludes everything, which is a different thing from an
     * empty ledger - see [ledgerIsEmpty]. Both are successes, so the screen is told which of the two
     * it is looking at instead of having to infer it from an empty list.
     */
    data class Content(
        /** The rows to draw, newest day first, with the headings that separate them. */
        val groups: List<TransactionDateGroup>,
        /** The search text, direction and month currently applied, as the controls must show them. */
        val filters: TransactionFilters,
        /** Every month that has entries, newest first, for the month selector to offer. */
        val monthOptions: List<YearMonth>,
        /**
         * True when the household has recorded nothing at all.
         *
         * This drives a genuinely different screen: an empty ledger is invited to be filled, and
         * offering it a search box and three disabled filters would be furniture in front of a
         * blank page. A ledger with rows but no *matches* keeps its controls, because there the
         * filters are the thing the household needs to change.
         */
        val ledgerIsEmpty: Boolean,
    ) : TransactionsUiState {

        /**
         * Every visible row, in the order it is drawn, with the day headings dropped.
         *
         * The headings belong to the list, not to the data, so anything reasoning about the rows
         * themselves - a test, or a later running total - should not have to walk the groups.
         */
        val visibleItems: List<TransactionListItem>
            get() = groups.flatMap { it.items }
    }

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
 * One day's entries, under the heading that names it.
 *
 * Grouping happens in the view model rather than in the list, so the heading and the rows under it
 * are decided together and the list is left with nothing to compute.
 */
@Immutable
data class TransactionDateGroup(
    val date: LocalDate,
    val relation: DateRelation,
    val items: List<TransactionListItem>,
)

/**
 * How a day reads relative to the one the device is having.
 *
 * Resolved in the view model against an injectable "today", so a test can pin it and the wording
 * never depends on what a composable happens to think the date is.
 */
enum class DateRelation {
    TODAY,
    YESTERDAY,

    /** Anything older, which needs no qualifier: the date itself is the answer. */
    EARLIER,
}

/**
 * The controls, as applied to the data.
 *
 * [month] is never null here: by the time a state carries filters, a month has been chosen from
 * what the ledger actually contains (see [toContent]).
 */
@Immutable
data class TransactionFilters(
    val query: String = "",
    val type: TransactionTypeFilter = TransactionTypeFilter.ALL,
    val month: YearMonth,
)

/**
 * What the household has asked for, before the ledger has been consulted.
 *
 * A null [month] means "follow the data": the current month while it has entries, otherwise the
 * newest month that does. That resolves to a real month in [toContent] and is what keeps a fresh
 * install from opening onto a September ledger under an October heading.
 */
@Immutable
data class TransactionFilterIntent(
    val query: String = "",
    val type: TransactionTypeFilter = TransactionTypeFilter.ALL,
    val month: YearMonth? = null,
)

/** The direction filter: which of the two kinds of entry to show, or both. */
enum class TransactionTypeFilter {
    ALL,
    INCOME,
    EXPENSE,
    ;

    fun matches(type: TransactionType): Boolean = when (this) {
        ALL -> true
        INCOME -> type == TransactionType.INCOME
        EXPENSE -> type == TransactionType.EXPENSE
    }
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
     * The amount as the row shows it: the magnitude, always positive, with no sign.
     *
     * The direction of the money is carried by [type], which the row renders as a colour and which
     * a later task can state in words. The magnitude is formatted by the one shared formatter, so
     * the list cannot disagree with the rest of the app about what a crore looks like.
     */
    fun amountText(): String = CurrencyFormatter.formatInr(amountPaise)
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

/**
 * Folds a loaded ledger into the state the screen draws: the month, then the direction, then the
 * search - and then the day headings.
 *
 * Everything here is filtering and grouping over rows already in memory. None of it queries
 * anything: the ledger is read once by Room and re-read only when the household asks for it, so
 * typing in the search box cannot put a database on the critical path of a keystroke.
 *
 * The order of operations is the order the controls appear in, and it matters for one case only:
 * the month is resolved from the *whole* ledger before anything is filtered out, so the month the
 * screen opens on is chosen from what the household has, not from what survived a filter they have
 * not touched yet.
 */
fun List<TransactionWithCategory>.toContent(
    intent: TransactionFilterIntent,
    today: LocalDate,
): TransactionsUiState.Content {
    val items = map { it.toListItem() }

    val monthsWithEntries = items.map { YearMonth.from(it.transactionDate) }.distinct().sortedDescending()

    // The month to show: the one asked for, else the current month while it has entries, else the
    // newest month that does. `monthsWithEntries` is empty only when the ledger is, in which case
    // the current month is shown and the controls are hidden anyway.
    val selectedMonth = intent.month
        ?: YearMonth.from(today).takeIf { it in monthsWithEntries }
        ?: monthsWithEntries.firstOrNull()
        ?: YearMonth.from(today)

    val query = intent.query.trim()
    val visible = items
        .filter { YearMonth.from(it.transactionDate) == selectedMonth }
        .filter { intent.type.matches(it.type) }
        .filter { query.isEmpty() || it.matches(query) }

    return TransactionsUiState.Content(
        // `groupBy` keeps the order it met each day in, and the rows arrive newest first, so the
        // headings come out newest first too and the days inside each keep the query's ordering.
        groups = visible
            .groupBy { it.transactionDate }
            .map { (date, dayItems) ->
                TransactionDateGroup(date = date, relation = date.relationTo(today), items = dayItems)
            },
        filters = TransactionFilters(query = intent.query, type = intent.type, month = selectedMonth),
        // The selected month is unioned in so the selector can always show the month it is on,
        // even in the moment before the ledger it was chosen from has been re-read.
        monthOptions = (monthsWithEntries + selectedMonth).distinct().sortedDescending(),
        ledgerIsEmpty = items.isEmpty(),
    )
}

/**
 * Whether this row is worth showing for [query].
 *
 * The description is matched as well as the category, because "chai" is how a household remembers
 * a purchase and "Groceries" is only how it was filed.
 */
private fun TransactionListItem.matches(query: String): Boolean =
    categoryName.contains(query, ignoreCase = true) ||
        description?.contains(query, ignoreCase = true) == true

private fun LocalDate.relationTo(today: LocalDate): DateRelation = when (this) {
    today -> DateRelation.TODAY
    today.minusDays(1) -> DateRelation.YESTERDAY
    else -> DateRelation.EARLIER
}
