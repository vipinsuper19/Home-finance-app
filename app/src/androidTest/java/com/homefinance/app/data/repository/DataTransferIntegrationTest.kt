package com.homefinance.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homefinance.app.data.local.HomeFinanceDatabase
import com.homefinance.app.data.local.RoomTransactionRunner
import com.homefinance.app.data.local.category
import com.homefinance.app.data.local.inMemoryDatabase
import com.homefinance.app.data.local.transaction
import com.homefinance.app.data.model.TransactionCsv
import com.homefinance.app.data.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CSV out of, and back into, a real SQLite ledger.
 *
 * The JVM tests pin the escaping and the validation rules. What this adds is the join: an export
 * names its category, the import finds that category in a real table (with its NOCASE comparison),
 * and the amount arrives as the same integer paise the form would have written.
 */
@RunWith(AndroidJUnit4::class)
class DataTransferIntegrationTest {

    private lateinit var source: HomeFinanceDatabase
    private lateinit var target: HomeFinanceDatabase

    private val instant = Instant.parse("2026-11-05T10:15:30Z")

    @Before
    fun setUp() {
        source = inMemoryDatabase()
        target = inMemoryDatabase()
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
    }

    private fun repository(database: HomeFinanceDatabase) = DataTransferRepository(
        transactionDao = database.transactionDao(),
        categoryDao = database.categoryDao(),
        transactionRunner = RoomTransactionRunner(database),
        clock = { instant },
    )

    private fun seedCategories(database: HomeFinanceDatabase) = runBlocking {
        database.categoryDao().insertAll(
            listOf(
                category("Groceries", TransactionType.EXPENSE).copy(id = 10L),
                category("Salary", TransactionType.INCOME).copy(id = 11L),
            ),
        )
    }

    @Test
    fun anExportRoundTripsThroughARealImport() = runBlocking {
        seedCategories(source)
        source.transactionDao().insertAll(
            listOf(
                transaction(
                    categoryId = 10L,
                    amountPaise = 25_050L,
                    description = "Milk, bread",
                    notes = "Said \"weekly\"",
                    transactionDate = LocalDate.of(2026, 11, 5),
                ),
                transaction(
                    categoryId = 11L,
                    type = TransactionType.INCOME,
                    amountPaise = 5_000_000L,
                    transactionDate = LocalDate.of(2026, 11, 1),
                ),
            ),
        )

        val export = repository(source).exportCsv()
        assertEquals(2, export.rows)

        seedCategories(target)
        val plan = repository(target).prepareImport(export.text)

        assertTrue(plan.problems.isEmpty())
        assertEquals(2, plan.rows.size)
        assertEquals(0, plan.duplicates)

        val result = repository(target).commitImport(plan)

        assertEquals(2, result.inserted)
        val stored = target.transactionDao().getAll()
        assertEquals(listOf(25_050L, 5_000_000L), stored.map { it.amountPaise }.sorted())
        val dinner = stored.first { it.amountPaise == 25_050L }
        assertEquals("Milk, bread", dinner.description)
        assertEquals("Said \"weekly\"", dinner.notes)
        assertEquals(LocalDate.of(2026, 11, 5), dinner.transactionDate)
        assertEquals(10L, dinner.categoryId)
    }

    @Test
    fun importingTheSameExportTwiceSkipsEveryRowTheSecondTime() = runBlocking {
        seedCategories(source)
        source.transactionDao().insert(
            transaction(
                categoryId = 10L,
                amountPaise = 25_050L,
                description = "Milk, bread",
                transactionDate = LocalDate.of(2026, 11, 5),
            ),
        )

        val export = repository(source).exportCsv()
        val plan = repository(source).prepareImport(export.text)

        assertTrue(plan.rows.isEmpty())
        assertEquals(1, plan.duplicates)
        assertEquals(1, source.transactionDao().getAll().size)
    }

    @Test
    fun aRowNamingACategoryThatDoesNotExistIsNotWritten() = runBlocking {
        seedCategories(source)
        val text = "date,type,category,description,amount\n" +
            "2026-11-05,EXPENSE,Unicorns,Shop,250\n"

        val plan = repository(source).prepareImport(text)

        assertTrue(plan.rows.isEmpty())
        assertTrue(plan.problems.single().contains("Unicorns"))
        assertEquals(0, source.transactionDao().getAll().size)
    }

    @Test
    fun theExportedHeaderIsTheDocumentedOne() = runBlocking {
        val export = repository(source).exportCsv()

        assertEquals(TransactionCsv.HEADER, export.text.trimEnd().lines().single())
        assertEquals(0, export.rows)
    }
}
