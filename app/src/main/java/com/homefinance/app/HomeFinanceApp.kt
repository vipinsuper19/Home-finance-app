package com.homefinance.app

import android.app.Application
import com.homefinance.app.data.local.HomeFinanceDatabase
import com.homefinance.app.data.local.RoomTransactionRunner
import com.homefinance.app.data.repository.CategoryRepository
import com.homefinance.app.data.repository.RecurrenceGenerator
import com.homefinance.app.data.repository.RecurringRepository
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

    /**
     * Reads and writes for recurring schedules, their occurrences, and the payments recorded against
     * them.
     *
     * The three collaborators are the whole wiring of the recurring engine: [RecurrenceGenerator]
     * turns a schedule's rule into dated occurrences over the one recurring DAO,
     * [RoomTransactionRunner] is what lets recording a payment write the expense and the link as one
     * atomic unit, and the transaction and category repositories are the gates the payment and the
     * validation go through rather than around.
     */
    val recurringRepository: RecurringRepository by lazy {
        RecurringRepository(
            recurringDao = database.recurringDao(),
            transactions = transactionRepository,
            categories = categoryRepository,
            generator = RecurrenceGenerator(
                recurringDao = database.recurringDao(),
                transactionRunner = transactionRunner,
            ),
            transactionRunner = transactionRunner,
        )
    }

    /** The one transaction scope on the one database; see [RoomTransactionRunner]. */
    private val transactionRunner: RoomTransactionRunner by lazy { RoomTransactionRunner(database) }
}
