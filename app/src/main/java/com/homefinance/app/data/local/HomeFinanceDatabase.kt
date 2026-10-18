package com.homefinance.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.homefinance.app.data.local.converter.HomeFinanceTypeConverters
import com.homefinance.app.data.local.dao.CategoryDao
import com.homefinance.app.data.local.dao.RecurringDao
import com.homefinance.app.data.local.dao.TransactionDao
import com.homefinance.app.data.local.entity.AppSettingEntity
import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.data.local.entity.RecurringOccurrenceEntity
import com.homefinance.app.data.local.entity.RecurringScheduleEntity
import com.homefinance.app.data.local.entity.TransactionEntity
import com.homefinance.app.data.local.migration.HOME_FINANCE_MIGRATIONS

/**
 * The single Room database for Home Finance.
 *
 * Version 1 held only [AppSettingEntity]. Version 2 adds the financial tables, [CategoryEntity]
 * and [TransactionEntity], along with their indices and the foreign key between them - see
 * [HOME_FINANCE_MIGRATIONS] for the upgrade itself. Version 3 adds an entry's notes and the optional
 * quantity and rate it was bought by. Version 4 adds the recurring expense engine,
 * [RecurringScheduleEntity] and [RecurringOccurrenceEntity].
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
        RecurringScheduleEntity::class,
        RecurringOccurrenceEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(HomeFinanceTypeConverters::class)
abstract class HomeFinanceDatabase : RoomDatabase() {

    abstract fun categoryDao(): CategoryDao

    abstract fun transactionDao(): TransactionDao

    abstract fun recurringDao(): RecurringDao

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
         *
         * Constructing the database does **not** open it. The SQLite file is not touched until a
         * DAO is actually queried, which is what keeps application start-up off the disk.
         *
         * @param name the SQLite file to open. Production always takes the default, [NAME]. The
         *   parameter exists so that instrumented tests can open an *isolated* file through this
         *   exact configuration - the migrations included - rather than hand-rolling a second
         *   builder that could quietly drift from what ships. Tests must never pass [NAME].
         */
        fun build(context: Context, name: String = NAME): HomeFinanceDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                HomeFinanceDatabase::class.java,
                name,
            )
                .addMigrations(*HOME_FINANCE_MIGRATIONS)
                .build()
    }
}
