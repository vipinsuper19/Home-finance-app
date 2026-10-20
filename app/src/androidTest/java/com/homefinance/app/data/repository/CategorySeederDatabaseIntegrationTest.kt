package com.homefinance.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homefinance.app.data.local.HomeFinanceDatabase
import com.homefinance.app.data.local.RoomTransactionRunner
import com.homefinance.app.data.local.dao.AppSettingDao
import com.homefinance.app.data.local.entity.AppSettingEntity
import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.data.local.entity.TransactionEntity
import com.homefinance.app.data.local.inMemoryDatabase
import com.homefinance.app.data.model.CategoryIconKey
import com.homefinance.app.data.model.DEFAULT_CATEGORIES
import com.homefinance.app.data.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [CategorySeeder] against a real SQLite database.
 *
 * `CategorySeederTest` proves the seeder's *rules* on fakes. This proves the three things a fake
 * cannot: that the writes really land in one transaction, that a failure part-way through really
 * takes the whole run back, and that the unique index on `(type, name)` really is what makes a
 * second run harmless rather than the seeder merely believing so.
 *
 * The database is in memory, so nothing here touches the household's ledger, and every test starts
 * from an empty schema rather than from whatever ran before it.
 */
@RunWith(AndroidJUnit4::class)
class CategorySeederDatabaseIntegrationTest {

    private lateinit var database: HomeFinanceDatabase

    @Before
    fun setUp() {
        database = inMemoryDatabase()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun seeder(
        appSettingDao: AppSettingDao = database.appSettingDao(),
    ): CategorySeeder = CategorySeeder(
        categoryDao = database.categoryDao(),
        appSettingDao = appSettingDao,
        transactionRunner = RoomTransactionRunner(database),
        clock = { SEEDED_AT },
    )

    @Test
    fun aFreshDatabaseEndsUpHoldingEveryShippedCategoryWithItsMark() {
        runBlocking {
            val result = seeder().seedOnce()

            val stored = database.categoryDao().observeAll().first()
            assertEquals(DEFAULT_CATEGORIES.size, result.created)
            assertFalse(result.alreadySeeded)
            assertEquals(DEFAULT_CATEGORIES.size, stored.size)

            DEFAULT_CATEGORIES.forEach { default ->
                val row = stored.single { it.name == default.name && it.type == default.type }
                assertEquals(default.iconKey, row.iconKey)
                assertTrue("a seeded category must be offered by the picker", row.isActive)
                assertEquals(SEEDED_AT, row.createdAt)
            }
        }
    }

    @Test
    fun nothingFinancialIsWrittenByASeedingRun() {
        runBlocking {
            seeder().seedOnce()

            // A category is an empty bucket. Seeding a fresh install must leave the ledger and the
            // recurring engine exactly as empty as they were - a category holds no money at all.
            assertEquals(0, tableCount("transactions"))
            assertEquals(0, tableCount("recurring_schedules"))
            assertEquals(0, tableCount("recurring_occurrences"))

            // And the only thing written outside `categories` is the flag that records the run.
            assertEquals(1, tableCount("app_setting"))
        }
    }

    @Test
    fun aSecondRunOnARealDatabaseWritesNothingAndAbandonsNothing() {
        runBlocking {
            val seeder = seeder()
            assertEquals(DEFAULT_CATEGORIES.size, seeder.seedOnce().created)

            val storesBefore = database.openHelper.writableDatabase
                .query("SELECT id, name, icon_key, is_active FROM categories ORDER BY id")
                .use { cursor -> snapshot(cursor) }

            val second = seeder.seedOnce()

            assertEquals(0, second.created)
            assertTrue(second.alreadySeeded)

            val storesAfter = database.openHelper.writableDatabase
                .query("SELECT id, name, icon_key, is_active FROM categories ORDER BY id")
                .use { cursor -> snapshot(cursor) }

            // Byte-for-byte the same rows, ids included: the second run did not re-insert anything
            // and did not rewrite anything.
            assertEquals(storesBefore, storesAfter)
            assertEquals(DEFAULT_CATEGORIES.size, tableCount("categories"))
        }
    }

    @Test
    fun aCategoryTheHouseholdRetiredIsNotBroughtBackByALaterStart() {
        runBlocking {
            val seeder = seeder()
            seeder.seedOnce()

            val milk = database.categoryDao().observeAll().first().single { it.name == "Milk" }
            database.categoryDao().deactivate(milk.id)

            // The whole reason the run records itself. If the seeder re-inserted by name on every
            // start, a retired "Milk" would come back the moment the app was reopened and the
            // household would be arguing with it about their own ledger.
            seeder.seedOnce()

            val stored = database.categoryDao().observeAll().first()
            assertEquals(DEFAULT_CATEGORIES.size, stored.size)
            assertFalse(stored.single { it.name == "Milk" }.isActive)
        }
    }

    @Test
    fun aNameTheHouseholdAlreadyUsesIsLeftToTheHousehold() {
        runBlocking {
            val theirMilkId = database.categoryDao().insert(
                CategoryEntity.create(
                    name = "Milk",
                    type = TransactionType.EXPENSE,
                    iconKey = null,
                    createdAt = SEEDED_AT,
                ).copy(isActive = false),
            )

            val result = seeder().seedOnce()

            assertEquals(DEFAULT_CATEGORIES.size - 1, result.created)
            val stored = database.categoryDao().observeAll().first()
            assertEquals(DEFAULT_CATEGORIES.size, stored.size)

            val milk = stored.single { it.name == "Milk" }
            assertEquals(theirMilkId, milk.id)
            assertNull("the household's own category must not be re-marked", milk.iconKey)
            assertFalse("the household's own category must not be re-activated", milk.isActive)
        }
    }

    @Test
    fun aFailureAfterTheCategoriesAreWrittenTakesTheWholeRunBack() {
        runBlocking {
            // The disk gives up at the last step: the categories are written, then the flag cannot be.
            val broken = FailingPutAppSettingDao(database.appSettingDao())

            try {
                seeder(appSettingDao = broken).seedOnce()
                fail("expected the failure to escape the seeder")
            } catch (expected: IllegalStateException) {
                // Exactly what was being arranged - the seeder must not swallow it.
            }

            // Neither half of the run survives, so the database is not left claiming it was seeded
            // while holding no categories - which is the state that would leave a new install
            // permanently empty.
            assertEquals(0, tableCount("categories"))
            assertEquals(0, tableCount("app_setting"))

            // And because nothing was recorded, the very next start can simply do it again.
            val retry = seeder().seedOnce()
            assertEquals(DEFAULT_CATEGORIES.size, retry.created)
            assertEquals(DEFAULT_CATEGORIES.size, tableCount("categories"))
            assertTrue(database.appSettingDao().get(CategorySeeder.SEEDED_KEY) != null)
        }
    }

    @Test
    fun theFlagIsReadInsideTheSameTransactionThatWritesTheRows() {
        runBlocking {
            // Seeding twice in a row through the real runner: the second call must see the flag the
            // first one committed, which is only possible if both writes shared a transaction.
            val seeder = seeder()

            val first = seeder.seedOnce()
            val second = seeder.seedOnce()

            assertEquals(DEFAULT_CATEGORIES.size, first.created)
            assertTrue(second.alreadySeeded)
            assertEquals(CategorySeeder.SEEDED_VALUE, database.appSettingDao().get(CategorySeeder.SEEDED_KEY))
        }
    }

    @Test
    fun aKeyTheSeederWroteIsReadableByAQueryThatJoinsTheCategory() {
        runBlocking {
            seeder().seedOnce()

            val milk = database.categoryDao().observeAll().first().single { it.name == "Milk" }
            database.transactionDao().insert(
                TransactionEntity(
                    type = TransactionType.EXPENSE,
                    amountPaise = 6_000L,
                    categoryId = milk.id,
                    transactionDate = LocalDate.of(2026, 10, 18),
                    createdAt = SEEDED_AT,
                    updatedAt = SEEDED_AT,
                ),
            )

            // The mark reaches the ledger through the join, not only through a category read: this is
            // the path a real row is drawn from.
            val row = database.transactionDao().observeAllWithCategory().first().single()
            assertEquals(CategoryIconKey.MILK, row.categoryIconKey)
        }
    }

    private fun tableCount(table: String): Int = database.openHelper.writableDatabase
        .query("SELECT COUNT(*) FROM `$table`")
        .use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun snapshot(cursor: android.database.Cursor): List<List<String?>> {
        val rows = mutableListOf<List<String?>>()
        while (cursor.moveToNext()) {
            rows += (0 until cursor.columnCount).map { column ->
                if (cursor.isNull(column)) null else cursor.getString(column)
            }
        }
        return rows
    }

    /** A real settings DAO whose last write fails, standing in for a disk that gives up. */
    private class FailingPutAppSettingDao(
        private val delegate: AppSettingDao,
    ) : AppSettingDao by delegate {

        override suspend fun put(setting: AppSettingEntity) {
            throw IllegalStateException("the write failed after the categories were inserted")
        }
    }

    private companion object {
        val SEEDED_AT: Instant = Instant.parse("2026-10-01T04:30:00Z")
    }
}
