package com.homefinance.app

import android.app.Application
import android.util.Log
import com.homefinance.app.core.util.AndroidDocumentStore
import com.homefinance.app.core.util.DocumentStore
import com.homefinance.app.data.local.HomeFinanceDatabase
import com.homefinance.app.data.local.RoomTransactionRunner
import com.homefinance.app.data.repository.AppDataRepository
import com.homefinance.app.data.repository.BackupRepository
import com.homefinance.app.data.repository.CategoryRepository
import com.homefinance.app.data.repository.CategorySeeder
import com.homefinance.app.data.repository.DataTransferRepository
import com.homefinance.app.data.repository.RecurrenceGenerator
import com.homefinance.app.data.repository.RecurringRepository
import com.homefinance.app.data.repository.ReportRepository
import com.homefinance.app.data.repository.SettingsRepository
import com.homefinance.app.data.repository.TransactionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point for Home Finance.
 *
 * The single Room database and the repositories that wrap it are created lazily and shared for
 * the whole process: `by lazy` is thread-safe, and nothing touches the database file until a DAO
 * is actually queried - the one exception being the seeding run on the first start after
 * installation, which is described beside it. There is deliberately no dependency-injection
 * framework and no network client - Home Finance is strictly offline-first - so this class is also
 * the one place that wires the data layer together.
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
     * Read-only reporting over the same ledger, for the Reports screen.
     *
     * It wraps the transaction DAO's aggregate reads and has no write method of any kind: a report is
     * a picture of money that has already moved, and nothing on that screen is allowed to move any.
     */
    val reportRepository: ReportRepository by lazy {
        ReportRepository(
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

    /**
     * The household's preferences, over the existing `app_setting` table.
     *
     * No second storage mechanism was added: this table already held the "categories were seeded"
     * flag, so it is the one place a preference can live and the one place a clear or a restore has
     * to know about.
     */
    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(database.appSettingDao())
    }

    /** Creates and reads versioned backup files, and replaces the database from one. */
    val backupRepository: BackupRepository by lazy {
        BackupRepository(
            categoryDao = database.categoryDao(),
            transactionDao = database.transactionDao(),
            recurringDao = database.recurringDao(),
            appSettingDao = database.appSettingDao(),
            transactionRunner = transactionRunner,
        )
    }

    /** Exports the ledger as CSV and validates an import back into it. */
    val dataTransferRepository: DataTransferRepository by lazy {
        DataTransferRepository(
            transactionDao = database.transactionDao(),
            categoryDao = database.categoryDao(),
            transactionRunner = transactionRunner,
        )
    }

    /** Deletes every row for "Clear app data", through the DAOs rather than around the database. */
    val appDataRepository: AppDataRepository by lazy {
        AppDataRepository(
            categoryDao = database.categoryDao(),
            transactionDao = database.transactionDao(),
            recurringDao = database.recurringDao(),
            appSettingDao = database.appSettingDao(),
            transactionRunner = transactionRunner,
        )
    }

    /**
     * Reads and writes the one document the household picked through the system file picker.
     *
     * The app never chooses a path or asks for a storage permission; see [AndroidDocumentStore].
     */
    val documentStore: DocumentStore by lazy { AndroidDocumentStore(contentResolver) }

    /**
     * Writes the twelve categories the app ships with, once, on the first start after installation.
     *
     * A fresh ledger has nothing to file an entry under, so the first thing the app does is make the
     * ordinary buckets exist. See [CategorySeeder] for why this can run on every start without ever
     * writing a second time, and for what it deliberately does not do - it creates no transaction and
     * no amount, so totals are untouched.
     */
    private val categorySeeder: CategorySeeder by lazy {
        CategorySeeder(
            categoryDao = database.categoryDao(),
            appSettingDao = database.appSettingDao(),
            transactionRunner = transactionRunner,
        )
    }

    /**
     * The one piece of work the application starts by itself.
     *
     * It is a coroutine on [applicationScope] rather than a main-thread call because it touches the
     * database, and it is deliberately fire-and-forget: a seeding run that fails must not stop the app
     * from opening. Nothing on screen waits for it - the seeder is idempotent and its rows arrive
     * through the same `Flow` reads every other category does, so a household that opens the picker
     * before the write lands simply sees the categories a moment later.
     */
    override fun onCreate() {
        super.onCreate()
        applicationScope.launch { seedDefaultCategories() }
    }

    /**
     * Runs the seeding and absorbs its failure.
     *
     * A failure here is not user-visible and not fatal: the ledger works exactly as well without the
     * shipped categories, and the next start tries again, because the flag that would have stopped it
     * was never written. Recording it in logcat is the most that is owed.
     */
    private suspend fun seedDefaultCategories() {
        try {
            categorySeeder.seedOnce()
        } catch (failure: Exception) {
            Log.w(TAG, "Could not write the default categories; the next start will try again", failure)
        }
    }

    /**
     * A scope tied to the process rather than to a screen, for the one start-up task above.
     *
     * `SupervisorJob` so a failed child cannot cancel the scope, and `Dispatchers.IO` because the first
     * thing this scope does opens the SQLite file. There is no `GlobalScope` anywhere: this is an
     * application-scoped object that dies with the process, and it is the only scope the app owns.
     */
    private val applicationScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        const val TAG = "HomeFinanceApp"
    }
}
