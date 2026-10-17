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
