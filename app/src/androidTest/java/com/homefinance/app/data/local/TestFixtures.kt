package com.homefinance.app.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.data.local.entity.RecurringOccurrenceEntity
import com.homefinance.app.data.local.entity.RecurringScheduleEntity
import com.homefinance.app.data.local.entity.TransactionEntity
import com.homefinance.app.data.model.RecurrenceFrequency
import com.homefinance.app.data.model.RecurringOccurrenceStatus
import com.homefinance.app.data.model.RecurringScheduleStatus
import com.homefinance.app.data.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail

/**
 * Shared builders for the instrumented data-layer tests.
 *
 * These tests run against a real in-memory Room database - real SQLite, the real generated DAO
 * implementations, the real constraints - because the interesting guarantees (a unique index, a
 * foreign key, `ORDER BY`) do not exist in a hand-written fake.
 */

internal val TEST_INSTANT: Instant = Instant.parse("2026-10-16T06:30:00Z")

internal val TEST_DATE: LocalDate = LocalDate.of(2026, 10, 15)

/**
 * A throwaway database that lives only in memory, so no test can touch the household's real
 * ledger and none of them depends on the order the others ran in.
 */
internal fun inMemoryDatabase(): HomeFinanceDatabase =
    Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        HomeFinanceDatabase::class.java,
    ).build()

/** A category built the way production code builds one: through the normalising factory. */
internal fun category(
    name: String,
    type: TransactionType,
    isActive: Boolean = true,
): CategoryEntity = CategoryEntity
    .create(name = name, type = type, createdAt = TEST_INSTANT)
    .copy(isActive = isActive)

internal fun transaction(
    categoryId: Long,
    type: TransactionType = TransactionType.EXPENSE,
    amountPaise: Long = 1_000L,
    transactionDate: LocalDate = TEST_DATE,
    description: String? = null,
    notes: String? = null,
    quantityThousandths: Long? = null,
    ratePaise: Long? = null,
): TransactionEntity = TransactionEntity(
    type = type,
    amountPaise = amountPaise,
    categoryId = categoryId,
    description = description,
    notes = notes,
    quantityThousandths = quantityThousandths,
    ratePaise = ratePaise,
    transactionDate = transactionDate,
    createdAt = TEST_INSTANT,
    updatedAt = TEST_INSTANT,
)

/**
 * A recurring schedule built the way production code builds one.
 *
 * The defaults describe a monthly expense on the 5th, so a test that only cares about one thing - a
 * foreign key, an index - does not have to spell out a whole rule to say so.
 */
internal fun schedule(
    categoryId: Long,
    name: String = "Shop rent",
    description: String? = null,
    amountPaise: Long = 1_500_000L,
    frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
    intervalCount: Int = 1,
    startDate: LocalDate = LocalDate.of(2026, 10, 5),
    endDate: LocalDate? = null,
    status: RecurringScheduleStatus = RecurringScheduleStatus.ACTIVE,
    generatedThrough: LocalDate? = null,
): RecurringScheduleEntity = RecurringScheduleEntity(
    name = name,
    description = description,
    categoryId = categoryId,
    amountPaise = amountPaise,
    frequency = frequency,
    intervalCount = intervalCount,
    startDate = startDate,
    endDate = endDate,
    status = status,
    generatedThrough = generatedThrough,
    createdAt = TEST_INSTANT,
    updatedAt = TEST_INSTANT,
)

/** One scheduled occurrence, pending unless a test says otherwise. */
internal fun occurrence(
    scheduleId: Long,
    dueDate: LocalDate = TEST_DATE,
    amountPaise: Long = 1_500_000L,
    status: RecurringOccurrenceStatus = RecurringOccurrenceStatus.PENDING,
    transactionId: Long? = null,
): RecurringOccurrenceEntity = RecurringOccurrenceEntity(
    scheduleId = scheduleId,
    dueDate = dueDate,
    amountPaise = amountPaise,
    status = status,
    transactionId = transactionId,
    createdAt = TEST_INSTANT,
    updatedAt = TEST_INSTANT,
)

/**
 * `MigrationTestHelper`'s existing tests, plus the runtime tests, need a *real* database file as
 * well as an in-memory one.
 *
 * [TEST_DATABASE_NAME] is deliberately not [HomeFinanceDatabase.NAME]: a test must never open -
 * and therefore never create, migrate or write to - the household's own ledger.
 */
internal const val TEST_DATABASE_NAME = "home_finance-test.db"

/**
 * Opens the isolated test file through the **production configuration**, so what is under test is
 * the builder that ships (migrations and all) rather than a second, hand-rolled one.
 */
internal fun isolatedFileDatabase(): HomeFinanceDatabase =
    HomeFinanceDatabase.build(
        context = InstrumentationRegistry.getInstrumentation().targetContext,
        name = TEST_DATABASE_NAME,
    )

/**
 * Removes the isolated test file and its `-wal`/`-journal` siblings, so a run cannot depend on
 * what an earlier run left behind, and so the test device is left as it was found.
 */
internal fun deleteTestDatabase() {
    InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(TEST_DATABASE_NAME)
}

/**
 * Asserts that [block] is refused by a database constraint.
 *
 * Any other failure - including the block unexpectedly succeeding - propagates, so this cannot
 * pass by accident.
 */
internal fun assertConstraintViolation(block: suspend () -> Unit) {
    try {
        runBlocking { block() }
        fail("Expected the database to refuse this statement, but it succeeded")
    } catch (expected: SQLiteConstraintException) {
        // Exactly what was being asserted.
    }
}
