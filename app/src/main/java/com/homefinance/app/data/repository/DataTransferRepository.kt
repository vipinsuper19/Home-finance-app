package com.homefinance.app.data.repository

import com.homefinance.app.data.local.TransactionRunner
import com.homefinance.app.data.local.dao.CategoryDao
import com.homefinance.app.data.local.dao.TransactionDao
import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.data.local.entity.TransactionEntity
import com.homefinance.app.data.model.CsvReadResult
import com.homefinance.app.data.model.CsvRow
import com.homefinance.app.data.model.CsvTransaction
import com.homefinance.app.data.model.TransactionCsv
import com.homefinance.app.data.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/** One row that survived validation, with the category it resolved to. */
data class ImportRow(val rowNumber: Int, val transaction: CsvTransaction, val categoryId: Long)

/**
 * Everything the household is shown before an import is committed.
 *
 * [rows] are the only rows that would be written; [duplicates] counts rows that are already in the
 * ledger (or repeat an earlier row of the same file), and [problems] states why each remaining row
 * was refused. [unreadable] is set instead when the file is not a transaction CSV at all.
 *
 * Showing this first is what makes the import safe: nothing is written until the household has seen
 * how many rows will arrive, how many are already there, and what is wrong with the rest.
 */
data class ImportPlan(
    val rows: List<ImportRow> = emptyList(),
    val duplicates: Int = 0,
    val problems: List<String> = emptyList(),
    val unreadable: String? = null,
) {
    val isValid: Boolean get() = unreadable == null && rows.isNotEmpty()

    val reportedProblems: List<String> get() = problems.take(MAX_REPORTED_PROBLEMS)

    companion object {
        const val MAX_REPORTED_PROBLEMS: Int = 8
    }
}

/** What a confirmed import actually wrote. */
data class ImportResult(val inserted: Int, val skippedDuplicates: Int)

/** What an export wrote. */
data class CsvExport(val text: String, val rows: Int)

/**
 * Exports the ledger as the documented CSV, and imports one back.
 *
 * ### Why the import is two steps
 *
 * [prepareImport] reads the file, resolves categories and finds duplicates without writing anything;
 * [commit] then writes only the rows that passed, inside one transaction. Separating them is what lets
 * the screen preview "12 rows ready, 3 already in your records, 1 row has a problem" and require a
 * confirmation before a single row is touched.
 *
 * ### The duplicate policy
 *
 * A row is a duplicate when the ledger already holds an entry with the same date, direction, category,
 * amount, description and notes. That is the *same payment recorded twice* - not merely a same-day
 * purchase of the same size, which is why description and notes are part of the key - and importing it
 * again would inflate the household's totals. Duplicates are skipped and counted, never silently
 * dropped, and never merged. Two identical rows inside one file are treated the same way: the second
 * is a duplicate of the first this import is about to write.
 *
 * ### Nothing is half-written
 *
 * [commit] re-checks duplicates against the database *inside* the transaction, so a row imported by
 * another screen between preview and confirmation cannot be doubled. Every accepted row is inserted in
 * one transaction; if one insert fails, none is written.
 */
class DataTransferRepository(
    private val transactionDao: TransactionDao,
    private val categoryDao: CategoryDao,
    private val transactionRunner: TransactionRunner,
    private val clock: () -> Instant = Instant::now,
) {

    /**
     * Writes the whole ledger as CSV text.
     *
     * A transaction whose category is missing is a broken foreign key that the schema forbids; if it
     * ever happened, failing loudly is better than writing a file that silently omits the row.
     */
    suspend fun exportCsv(): CsvExport = transactionRunner.inTransaction {
        val categories = categoryDao.getAll().associateBy(CategoryEntity::id)

        val rows = transactionDao.getAll().map { transaction ->
            val category = checkNotNull(categories[transaction.categoryId]) {
                "Transaction ${transaction.id} refers to a category that does not exist"
            }

            CsvTransaction(
                date = transaction.transactionDate,
                type = transaction.type,
                categoryName = category.name,
                description = transaction.description,
                amountPaise = transaction.amountPaise,
                currency = "INR",
                quantityThousandths = transaction.quantityThousandths,
                ratePaise = transaction.ratePaise,
                notes = transaction.notes,
            )
        }

        CsvExport(text = TransactionCsv.encode(rows), rows = rows.size)
    }

    /**
     * Reads [text] and resolves it against the current ledger, without writing anything.
     *
     * Category names are matched case-insensitively within the row's direction, exactly as the schema's
     * unique index compares them, so "groceries" in a file finds the "Groceries" category. Retired
     * categories are matched too: an imported row may be history filed under a bucket the household has
     * since retired, which is the same state an old ledger row is in.
     */
    suspend fun prepareImport(text: String): ImportPlan {
        val read = when (val result = TransactionCsv.read(text)) {
            is CsvReadResult.Unreadable -> return ImportPlan(unreadable = result.message)
            is CsvReadResult.Read -> result
        }

        val categories = categoryDao.getAll()
        val categoryIds = categories.associate { category ->
            categoryKey(category.type, category.name) to category.id
        }

        val existing = transactionDao.getAll()
            .map { transaction ->
                LedgerKey(
                    date = transaction.transactionDate,
                    type = transaction.type,
                    categoryId = transaction.categoryId,
                    amountPaise = transaction.amountPaise,
                    description = transaction.description,
                    notes = transaction.notes,
                )
            }
            .toMutableSet()

        val accepted = mutableListOf<ImportRow>()
        val problems = read.problems.toMutableList()
        var duplicates = 0

        read.rows.forEach { row ->
            val categoryId = categoryIds[categoryKey(row.transaction.type, row.transaction.categoryName)]
            if (categoryId == null) {
                val direction = if (row.transaction.type == TransactionType.INCOME) "income" else "expense"
                problems += "Row ${row.rowNumber}: there is no $direction category called " +
                    "\"${row.transaction.categoryName}\"."
                return@forEach
            }

            val key = LedgerKey(
                date = row.transaction.date,
                type = row.transaction.type,
                categoryId = categoryId,
                amountPaise = row.transaction.amountPaise,
                description = row.transaction.description,
                notes = row.transaction.notes,
            )

            if (!existing.add(key)) {
                duplicates += 1
            } else {
                accepted += ImportRow(rowNumber = row.rowNumber, transaction = row.transaction, categoryId = categoryId)
            }
        }

        return ImportPlan(
            rows = accepted,
            duplicates = duplicates,
            problems = problems,
            unreadable = null,
        )
    }

    /**
     * Writes the rows of a previewed [plan], skipping any that became duplicates in the meantime.
     *
     * The returned counts are the database's answer, not the plan's: if the ledger changed between the
     * preview and the confirmation, the summary reports what was actually written.
     */
    suspend fun commitImport(plan: ImportPlan): ImportResult {
        if (plan.rows.isEmpty()) return ImportResult(inserted = 0, skippedDuplicates = 0)

        return transactionRunner.inTransaction {
            val existing = transactionDao.getAll()
                .map { transaction ->
                    LedgerKey(
                        date = transaction.transactionDate,
                        type = transaction.type,
                        categoryId = transaction.categoryId,
                        amountPaise = transaction.amountPaise,
                        description = transaction.description,
                        notes = transaction.notes,
                    )
                }
                .toMutableSet()

            val now = clock()
            val toInsert = mutableListOf<TransactionEntity>()
            var duplicates = 0

            plan.rows.forEach { ready ->
                val row = ready.transaction
                val key = LedgerKey(
                    date = row.date,
                    type = row.type,
                    categoryId = ready.categoryId,
                    amountPaise = row.amountPaise,
                    description = row.description,
                    notes = row.notes,
                )

                if (!existing.add(key)) {
                    duplicates += 1
                } else {
                    toInsert += TransactionEntity(
                        type = row.type,
                        amountPaise = row.amountPaise,
                        categoryId = ready.categoryId,
                        description = row.description,
                        notes = row.notes,
                        quantityThousandths = row.quantityThousandths,
                        ratePaise = row.ratePaise,
                        transactionDate = row.date,
                        createdAt = now,
                        updatedAt = now,
                    )
                }
            }

            transactionDao.insertAll(toInsert)

            ImportResult(inserted = toInsert.size, skippedDuplicates = duplicates)
        }
    }

    private fun categoryKey(type: TransactionType, name: String): String =
        "${type.name}|${name.trim().lowercase(Locale.ENGLISH)}"

    /**
     * The identity of a ledger row for duplicate detection.
     *
     * `categoryId` rather than the name so two categories that happen to share a name across
     * directions cannot collide, and every stored value rather than a hash so the comparison is exact.
     */
    private data class LedgerKey(
        val date: LocalDate,
        val type: TransactionType,
        val categoryId: Long,
        val amountPaise: Long,
        val description: String?,
        val notes: String?,
    )
}
