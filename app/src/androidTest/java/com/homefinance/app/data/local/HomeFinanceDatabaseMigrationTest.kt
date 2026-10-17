package com.homefinance.app.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homefinance.app.data.local.migration.MIGRATION_1_2
import com.homefinance.app.data.local.migration.MIGRATION_2_3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The schema upgrades, run the way a real device runs them.
 *
 * `MigrationTestHelper` builds a database from an exported schema, applies the migrations, and then
 * validates the result against the exported schema for the version it claims to have reached. That
 * validation is the point: if a statement ever drifted from what Room expects - a missing index, a
 * differently spelled column, a nullable column made `NOT NULL` - the app would refuse to open its
 * own database, and this test would fail instead of the household discovering it.
 *
 * The upgrade paths are worth testing even though the shipped app has no old installs yet: anyone
 * running an earlier build has one of these files on disk, and whatever they had stored must
 * survive. Two things are asserted every time - that the schema validates, and that the rows that
 * were there before are still there afterwards, unchanged.
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

    // ----------------------------------------------------------- version 2 -> 3

    @Test
    fun migratingFrom2To3KeepsEveryExistingTransaction() {
        createVersion2DatabaseWithALedger()

        helper.runMigrationsAndValidate(DATABASE_NAME, 3, true, MIGRATION_2_3).use { db ->
            // The point of an additive migration: the household's own rows are all still here,
            // with the values they already had.
            assertEquals(1, rowCount(db, "categories"))
            assertEquals(2, rowCount(db, "transactions"))
            assertEquals("Groceries", firstCategoryName(db))
            assertEquals(25_050L, transactionAmount(db, "1"))
            assertEquals("Weekly shop", transactionDescription(db, "1"))
        }
    }

    @Test
    fun migratingFrom2To3GivesExistingRowsNothingTheyDidNotHave() {
        createVersion2DatabaseWithALedger()

        helper.runMigrationsAndValidate(DATABASE_NAME, 3, true, MIGRATION_2_3).use { db ->
            // A row written before notes, quantities and rates existed had none of them, so it must
            // come out of the upgrade with NULL in all three - not with a zero that would claim the
            // entry was bought at a rate of nothing.
            db.query("SELECT notes, quantity_thousandths, rate_paise FROM transactions ORDER BY id")
                .use { cursor ->
                    assertTrue("expected the transaction rows to survive", cursor.moveToFirst())
                    while (!cursor.isAfterLast) {
                        assertTrue(cursor.isNull(0))
                        assertTrue(cursor.isNull(1))
                        assertTrue(cursor.isNull(2))
                        cursor.moveToNext()
                    }
                }
        }
    }

    @Test
    fun migratingAllTheWayFrom1To3KeepsSettingsAndLandsOnTheCurrentSchema() {
        createVersion1DatabaseWithSettings()

        // The whole chain a device that has been running since the first build would walk, ending
        // on the schema the app actually ships - validated against the exported 3.json.
        helper.runMigrationsAndValidate(DATABASE_NAME, 3, true, MIGRATION_1_2, MIGRATION_2_3)
            .use { db ->
                assertEquals("Vipin", settingValue(db, "household_name"))
                assertEquals(0, rowCount(db, "categories"))
                assertEquals(0, rowCount(db, "transactions"))
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

    /**
     * Builds a version-2 database holding one category and two transactions, written through plain
     * SQL so the rows are exactly what a household on the previous build would have on disk.
     */
    private fun createVersion2DatabaseWithALedger() {
        helper.createDatabase(DATABASE_NAME, 2).use { db ->
            db.execSQL(
                "INSERT INTO categories (id, name, type, is_active, created_at) " +
                    "VALUES (1, 'Groceries', 'EXPENSE', 1, 1)",
            )
            db.execSQL(
                "INSERT INTO transactions " +
                    "(id, type, amount_paise, category_id, description, transaction_date, created_at, updated_at) " +
                    "VALUES (1, 'EXPENSE', 25050, 1, 'Weekly shop', 20000, 1, 1)",
            )
            db.execSQL(
                "INSERT INTO transactions " +
                    "(id, type, amount_paise, category_id, description, transaction_date, created_at, updated_at) " +
                    "VALUES (2, 'EXPENSE', 500, 1, NULL, 20001, 1, 1)",
            )
        }
    }

    private fun firstCategoryName(db: androidx.sqlite.db.SupportSQLiteDatabase): String? =
        db.query("SELECT name FROM categories").use { cursor ->
            assertTrue("No categories row survived", cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun transactionAmount(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: String,
    ): Long = db.query("SELECT amount_paise FROM transactions WHERE id = $id").use { cursor ->
        assertTrue("No transactions row with id $id", cursor.moveToFirst())
        cursor.getLong(0)
    }

    private fun transactionDescription(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: String,
    ): String? = db.query("SELECT description FROM transactions WHERE id = $id").use { cursor ->
        assertTrue("No transactions row with id $id", cursor.moveToFirst())
        cursor.getString(0)
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
