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
 * Version 3 -> 4: the recurring expense engine's two tables.
 *
 * `recurring_schedules` holds a household's plans - rent on the 5th, milk every day, the cylinder
 * every two months - and `recurring_occurrences` holds the dated occurrences each plan produces, one
 * per due date, each carrying what became of it.
 *
 * Both tables start **empty**, and nothing existing is touched: `app_setting`, `categories` and
 * `transactions` are not read, written or altered in any way, so a household's whole ledger and every
 * category they have survive the upgrade exactly as they were. No schedule is invented for anyone -
 * a table of made-up bills would be as bad as a table of made-up expenses.
 *
 * The occurrence table carries two foreign keys, and both are deliberate:
 *
 *  - to `recurring_schedules`, `ON DELETE RESTRICT`, so a schedule with history cannot be deleted out
 *    from under the occurrences that point at it (the app retires schedules instead of deleting
 *    them); and
 *  - to `transactions`, `ON DELETE SET NULL`, so deleting an expense from the ledger - which the
 *    household is free to do - leaves the occurrence honestly saying the payment *was* recorded while
 *    no longer claiming a row that is gone. `RESTRICT` here would have silently broken deleting a
 *    transaction from the ledger, which is a shipped feature.
 *
 * The unique index on `(schedule_id, due_date)` is the duplicate authority the whole engine relies
 * on: with it, generating a schedule's occurrences can be run twice, or interrupted and re-run, and a
 * bill can never be generated twice.
 *
 * The statements deliberately mirror, character for character, the SQL Room generates for the
 * version-4 entities, and `HomeFinanceDatabaseMigrationTest` validates the result against the
 * exported `4.json` on a device. Room compares the migrated schema to that file column by column,
 * foreign key by foreign key and index by index, so a typo here would be caught before it could stop
 * a household opening their own database.
 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `recurring_schedules` " +
                "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`description` TEXT, " +
                "`category_id` INTEGER NOT NULL, " +
                "`amount_paise` INTEGER NOT NULL, " +
                "`frequency` TEXT NOT NULL, " +
                "`interval_count` INTEGER NOT NULL, " +
                "`start_date` INTEGER NOT NULL, " +
                "`end_date` INTEGER, " +
                "`status` TEXT NOT NULL, " +
                "`generated_through` INTEGER, " +
                "`created_at` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, " +
                "FOREIGN KEY(`category_id`) REFERENCES `categories`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE RESTRICT )",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_recurring_schedules_category_id` " +
                "ON `recurring_schedules` (`category_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_recurring_schedules_status` " +
                "ON `recurring_schedules` (`status`)",
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `recurring_occurrences` " +
                "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`schedule_id` INTEGER NOT NULL, " +
                "`due_date` INTEGER NOT NULL, " +
                "`amount_paise` INTEGER NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`transaction_id` INTEGER, " +
                "`created_at` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, " +
                "FOREIGN KEY(`schedule_id`) REFERENCES `recurring_schedules`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE RESTRICT , " +
                "FOREIGN KEY(`transaction_id`) REFERENCES `transactions`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE SET NULL )",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_recurring_occurrences_schedule_id_due_date` " +
                "ON `recurring_occurrences` (`schedule_id`, `due_date`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_recurring_occurrences_transaction_id` " +
                "ON `recurring_occurrences` (`transaction_id`)",
        )
    }
}

/**
 * Version 4 -> 5: gives a category a stored mark.
 *
 * One additive statement. `icon_key` is **nullable and has no default**, and that is the whole of the
 * policy: a category that existed before this version has never had a mark chosen for it, and `NULL`
 * is exactly that fact. The app reads `NULL` as "nobody chose one" and derives a mark from the name at
 * the moment it draws it - which is what every category did in version 4 - so an upgrade changes no
 * picture on any screen, and no row is rewritten to make it look as though someone had chosen.
 *
 * The twelve categories the app ships with are given their marks by
 * [com.homefinance.app.data.repository.CategorySeeder] on the next start, not by this migration. A
 * migration is the wrong place for it: it would have to invent names a household may already have,
 * and it would run on a database whose owner never asked for extra categories. That decision is
 * recorded once, in `app_setting`, by the seeder.
 *
 * Nothing is dropped and nothing is rewritten, so this upgrade cannot lose a ledger. The statement
 * mirrors, character for character, the SQL Room generates for the version-5 entity, and
 * `HomeFinanceDatabaseMigrationTest` validates the result against the exported `5.json`.
 */
val MIGRATION_4_5: Migration = object : Migration(4, 5) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `categories` ADD COLUMN `icon_key` TEXT")
    }
}

/**
 * Version 5 -> 6: a schedule's optional unit price.
 *
 * Two additive statements. `unit` and `rate_paise` are both **nullable and have no defaults**, and
 * that is the whole of the policy: a schedule written before this version was not priced by unit, and
 * `NULL` is exactly that fact. Nothing is back-filled, no amount is recomputed and no occurrence is
 * touched - an upgraded household sees "Monthly • ₹15,000.00" on every row it already had, which is
 * what it saw before.
 *
 * The pair is the *unit price the household read off the bill* ("₹60 a litre"), not a second opinion
 * about what an occurrence is worth: `amount_paise` stays the expected figure, for the same reason it
 * always was. So there is no row here that needs its amount rewritten to agree with a rate, and no
 * migration could get it wrong even if it tried.
 *
 * Nothing is dropped and nothing is rewritten, so this upgrade cannot lose a schedule. The statements
 * mirror, character for character, the SQL Room generates for the version-6 entity, and
 * `HomeFinanceDatabaseMigrationTest` validates the result against the exported `6.json`.
 */
val MIGRATION_5_6: Migration = object : Migration(5, 6) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `recurring_schedules` ADD COLUMN `unit` TEXT")
        db.execSQL("ALTER TABLE `recurring_schedules` ADD COLUMN `rate_paise` INTEGER")
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
val HOME_FINANCE_MIGRATIONS: Array<Migration> =
    arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
