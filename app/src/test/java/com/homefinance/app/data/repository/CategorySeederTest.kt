package com.homefinance.app.data.repository

import com.homefinance.app.data.local.TransactionRunner
import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.data.model.CategoryIconKey
import com.homefinance.app.data.model.DEFAULT_CATEGORIES
import com.homefinance.app.data.model.DefaultCategory
import com.homefinance.app.data.model.TransactionType
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the one-off seeding run writes, and - more importantly - what it refuses to write.
 *
 * Three properties are asserted here, in the order they matter:
 *
 *  1. a household that has never had categories gets the shipped ones, each wearing its own mark;
 *  2. a run that has already happened does *nothing at all* the next time, so a category the
 *     household retired or renamed can never come back;
 *  3. a category the household already has is left exactly as it is, including one that is retired
 *     and one whose name only collides on the other side of the ledger.
 *
 * The flag and the rows are written inside one [TransactionRunner] block, and the ordering of those
 * two writes is asserted here. The *rollback* - that a failure between them leaves neither behind -
 * needs real SQLite and is asserted against Room in `CategorySeederDatabaseIntegrationTest`.
 */
class CategorySeederTest {

    @Test
    fun aFreshInstallGetsEveryShippedCategoryWithItsMark() = runTest {
        val categories = FakeCategoryDao()

        val result = seeder(categories = categories).seedOnce()

        assertEquals(DEFAULT_CATEGORIES.size, result.created)
        assertFalse(result.alreadySeeded)

        val stored = categories.storedCategories
        assertEquals(DEFAULT_CATEGORIES.size, stored.size)
        DEFAULT_CATEGORIES.forEach { default ->
            val row = stored.single { it.name == default.name && it.type == default.type }
            assertEquals(default.iconKey, row.iconKey)
        }
    }

    @Test
    fun everySeededCategoryIsActiveAndStampedByTheOneClockRead() = runTest {
        val categories = FakeCategoryDao()
        var clockReads = 0

        seeder(categories = categories, clock = { clockReads++; SEEDED_AT }).seedOnce()

        // One timestamp for the batch, from one read of the clock: these rows were written by a
        // single run, and stamping them individually would only invite a test to depend on the
        // difference between them.
        assertEquals(1, clockReads)
        assertTrue(categories.storedCategories.all { it.isActive })
        assertTrue(categories.storedCategories.all { it.createdAt == SEEDED_AT })
    }

    @Test
    fun theRunRecordsItselfSoASecondRunDoesNothing() = runTest {
        val categories = FakeCategoryDao()
        val settings = FakeAppSettingDao()
        val seeder = seeder(categories = categories, settings = settings)

        assertEquals(DEFAULT_CATEGORIES.size, seeder.seedOnce().created)

        val second = seeder.seedOnce()

        assertEquals(0, second.created)
        assertTrue("the second run should have known it was already done", second.alreadySeeded)
        // The strongest statement available: the seeder did not even try. It read the flag, saw it,
        // and returned - which is what makes retiring a seeded category safe forever.
        assertEquals(DEFAULT_CATEGORIES.size, categories.inserted.size)
        assertEquals(DEFAULT_CATEGORIES.size, categories.storedCategories.size)
        assertEquals(1, settings.written.size)
    }

    @Test
    fun aCategoryTheHouseholdAlreadyHasIsLeftExactlyAsItWas() = runTest {
        val theirMilk = CategoryEntity(
            id = 1L,
            name = "Milk",
            type = TransactionType.EXPENSE,
            iconKey = null,
            isActive = false,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        )
        val categories = FakeCategoryDao(listOf(theirMilk))

        val result = seeder(categories = categories).seedOnce()

        // Eleven of the twelve are new; "Milk" was already theirs.
        assertEquals(DEFAULT_CATEGORIES.size - 1, result.created)
        assertEquals(DEFAULT_CATEGORIES.size, categories.storedCategories.size)

        val milk = categories.storedCategories
            .single { it.name == "Milk" && it.type == TransactionType.EXPENSE }
        // Untouched: not re-marked, not re-activated, not restamped. The app fills gaps, it does not
        // take over what the household has already decided.
        assertEquals(theirMilk, milk)
        assertNull(milk.iconKey)
        assertFalse(milk.isActive)
    }

    @Test
    fun aNameOnlyCollidesWithACategoryOfItsOwnType() = runTest {
        // "Salary" is filed as income, and the same word as an expense is a different category as far
        // as the unique index is concerned - so it must not stop the income one from being written.
        val expenseSalary = CategoryEntity(
            id = 1L,
            name = "Salary",
            type = TransactionType.EXPENSE,
            createdAt = SEEDED_AT,
        )
        val categories = FakeCategoryDao(listOf(expenseSalary))

        val result = seeder(categories = categories).seedOnce()

        assertEquals(DEFAULT_CATEGORIES.size, result.created)
        val incomeSalary = categories.storedCategories
            .single { it.name == "Salary" && it.type == TransactionType.INCOME }
        assertEquals(CategoryIconKey.SALARY, incomeSalary.iconKey)
    }

    @Test
    fun aNameAlreadyTakenCaseInsensitivelyIsStillADuplicate() = runTest {
        // The unique index is NOCASE, so "groceries" and the shipped "Groceries" are one name. A
        // seeder that matched case-sensitively would insert a second row here and be refused by the
        // real database - which is to say, it would fail in production and not in the test.
        val theirs = CategoryEntity(
            id = 3L,
            name = "groceries",
            type = TransactionType.EXPENSE,
            createdAt = SEEDED_AT,
        )
        val categories = FakeCategoryDao(listOf(theirs))

        val result = seeder(categories = categories).seedOnce()

        assertEquals(DEFAULT_CATEGORIES.size - 1, result.created)
        assertEquals(
            1,
            categories.storedCategories.count { it.name.equals("groceries", ignoreCase = true) },
        )
    }

    @Test
    fun theShippedSetIsWhatIsWrittenRatherThanSomethingTheCallerInvented() = runTest {
        // A test-only list, so the assertion is about the seeder rather than about whichever names
        // the app happens to ship today.
        val categories = FakeCategoryDao()
        val defaults = listOf(
            DefaultCategory("Test Milk", TransactionType.EXPENSE, CategoryIconKey.MILK),
        )

        val result = categorySeeder(categories = categories, defaults = defaults).seedOnce()

        assertEquals(1, result.created)
        assertEquals("Test Milk", categories.storedCategories.single().name)
    }

    @Test
    fun theFlagIsWrittenInsideTheTransactionThatWroteTheRows() = runTest {
        val categories = FakeCategoryDao()
        val settings = FakeAppSettingDao()
        val runner = RecordingTransactionRunner(settings)

        categorySeeder(categories = categories, settings = settings, runner = runner).seedOnce()

        assertEquals(listOf(ENTERED, EXITED), runner.events)
        // Read at the moment the block returned: the flag was already written, so a rollback could
        // take it back with the rows rather than leaving a database that believes it was seeded.
        assertEquals(
            CategorySeeder.SEEDED_VALUE,
            runner.settingsWhenTheBlockReturned[CategorySeeder.SEEDED_KEY],
        )
    }

    @Test
    fun anAlreadySeededRunWritesNothingAndRecordsNothing() = runTest {
        val categories = FakeCategoryDao()
        val settings = FakeAppSettingDao(
            mapOf(CategorySeeder.SEEDED_KEY to CategorySeeder.SEEDED_VALUE),
        )

        val result = seeder(categories = categories, settings = settings).seedOnce()

        assertTrue(result.alreadySeeded)
        assertTrue(categories.inserted.isEmpty())
        assertTrue(settings.written.isEmpty())
    }

    private fun seeder(
        categories: FakeCategoryDao = FakeCategoryDao(),
        settings: FakeAppSettingDao = FakeAppSettingDao(),
        clock: () -> Instant = { SEEDED_AT },
    ): CategorySeeder = categorySeeder(categories = categories, settings = settings, clock = clock)

    private fun categorySeeder(
        categories: FakeCategoryDao,
        settings: FakeAppSettingDao = FakeAppSettingDao(),
        runner: TransactionRunner = DirectTransactionRunner,
        defaults: List<DefaultCategory> = DEFAULT_CATEGORIES,
        clock: () -> Instant = { SEEDED_AT },
    ): CategorySeeder = CategorySeeder(
        categoryDao = categories,
        appSettingDao = settings,
        transactionRunner = runner,
        defaults = defaults,
        clock = clock,
    )

    /** A [TransactionRunner] that records entering and leaving, so ordering can be asserted. */
    private class RecordingTransactionRunner(
        private val settings: FakeAppSettingDao,
    ) : TransactionRunner {

        val events: MutableList<String> = mutableListOf()

        var settingsWhenTheBlockReturned: Map<String, String> = emptyMap()
            private set

        override suspend fun <T> inTransaction(block: suspend () -> T): T {
            events += ENTERED
            val result = block()
            settingsWhenTheBlockReturned = settings.stored
            events += EXITED
            return result
        }
    }

    private companion object {
        val SEEDED_AT: Instant = Instant.parse("2026-10-01T04:30:00Z")
        const val ENTERED = "entered"
        const val EXITED = "exited"
    }
}
