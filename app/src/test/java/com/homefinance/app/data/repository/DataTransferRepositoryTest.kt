package com.homefinance.app.data.repository

import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.data.local.entity.TransactionEntity
import com.homefinance.app.data.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CSV export and the two-step import.
 *
 * The tests that matter are the ones about honesty: the export must contain the rows that are really
 * there, the preview must say how many rows will arrive and why the rest will not, and confirmation
 * must write only what the preview showed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataTransferRepositoryTest {

    private val instant = Instant.parse("2026-11-05T10:15:30Z")

    private val groceries = CategoryEntity
        .create("Groceries", TransactionType.EXPENSE, "cart", instant)
        .copy(id = 10L)

    private val salary = CategoryEntity
        .create("Salary", TransactionType.INCOME, null, instant)
        .copy(id = 11L)

    private class Store(
        categories: List<CategoryEntity> = emptyList(),
        transactions: List<TransactionEntity> = emptyList(),
    ) {
        val categoryDao = FakeCategoryDao(categories)
        val transactionDao = FakeTransactionDao(
            categoryNames = categories.associate { it.id to it.name },
            initial = transactions,
        )

        val repository = DataTransferRepository(
            transactionDao = transactionDao,
            categoryDao = categoryDao,
            transactionRunner = DirectTransactionRunner,
            clock = { Instant.parse("2026-11-05T12:00:00Z") },
        )
    }

    private fun storeWithLedger() = Store(
        categories = listOf(groceries, salary),
        transactions = listOf(
            TransactionEntity(
                id = 1L,
                type = TransactionType.EXPENSE,
                amountPaise = 25_050L,
                categoryId = 10L,
                description = "Milk, bread",
                notes = "Said \"weekly\"",
                quantityThousandths = 1_500L,
                ratePaise = 16_700L,
                transactionDate = LocalDate.of(2026, 11, 5),
                createdAt = instant,
                updatedAt = instant,
            ),
            TransactionEntity(
                id = 2L,
                type = TransactionType.INCOME,
                amountPaise = 5_000_000L,
                categoryId = 11L,
                description = null,
                notes = null,
                quantityThousandths = null,
                ratePaise = null,
                transactionDate = LocalDate.of(2026, 11, 1),
                createdAt = instant,
                updatedAt = instant,
            ),
        ),
    )

    // ------------------------------------------------------------------ export

    @Test
    fun theExportIsTheDocumentedHeaderAndTheRealRows() = runTest {
        val export = storeWithLedger().repository.exportCsv()

        assertEquals(2, export.rows)
        val lines = export.text.trimEnd().lines()
        assertEquals("date,type,category,description,amount,currency,quantity,rate,notes", lines.first())
        assertTrue(
            export.text.contains(
                "2026-11-05,EXPENSE,Groceries,\"Milk, bread\",250.50,INR,1.500,167.00," +
                    "\"Said \"\"weekly\"\"\"",
            ),
        )
        assertTrue(export.text.contains("2026-11-01,INCOME,Salary,,50000.00,INR,,,"))
    }

    @Test
    fun theExportUsesIsoDatesWhateverTheDisplayPreferenceIs() = runTest {
        // The display layout is deliberately not consulted: a file must import after the household
        // changes how dates are drawn.
        val export = storeWithLedger().repository.exportCsv()

        assertTrue(export.text.contains("2026-11-05"))
        assertTrue(!export.text.contains("5 Nov 2026"))
        assertTrue(!export.text.contains("05/11/2026"))
    }

    @Test
    fun aRowWhoseCategoryVanishedFailsTheExportInsteadOfBeingOmitted() = runTest {
        val store = Store(
            categories = emptyList(),
            transactions = listOf(
                TransactionEntity(
                    id = 1L,
                    type = TransactionType.EXPENSE,
                    amountPaise = 100L,
                    categoryId = 99L,
                    transactionDate = LocalDate.of(2026, 11, 5),
                    createdAt = instant,
                    updatedAt = instant,
                ),
            ),
        )

        val failure = runCatching { store.repository.exportCsv() }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("category that does not exist"))
    }

    // ------------------------------------------------------------------ import: preview

    @Test
    fun aValidFilePreviewsTheRowsItWouldWrite() = runTest {
        val text = """
            date,type,category,description,amount,currency
            2026-11-05,EXPENSE,groceries,Shop,250.50,INR
            2026-11-06,INCOME,Salary,November,50000,INR
        """.trimIndent()

        val plan = storeWithLedger().repository.prepareImport(text)

        assertTrue(plan.isValid)
        assertEquals(2, plan.rows.size)
        assertEquals(0, plan.duplicates)
        assertTrue(plan.problems.isEmpty())
        assertEquals(10L, plan.rows.first { it.transaction.categoryName == "groceries" }.categoryId)
        assertEquals(11L, plan.rows.first { it.transaction.type == TransactionType.INCOME }.categoryId)
    }

    @Test
    fun aRowAlreadyInTheLedgerIsCountedAsADuplicateNotWritten() = runTest {
        val store = storeWithLedger()
        val text = "date,type,category,description,amount\n" +
            "2026-11-01,INCOME,Salary,,50000.00\n"

        val plan = store.repository.prepareImport(text)

        assertTrue(plan.rows.isEmpty())
        assertEquals(1, plan.duplicates)
    }

    @Test
    fun twoIdenticalRowsInOneFileAreOneRowAndOneDuplicate() = runTest {
        val text = "date,type,category,description,amount\n" +
            "2026-11-05,EXPENSE,Groceries,Shop,250\n" +
            "2026-11-05,EXPENSE,Groceries,Shop,250\n"

        val plan = Store(listOf(groceries)).repository.prepareImport(text)

        assertEquals(1, plan.rows.size)
        assertEquals(1, plan.duplicates)
    }

    @Test
    fun anUnknownCategoryIsAStatedProblemAndNothingElse() = runTest {
        val text = "date,type,category,description,amount\n" +
            "2026-11-05,EXPENSE,Unicorns,Shop,250\n" +
            "2026-11-06,EXPENSE,Groceries,Shop,250\n"

        val plan = storeWithLedger().repository.prepareImport(text)

        assertEquals(1, plan.rows.size)
        assertEquals(1, plan.problems.size)
        assertTrue(plan.problems.single().contains("no expense category called \"Unicorns\""))
    }

    @Test
    fun aRowThatFailsItsOwnValuesIsReportedWithItsLineNumber() = runTest {
        val text = "date,type,category,description,amount\n" +
            "2026-11-05,EXPENSE,Groceries,Shop,not money\n" +
            "2026-11-06,EXPENSE,Groceries,Shop,250\n"

        val plan = storeWithLedger().repository.prepareImport(text)

        assertEquals(1, plan.rows.size)
        assertTrue(plan.problems.single().startsWith("Row 2:"))
    }

    @Test
    fun aFileThatIsNotATransactionExportIsUnreadableAndSaysWhy() = runTest {
        val plan = storeWithLedger().repository.prepareImport("name,value\nMilk,20")

        assertNull(plan.rows.firstOrNull())
        assertNotNull(plan.unreadable)
        assertTrue(plan.unreadable!!.contains("\"date\""))
        assertTrue(plan.unreadable!!.contains("\"type\""))
    }

    // ------------------------------------------------------------------ import: commit

    @Test
    fun confirmingThePreviewWritesExactlyItsRows() = runTest {
        val store = Store(listOf(groceries))
        val plan = store.repository.prepareImport(
            "date,type,category,description,amount\n" +
                "2026-11-05,EXPENSE,Groceries,Shop,250.50\n",
        )

        val result = store.repository.commitImport(plan)

        assertEquals(1, result.inserted)
        assertEquals(0, result.skippedDuplicates)
        val written = store.transactionDao.getAll().single()
        assertEquals(25_050L, written.amountPaise)
        assertEquals(LocalDate.of(2026, 11, 5), written.transactionDate)
        assertEquals(10L, written.categoryId)
        // A CSV carries no created/updated time, so the import stamps the moment it wrote the row.
        assertEquals(Instant.parse("2026-11-05T12:00:00Z"), written.createdAt)
    }

    @Test
    fun aRowThatLandedBetweenPreviewAndConfirmationIsSkippedNotDoubled() = runTest {
        val store = Store(listOf(groceries))
        val text = "date,type,category,description,amount\n" +
            "2026-11-05,EXPENSE,Groceries,Shop,250.50\n"
        val plan = store.repository.prepareImport(text)

        // Somebody recorded the same payment while the preview was on screen.
        store.repository.commitImport(plan)
        val second = store.repository.commitImport(plan)

        assertEquals(0, second.inserted)
        assertEquals(1, second.skippedDuplicates)
        assertEquals(1, store.transactionDao.getAll().size)
    }

    @Test
    fun committingAnEmptyPlanWritesNothing() = runTest {
        val store = Store(listOf(groceries))
        val result = store.repository.commitImport(
            store.repository.prepareImport("date,type,category,amount\n"),
        )

        assertEquals(0, result.inserted)
        assertEquals(0, result.skippedDuplicates)
        assertTrue(store.transactionDao.getAll().isEmpty())
    }

    @Test
    fun theFileIsMatchedByColumnNameSoOrderAndExtraColumnsDoNotMatter() = runTest {
        val text = "amount,category,type,date,ignored\n" +
            "250.50,Groceries,EXPENSE,2026-11-05,whatever\n"

        val plan = Store(listOf(groceries)).repository.prepareImport(text)

        assertEquals(1, plan.rows.size)
        assertEquals(25_050L, plan.rows.single().transaction.amountPaise)
    }

    @Test
    fun aNonRupeeCurrencyIsRefusedRatherThanRelabelled() = runTest {
        val text = "date,type,category,amount,currency\n" +
            "2026-11-05,EXPENSE,Groceries,250,USD\n"

        val plan = Store(listOf(groceries)).repository.prepareImport(text)

        assertTrue(plan.rows.isEmpty())
        assertTrue(plan.problems.single().contains("not supported"))
    }
}
