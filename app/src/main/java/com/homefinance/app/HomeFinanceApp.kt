package com.homefinance.app

import android.app.Application
import com.homefinance.app.data.local.HomeFinanceDatabase

/**
 * Application entry point for Home Finance.
 *
 * The single Room database is created lazily and shared for the whole process: `by lazy` is
 * thread-safe, and nothing touches the file until a DAO is actually queried. There is
 * deliberately no dependency-injection framework and no network client - Home Finance is
 * strictly offline-first.
 */
class HomeFinanceApp : Application() {

    /**
     * The one and only database instance, shared for the lifetime of the process. Feature code
     * obtains it from the application context; nothing else should call
     * [HomeFinanceDatabase.build].
     */
    val database: HomeFinanceDatabase by lazy { HomeFinanceDatabase.build(this) }
}
