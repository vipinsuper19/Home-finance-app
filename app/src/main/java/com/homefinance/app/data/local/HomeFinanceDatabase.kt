package com.homefinance.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.homefinance.app.data.local.converter.HomeFinanceTypeConverters
import com.homefinance.app.data.local.dao.CategoryDao
import com.homefinance.app.data.local.dao.TransactionDao
import com.homefinance.app.data.local.entity.AppSettingEntity
import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.data.local.entity.TransactionEntity
import com.homefinance.app.data.local.migration.HOME_FINANCE_MIGRATIONS

/**
 * The single Room database for Home Finance.
 *
 * Version 1 held only [AppSettingEntity]. Version 2 adds the financial tables, [CategoryEntity]
 * and [TransactionEntity], along with their indices and the foreign key between them - see
 * [HOME_FINANCE_MIGRATIONS] for the upgrade itself.
 *
 * Conventions for the persistence layer:
 * - entities live in `com.homefinance.app.data.local.entity`
 * - DAOs live in `com.homefinance.app.data.local.dao`
 * - repositories that wrap the DAOs live in `com.homefinance.app.data.repository`
 *
 * Schema export stays on (see the `room.schemaLocation` KSP argument in the app module): a
 * household ledger must never be wiped to change the schema, so migrations have to be written
 * and tested against a recorded schema history. The exported files under `app/schemas` are part
 * of the source and must not be deleted or edited by hand.
 *
 * [HomeFinanceTypeConverters] is registered here rather than per entity so that the enum, date and
 * timestamp conversions are declared exactly once for the whole database.
 */
@Database(
    entities = [
        AppSettingEntity::class,
        CategoryEntity::class,
        TransactionEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(HomeFinanceTypeConverters::class)
abstract class HomeFinanceDatabase : RoomDatabase() {

    abstract fun categoryDao(): CategoryDao

    abstract fun transactionDao(): TransactionDao

    companion object {

        /**
         * Stable file name on disk. It must never change: to Android, renaming the file looks
         * like the household's data has simply vanished.
         */
        const val NAME = "home_finance.db"

        /**
         * Builds the database. Deliberately not named `getInstance`: the application class owns
         * the only instance, so nothing else can accidentally open a second connection.
         *
         * Every known migration is registered up front, and there is no
         * `fallbackToDestructiveMigration` and no seeding - schema changes ship as real
         * migrations, and the database starts empty.
         */
        fun build(context: Context): HomeFinanceDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                HomeFinanceDatabase::class.java,
                NAME,
            )
                .addMigrations(*HOME_FINANCE_MIGRATIONS)
                .build()
    }
}
