package com.homefinance.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.homefinance.app.data.local.entity.AppSettingEntity

/**
 * The single Room database for Home Finance.
 *
 * Task 2.1 holds no financial tables yet. Room will not compile a database that declares no
 * entities, so [AppSettingEntity] - a non-financial key/value preference row - is present to
 * give the schema a real version 1. Later tasks add the financial tables, DAOs and tested
 * migrations on top of this foundation.
 *
 * Conventions for the persistence layer:
 * - entities live in `com.homefinance.app.data.local.entity`
 * - DAOs live in `com.homefinance.app.data.local.dao`
 * - repositories that wrap the DAOs live in `com.homefinance.app.data.repository`
 *
 * Schema export stays on (see the `room.schemaLocation` KSP argument in the app module): a
 * household ledger must never be wiped to change the schema, so migrations have to be written
 * and tested against a recorded schema history.
 */
@Database(
    entities = [AppSettingEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class HomeFinanceDatabase : RoomDatabase() {

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
         * No `fallbackToDestructiveMigration` and no seeding - schema changes ship as real
         * migrations, and the database starts empty.
         */
        fun build(context: Context): HomeFinanceDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                HomeFinanceDatabase::class.java,
                NAME,
            ).build()
    }
}
