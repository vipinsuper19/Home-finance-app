package com.homefinance.app.data.repository

import com.homefinance.app.data.local.TransactionRunner
import com.homefinance.app.data.local.dao.AppSettingDao
import com.homefinance.app.data.local.dao.CategoryDao
import com.homefinance.app.data.local.entity.AppSettingEntity
import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.data.model.DEFAULT_CATEGORIES
import com.homefinance.app.data.model.DefaultCategory
import java.time.Instant

/**
 * What one run of [CategorySeeder.seedOnce] did.
 *
 * @param created how many categories this run actually wrote. Zero is a perfectly ordinary answer.
 * @param alreadySeeded true when the run did nothing because a previous run had already recorded
 *   itself, as opposed to having tried and found every name taken.
 */
data class SeedResult(
    val created: Int,
    val alreadySeeded: Boolean,
)

/**
 * Writes the categories the app ships with, **once**, the first time the app runs.
 *
 * ### Why once, and how "once" is guaranteed
 *
 * The household must be able to throw these away. A seeder that ran on every start - or one that
 * re-inserted by name - would bring a category back the moment it was retired or renamed, and the
 * app would be arguing with its owner about how their ledger is organised. So the run records itself
 * in `app_setting` under [SEEDED_KEY], and every later start reads that flag and does nothing at all.
 *
 * The flag is written in the *same* transaction as the rows, so the two can never disagree: either
 * the categories and the record of having written them are both on disk, or neither is and the next
 * start tries again. A failed run is therefore safe to repeat, which is what makes it acceptable to
 * fire and forget at start-up.
 *
 * ### Why ignoring duplicates is not the same as overwriting
 *
 * A household upgrading from an earlier build may already have a "Milk" of their own - possibly
 * retired, possibly renamed to something else, possibly filed as income. The insert ignores any name
 * that is already taken for that type, so the existing row is left exactly as it is: its name, its
 * mark, and whether it is active. The app fills gaps, it does not take over.
 *
 * ### What it does not do
 *
 * It writes no transaction, no schedule and no amount. A seeded category is an empty bucket - it
 * holds no money and cannot move a total - so seeding a fresh install still leaves an empty ledger
 * and untouched totals. See [DefaultCategory].
 *
 * Failures are **not** swallowed here: the run either returns what it wrote or throws. The caller
 * decides what a failed start-up seeding is worth saying about it.
 */
class CategorySeeder(
    private val categoryDao: CategoryDao,
    private val appSettingDao: AppSettingDao,
    private val transactionRunner: TransactionRunner,
    private val defaults: List<DefaultCategory> = DEFAULT_CATEGORIES,
    private val clock: () -> Instant = Instant::now,
) {

    /**
     * Writes the shipped categories if they have never been written, and returns what happened.
     *
     * Idempotent: calling it twice in a row, or on every start of the app, writes the categories at
     * most once. Safe to call concurrently - the flag is read and written inside one transaction, so
     * a second run either sees the first run's flag or blocks until it can.
     */
    suspend fun seedOnce(): SeedResult = transactionRunner.inTransaction {
        if (appSettingDao.get(SEEDED_KEY) != null) {
            return@inTransaction SeedResult(created = 0, alreadySeeded = true)
        }

        // One timestamp for the whole batch: these rows were all written by the same run, and
        // stamping them individually would only invite a test to depend on the difference.
        val now = clock()
        val rows = defaults.map { default ->
            CategoryEntity.create(
                name = default.name,
                type = default.type,
                iconKey = default.iconKey,
                createdAt = now,
            )
        }

        val ids = categoryDao.insertIgnoring(rows)
        appSettingDao.put(AppSettingEntity(key = SEEDED_KEY, value = SEEDED_VALUE))

        SeedResult(created = ids.count { it != ALREADY_PRESENT }, alreadySeeded = false)
    }

    companion object {

        /**
         * The `app_setting` key this run records itself under.
         *
         * Its presence - not its value - is the fact being stored, so the value is a constant rather
         * than a version number: if a later build ships a *different* starter list, that is a new
         * decision with a new key, not a reinterpretation of this one.
         */
        const val SEEDED_KEY: String = "default_categories_seeded"

        /** The only value ever written under [SEEDED_KEY]. */
        const val SEEDED_VALUE: String = "1"

        /** What [CategoryDao.insertIgnoring] returns for a row that was already there. */
        private const val ALREADY_PRESENT: Long = -1L
    }
}
