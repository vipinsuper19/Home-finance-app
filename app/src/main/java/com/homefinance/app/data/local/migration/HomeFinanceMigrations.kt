package com.homefinance.app.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 1 -> 2: introduces the financial tables.
 *
 * Version 1 held only `app_setting`. This adds `categories` and `transactions`, together with
 * their indices and the foreign key that stops a category from being deleted out from under its
 * transactions.
 *
 * `app_setting` is not touched at all, so whatever the household had already stored - the display
 * currency, and anything saved before the financial features existed - survives the upgrade
 * untouched. No rows are written to the new tables either: the ledger starts empty, because
 * inventing placeholder amounts would be inventing a household's finances.
 *
 * The statements below deliberately mirror, character for character, the SQL that Room generates
 * for the version-2 entities. Room validates the result of every migration against the exported
 * schema in `app/schemas` when the database is opened, and a mismatch (a missing index, say)
 * would leave the app unable to open its own database. `HomeFinanceDatabaseMigrationTest` runs
 * that same validation on a device.
 *
 * Public rather than private only so that `HomeFinanceDatabaseMigrationTest` can hand this exact
 * object to `MigrationTestHelper`; there is no production caller other than
 * [HOME_FINANCE_MIGRATIONS] below.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `categories` " +
                "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL COLLATE NOCASE, " +
                "`type` TEXT NOT NULL, " +
                "`is_active` INTEGER NOT NULL, " +
                "`created_at` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_type_name` " +
                "ON `categories` (`type`, `name`)",
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `transactions` " +
                "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`type` TEXT NOT NULL, " +
                "`amount_paise` INTEGER NOT NULL, " +
                "`category_id` INTEGER NOT NULL, " +
                "`description` TEXT, " +
                "`transaction_date` INTEGER NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, " +
                "FOREIGN KEY(`category_id`) REFERENCES `categories`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE RESTRICT )",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_transactions_category_id` " +
                "ON `transactions` (`category_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_transactions_transaction_date` " +
                "ON `transactions` (`transaction_date`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_transactions_type_transaction_date` " +
                "ON `transactions` (`type`, `transaction_date`)",
        )
    }
}

/**
 * Version 2 -> 3: notes, and the quantity and rate a milk-like entry is recorded by.
 *
 * The add form asks for a description *and* notes, and offers a switch that records what was bought
 * and at what price. Neither existed in version 2, so this adds three **nullable** columns.
 *
 * `ALTER TABLE ... ADD COLUMN` is chosen over the table-rebuild recipe on purpose: SQLite documents
 * it as an O(1) metadata change that rewrites nothing, and every existing row keeps its values and
 * takes `NULL` in the three new columns. A rebuild (`CREATE TABLE`/`INSERT SELECT`/`DROP TABLE`/
 * `RENAME`) would copy the household's whole ledger to disk for no gain and would be the one place a
 * migration could actually lose a row. No row is written or updated here either: an entry that had no
 * quantity before must not be given a made-up one.
 *
 * The statements deliberately mirror, character for character, the SQL Room generates for the
 * version-3 entity, and `HomeFinanceDatabaseMigrationTest` validates the result against the exported
 * `3.json` on a device.
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {

    override fun migrate(db: SupportSQLiteDatabase) {
        // No DEFAULT: a nullable column with no declared default matches the entity, where all three
        // fields default to null in Kotlin rather than in SQL.
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `notes` TEXT")
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `quantity_thousandths` INTEGER")
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `rate_paise` INTEGER")
    }
}

/**
 * Every schema migration the app knows about, oldest first.
 *
 * [com.homefinance.app.data.local.HomeFinanceDatabase.build] registers this whole array, so adding
 * a migration above and listing it here is all that is needed to ship one - there is no second
 * place to remember to update.
 *
 * There is no `fallbackToDestructiveMigration` anywhere in the project, on purpose. Home Finance
 * holds a household's ledger, and the alternative to writing a migration is asking the user to
 * lose it.
 */
val HOME_FINANCE_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
