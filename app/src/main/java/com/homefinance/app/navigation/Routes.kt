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
 * The route of the **nested graph** that holds the add form and its category picker.
 *
 * It is a graph rather than a plain destination because the two screens are one flow: they share a
 * single [com.homefinance.app.feature.addtransaction.AddTransactionViewModel] scoped to this graph's
 * back stack entry, so the category chosen on the picker is the same fact the form renders, with no
 * result passing through navigation. Nothing navigates *to* this route - the form's own route is
 * where the flow is entered - but the graph needs a route like any other destination, because that
 * is what the shared view model is scoped to and what both screens look each other up by.
 */
@Serializable
data object AddTransactionGraph

/** The category picker, opened from the add form and never from the ledgers' own list. */
@Serializable
data object CategoryPickerRoute
