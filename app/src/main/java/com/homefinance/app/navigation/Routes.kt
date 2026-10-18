package com.homefinance.app.navigation

import kotlinx.serialization.Serializable

/**
 * Every navigation route in the app, in one place.
 *
 * Type-safe routes (Navigation Compose 2.8+) are used rather than string routes, so renaming
 * or mistyping a destination becomes a compile error instead of a runtime crash.
 */

// Bottom-navigation destinations. Order matches the navigation bar.
@Serializable
data object HomeRoute

@Serializable
data object TransactionsRoute

@Serializable
data object RecurringRoute

@Serializable
data object ReportsRoute

// Detail destination opened from the Home toolbar.
@Serializable
data object SettingsRoute

// Detail destination opened from the Transactions screen's add action.
@Serializable
data object AddTransactionRoute

/**
 * One transaction's details, opened from its row in the ledger.
 *
 * The id is carried as an argument rather than held anywhere else, so the screen reads the
 * transaction the household actually tapped. It is a `Long` - the ledger's primary key, not a
 * position in a list - so a row that moves when the list re-sorts cannot make this screen show a
 * different transaction.
 */
@Serializable
data class TransactionDetailRoute(val transactionId: Long)

/**
 * The edit form for one transaction, opened from that transaction's details.
 *
 * It carries the same id, and for the same reason: the form reads the row it is about to change
 * rather than being handed a copy of it, so the figures it shows are the ones in the database and
 * the write goes back to that row and no other.
 */
@Serializable
data class EditTransactionRoute(val transactionId: Long)

/**
 * The route of the **nested graph** that holds the two transaction forms and the category picker
 * they both open.
 *
 * It is a graph rather than a set of plain destinations because each form and its picker are one
 * flow: they share a single [com.homefinance.app.feature.addtransaction.AddTransactionViewModel]
 * scoped to this graph's back stack entry, so the category chosen on the picker is the same fact the
 * form renders, with no result passing through navigation. Nothing navigates *to* this route - the
 * flow is entered at one of the two forms - but the graph needs a route like any other destination,
 * because that is what the shared view model is scoped to and what the screens look each other up by.
 *
 * Both forms live in the one graph, and that is deliberate rather than incidental: entering either
 * of them pushes a *new* graph entry, so each visit gets its own view model, and the picker's route
 * then needs to be declared exactly once. There is no path from one form to the other, so the two
 * can never be on the stack together and the shared entry can never hold the wrong one's figures.
 */
@Serializable
data object TransactionFormGraph

/** The category picker, opened from either form and never from the ledger's own list. */
@Serializable
data object CategoryPickerRoute

/**
 * One recurring schedule's details, opened from its row on the Recurring tab.
 *
 * The id is carried as an argument for the same reason a transaction's is: the screen reads the
 * schedule the household actually tapped, so a row that moves when the list re-sorts cannot make this
 * screen show a different schedule's figures.
 */
@Serializable
data class RecurringDetailRoute(val scheduleId: Long)

/** One recurring schedule's occurrence history, opened from its details. */
@Serializable
data class RecurringOccurrencesRoute(val scheduleId: Long)

/**
 * The edit form for one recurring schedule, opened from that schedule's details.
 *
 * The same id again, and for the same reason: the form reads the row it is about to change rather than
 * being handed a copy of it, so what it shows is what the database holds and the write goes back to
 * that row and no other.
 */
@Serializable
data class EditRecurringRoute(val scheduleId: Long)

/** The form that creates a recurring schedule, opened from the Recurring tab's add action. */
@Serializable
data object AddRecurringRoute

/**
 * The route of the **nested graph** that holds the two recurring forms and the category picker they
 * both open.
 *
 * Exactly the shape [TransactionFormGraph] has, and for the same reason: the form and its picker are
 * one flow sharing a single [com.homefinance.app.feature.recurring.RecurringFormViewModel] scoped to
 * this graph's back stack entry, so the category chosen on the picker is the same fact the form
 * renders, with no result passing through navigation. Nothing navigates *to* this route - the flow is
 * entered at one of the two forms - but the graph needs a route like any other destination, because
 * that is what the shared view model is scoped to.
 *
 * Both forms live in the one graph so the picker's route is declared exactly once, and entering either
 * of them pushes a *new* graph entry, so each visit gets its own view model and the shared entry can
 * never hold the wrong one's figures.
 */
@Serializable
data object RecurringFormGraph

/** The category picker the recurring forms open, offering active expense categories only. */
@Serializable
data object RecurringCategoryPickerRoute
