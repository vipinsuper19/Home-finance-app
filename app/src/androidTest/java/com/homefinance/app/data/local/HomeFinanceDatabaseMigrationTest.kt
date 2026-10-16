package com.homefinance.app.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homefinance.app.data.local.migration.MIGRATION_1_2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The version 1 -> 2 upgrade, run the way a real device runs it.
 *
 * `MigrationTestHelper` builds a version-1 database from the exported `1.json`, applies
 * [MIGRATION_1_2], and then validates the result against the exported `2.json`. That validation is
 * the point: if the migration's `CREATE TABLE` ever drifted from what Room expects - a missing
 * index, a differently spelled column - the app would refuse to open its own database, and this
 * test would fail instead of the household discovering it.
 *
 * The upgrade path is worth testing even though the shipped app has no version-1-off-the-shelf
 * installs yet: anyone running an earlier build has a version-1 file on disk, and their settings
 * must survive.
 */
@RunWith(AndroidJUnit4::class)
class HomeFinanceDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        HomeFinanceDatabase::class.java,
    )

    @Test
    fun migratingFrom1To2KeepsExistingSettings() {
        createVersion1DatabaseWithSettings()

        helper.runMigrationsAndValidate(DATABASE_NAME, 2, true, MIGRATION_1_2).use { db ->
            assertEquals("INR", settingValue(db, "currency_code"))
            assertEquals("Vipin", settingValue(db, "household_name"))
            assertEquals("", settingValue(db, "empty_value"))
        }
    }

    @Test
    fun migratingFrom1To2AddsTheFinancialTablesEmpty() {
        createVersion1DatabaseWithSettings()

        helper.runMigrationsAndValidate(DATABASE_NAME, 2, true, MIGRATION_1_2).use { db ->
            assertEquals(0, rowCount(db, "categories"))
            assertEquals(0, rowCount(db, "transactions"))
        }
    }

    @Test
    fun migratingFrom1To2LeavesAppSettingIntact() {
        createVersion1DatabaseWithSettings()

        helper.runMigrationsAndValidate(DATABASE_NAME, 2, true, MIGRATION_1_2).use { db ->
            assertEquals(3, rowCount(db, "app_setting"))
        }
    }

    /**
     * Builds the database exactly as version 1 shipped it, then writes rows through plain SQL -
     * deliberately not through the current DAOs, which would use the version-2 schema.
     */
    private fun createVersion1DatabaseWithSettings() {
        helper.createDatabase(DATABASE_NAME, 1).use { db ->
            db.execSQL("INSERT INTO app_setting (setting_key, setting_value) VALUES ('currency_code', 'INR')")
            db.execSQL("INSERT INTO app_setting (setting_key, setting_value) VALUES ('household_name', 'Vipin')")
            db.execSQL("INSERT INTO app_setting (setting_key, setting_value) VALUES ('empty_value', '')")
        }
    }

    private fun settingValue(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        key: String,
    ): String? = db
        .query("SELECT setting_value FROM app_setting WHERE setting_key = '$key'")
        .use { cursor ->
            assertTrue("No app_setting row for key '$key'", cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun rowCount(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private companion object {
        const val DATABASE_NAME = "migration-test.db"
    }
}
