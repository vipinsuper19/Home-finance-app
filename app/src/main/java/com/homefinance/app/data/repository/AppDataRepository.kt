package com.homefinance.app.data.repository

import com.homefinance.app.data.local.TransactionRunner
import com.homefinance.app.data.local.dao.AppSettingDao
import com.homefinance.app.data.local.dao.CategoryDao
import com.homefinance.app.data.local.dao.RecurringDao
import com.homefinance.app.data.local.dao.TransactionDao
import com.homefinance.app.data.local.entity.AppSettingEntity

/**
 * "Clear app data", done through the database rather than around it.
 *
 * ### Why not delete the database file
 *
 * Room may be holding the file open, its connection pool may still be reading it, and a deleted file
 * on Android is not necessarily a closed one. Deleting the file would leave the running process writing
 * to a database that no longer exists - and would also throw away the schema, forcing a rebuild on the
 * next start. Every row is therefore deleted through the DAOs, inside one transaction, so the schema
 * survives and the app's open readers simply see the tables empty.
 *
 * ### Dependency order, not cascade
 *
 * Occurrences point at schedules and transactions, transactions point at categories, and those foreign
 * keys are `RESTRICT`. Deleting in the order the schema demands is what keeps the operation from
 * failing halfway; the transaction means a failure would roll all of it back anyway.
 *
 * ### The one row that is written back
 *
 * The category seeder's flag is re-written after the wipe. Without it, the next start would decide
 * that a fresh install had arrived and quietly re-create the twelve shipped categories - which is the
 * opposite of what "clear all data" was asked to do. The app is left with no categories at all, and
 * the add form already offers to create the first one.
 */
class AppDataRepository(
    private val categoryDao: CategoryDao,
    private val transactionDao: TransactionDao,
    private val recurringDao: RecurringDao,
    private val appSettingDao: AppSettingDao,
    private val transactionRunner: TransactionRunner,
) {

    /** Deletes every row of household data and every preference, in one transaction. */
    suspend fun clearAll() {
        transactionRunner.inTransaction {
            recurringDao.deleteAllOccurrences()
            transactionDao.deleteAll()
            recurringDao.deleteAllSchedules()
            categoryDao.deleteAll()
            appSettingDao.deleteAll()
            appSettingDao.put(
                AppSettingEntity(
                    key = CategorySeeder.SEEDED_KEY,
                    value = CategorySeeder.SEEDED_VALUE,
                ),
            )
        }
    }
}
