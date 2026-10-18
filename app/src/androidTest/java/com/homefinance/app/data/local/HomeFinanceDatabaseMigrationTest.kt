package com.homefinance.app.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homefinance.app.data.local.migration.MIGRATION_1_2
import com.homefinance.app.data.local.migration.MIGRATION_2_3
import com.homefinance.app.data.local.migration.MIGRATION_3_4
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

    // ----------------------------------------------------------- version 3 -> 4

    @Test
    fun migratingFrom3To4KeepsSettingsCategoriesAndTransactions() {
        createVersion3DatabaseWithALedger()

        helper.runMigrationsAndValidate(DATABASE_NAME, 4, true, MIGRATION_3_4).use { db ->
            // The household's own rows, exactly as they were. A migration that adds two tables must not
            // disturb anything that was already being counted.
            assertEquals(3, rowCount(db, "app_setting"))
            assertEquals(1, rowCount(db, "categories"))
            assertEquals(2, rowCount(db, "transactions"))
            assertEquals("Vipin", settingValue(db, "household_name"))
            assertEquals("Groceries", firstCategoryName(db))
            assertEquals(25_050L, transactionAmount(db, "1"))
            assertEquals("Weekly shop", transactionDescription(db, "1"))
            assertEquals("", settingValue(db, "empty_value"))
        }
    }

    @Test
    fun migratingFrom3To4AddsTheRecurringTablesEmpty() {
        createVersion3DatabaseWithALedger()

        helper.runMigrationsAndValidate(DATABASE_NAME, 4, true, MIGRATION_3_4).use { db ->
            // Empty, deliberately: a migration must never invent a schedule or an expected payment. The
            // household's recurring expenses begin when they add one.
            assertEquals(0, rowCount(db, "recurring_schedules"))
            assertEquals(0, rowCount(db, "recurring_occurrences"))
        }
    }

    @Test
    fun migratingFrom3To4GivesTheOccurrenceTableItsUniqueIndex() {
        createVersion3DatabaseWithALedger()

        helper.runMigrationsAndValidate(DATABASE_NAME, 4, true, MIGRATION_3_4).use { db ->
            db.execSQL(
                "INSERT INTO recurring_schedules " +
                    "(id, name, category_id, amount_paise, frequency, interval_count, start_date, " +
                    "status, created_at, updated_at) " +
                    "VALUES (1, 'Shop rent', 1, 1500000, 'MONTHLY', 1, 20000, 'ACTIVE', 1, 1)",
            )
            db.execSQL(
                "INSERT INTO recurring_occurrences " +
                    "(id, schedule_id, due_date, amount_paise, status, created_at, updated_at) " +
                    "VALUES (1, 1, 20000, 1500000, 'PENDING', 1, 1)",
            )

            // The index is the duplicate authority the whole feature rests on: two occurrences of one
            // schedule on one day must be impossible, whoever asks for them.
            assertTrue(
                "the unique (schedule_id, due_date) index is missing from the upgraded schema",
                hasUniqueIndexOn(db, "recurring_occurrences", "schedule_id", "due_date"),
            )
            assertConstraintRefused(
                db,
                "INSERT INTO recurring_occurrences " +
                    "(schedule_id, due_date, amount_paise, status, created_at, updated_at) " +
                    "VALUES (1, 20000, 100, 'PENDING', 1, 1)",
            )
        }
    }

    @Test
    fun migratingFrom3To4KeepsTheRecurringForeignKeysEnforced() {
        createVersion3DatabaseWithALedger()

        helper.runMigrationsAndValidate(DATABASE_NAME, 4, true, MIGRATION_3_4).use { db ->
            // Foreign keys are a *per-connection* SQLite setting and are off on a bare handle like this
            // one, so enforcement has to be asked for before it can be observed. Room asks for exactly
            // this on every connection it opens, which is why the migrated schema behaves this way in the
            // app - this test is about the keys the migration wrote, not about Room's connection settings.
            db.setForeignKeyConstraintsEnabled(true)

            // No such category, so no such schedule: a schedule is always filed under a real one.
            assertConstraintRefused(
                db,
                "INSERT INTO recurring_schedules " +
                    "(name, category_id, amount_paise, frequency, interval_count, start_date, status, " +
                    "created_at, updated_at) " +
                    "VALUES ('Shop rent', 42, 1500000, 'MONTHLY', 1, 20000, 'ACTIVE', 1, 1)",
            )

            db.execSQL(
                "INSERT INTO recurring_schedules " +
                    "(id, name, category_id, amount_paise, frequency, interval_count, start_date, " +
                    "status, created_at, updated_at) " +
                    "VALUES (1, 'Shop rent', 1, 1500000, 'MONTHLY', 1, 20000, 'ACTIVE', 1, 1)",
            )
            db.execSQL(
                "INSERT INTO recurring_occurrences " +
                    "(id, schedule_id, due_date, amount_paise, status, transaction_id, created_at, updated_at) " +
                    "VALUES (1, 1, 20000, 1500000, 'PAID', 1, 1, 1)",
            )

            // Deleting the expense leaves the occurrence in place, saying it was paid and no longer
            // pointing at anything - rather than silently deleting the record that a bill was settled.
            db.execSQL("DELETE FROM transactions WHERE id = 1")

            assertEquals(1, rowCount(db, "recurring_occurrences"))
            db.query("SELECT status, transaction_id FROM recurring_occurrences WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("PAID", cursor.getString(0))
                assertTrue(cursor.isNull(1))
            }
        }
    }

    @Test
    fun migratingAllTheWayFrom1To4KeepsSettingsAndLandsOnTheCurrentSchema() {
        createVersion1DatabaseWithSettings()

        // The whole chain a device running since the first build would walk, ending on the schema the app
        // actually ships - validated against the exported 4.json.
        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            4,
            true,
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
        ).use { db ->
            assertEquals("Vipin", settingValue(db, "household_name"))
            assertEquals(0, rowCount(db, "categories"))
            assertEquals(0, rowCount(db, "transactions"))
            assertEquals(0, rowCount(db, "recurring_schedules"))
            assertEquals(0, rowCount(db, "recurring_occurrences"))
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

    /**
     * The same ledger as [createVersion2DatabaseWithALedger], written into a **version-3** file so the
     * version-3-to-4 step can be exercised on its own.
     *
     * The settings rows are included as well, because the point of an additive migration is that it
     * leaves *everything* it did not come to change exactly where it was.
     */
    private fun createVersion3DatabaseWithALedger() {
        helper.createDatabase(DATABASE_NAME, 3).use { db ->
            db.execSQL("INSERT INTO app_setting (setting_key, setting_value) VALUES ('currency_code', 'INR')")
            db.execSQL("INSERT INTO app_setting (setting_key, setting_value) VALUES ('household_name', 'Vipin')")
            db.execSQL("INSERT INTO app_setting (setting_key, setting_value) VALUES ('empty_value', '')")
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

    /** True when [table] has a unique index whose columns are exactly [columns]. */
    private fun hasUniqueIndexOn(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
        vararg columns: String,
    ): Boolean = db.query("PRAGMA index_list(`$table`)").use { indexes ->
        // `PRAGMA index_list` answers with (seq, name, unique, origin, partial): the name is column 1
        // and the uniqueness flag is column 2. It is the index's *name* that `PRAGMA index_info` then
        // wants, so reading them the other way round finds nothing and quietly reports a missing index.
        while (indexes.moveToNext()) {
            val unique = indexes.getInt(2) == 1
            val name = indexes.getString(1)
            if (!unique) continue

            val indexed = db.query("PRAGMA index_info(`$name`)").use { info ->
                val names = mutableListOf<String>()
                while (info.moveToNext()) names += info.getString(2)
                names
            }
            if (indexed == columns.toList()) return true
        }
        false
    }

    /** Asserts that [statement] is refused by the database, whatever constraint does the refusing. */
    private fun assertConstraintRefused(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        statement: String,
    ) {
        try {
            db.execSQL(statement)
            org.junit.Assert.fail("Expected the database to refuse: $statement")
        } catch (expected: android.database.sqlite.SQLiteException) {
            // Exactly what was being asserted - a constraint refused it.
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
