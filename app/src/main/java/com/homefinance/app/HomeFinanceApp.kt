package com.homefinance.app

import android.app.Application
import com.homefinance.app.data.local.HomeFinanceDatabase
import com.homefinance.app.data.repository.CategoryRepository
import com.homefinance.app.data.repository.TransactionRepository

/**
 * Application entry point for Home Finance.
 *
 * The single Room database and the repositories that wrap it are created lazily and shared for
 * the whole process: `by lazy` is thread-safe, and nothing touches the database file until a DAO
 * is actually queried. There is deliberately no dependency-injection framework and no network
 * client - Home Finance is strictly offline-first - so this class is also the one place that
 * wires the data layer together.
 */
class HomeFinanceApp : Application() {

    /**
     * The one and only database instance, shared for the lifetime of the process. Feature code
     * obtains it from the application context; nothing else should call
     * [HomeFinanceDatabase.build].
     */
    val database: HomeFinanceDatabase by lazy { HomeFinanceDatabase.build(this) }

    val categoryRepository: CategoryRepository by lazy {
        CategoryRepository(database.categoryDao())
    }

    val transactionRepository: TransactionRepository by lazy {
        TransactionRepository(
            transactionDao = database.transactionDao(),
            categoryDao = database.categoryDao(),
        )
    }
}
