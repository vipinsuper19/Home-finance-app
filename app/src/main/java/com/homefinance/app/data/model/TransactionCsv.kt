package com.homefinance.app.data.model

import com.homefinance.app.core.util.MoneyInput
import com.homefinance.app.core.util.MoneyParseResult
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/** One ledger row as the CSV contract carries it. */
data class CsvTransaction(
    val date: LocalDate,
    val type: TransactionType,
    val categoryName: String,
    val description: String?,
    val amountPaise: Long,
    val currency: String,
    val quantityThousandths: Long?,
    val ratePaise: Long?,
    val notes: String?,
)

/** One parsed data record, with the row number it occupied in the file (the header is row 1). */
data class CsvRow(val rowNumber: Int, val transaction: CsvTransaction)

/** What reading a CSV file produced. */
sealed interface CsvReadResult {
    /**
     * The file's header was understood. [rows] are the records whose *fields* all parsed;
     * [problems] are the ones that did not, stated as sentences with their row number.
     */
    data class Read(val rows: List<CsvRow>, val problems: List<String>) : CsvReadResult

    /** The file is not this app's CSV at all: empty, or missing a required column. */
    data class Unreadable(val message: String) : CsvReadResult
}

/**
 * The versioned CSV contract for exporting and importing transactions.
 *
 * ### The format
 *
 * ```text
 * date,type,category,description,amount,currency,quantity,rate,notes
 * 2026-11-05,EXPENSE,Groceries,"Milk, bread",250.50,INR,,,Weekly shop
 * ```
 *
 * Dates are ISO-8601 (`yyyy-MM-dd`) in the file, always, whatever layout the app is currently drawing
 * dates in. That is deliberate: a file must still import after the household changes the display
 * format, and a recipient opening it in a spreadsheet must be able to sort it. The layout preference
 * is a *display* preference and does not change the contract.
 *
 * Amounts are plain decimal major units with no symbol and no grouping (`250.50`), so a spreadsheet
 * reads them as numbers, and they are parsed back through [MoneyInput] - the same exact decimal
 * parser the form uses - so an import cannot introduce a rounding difference.
 *
 * Text is escaped the RFC 4180 way: a field containing a comma, a quote or a line break is wrapped in
 * quotes, and an embedded quote is doubled. The file is UTF-8.
 *
 * ### Import is liberal about shape, strict about values
 *
 * Columns are matched by name, so their order does not matter and an extra column is ignored; the four
 * mandatory columns (`date`, `type`, `category`, `amount`) must be present. Beyond the header, every
 * value is checked: a date must parse, an amount must be a positive exact decimal, a type must be
 * `INCOME` or `EXPENSE`, and a currency other than the one the ledger records is refused rather than
 * silently relabelled. A row that fails is reported with its row number and is not imported.
 */
object TransactionCsv {

    const val HEADER: String = "date,type,category,description,amount,currency,quantity,rate,notes"

    /** The columns a file must have, in any order. The rest are optional. */
    val REQUIRED_COLUMNS: List<String> = listOf("date", "type", "category", "amount")

    private const val QUOTE = '"'
    private const val DELIMITER = ','
    private const val NEWLINE = '\n'

    /** The display layouts an imported date may be written in, in addition to ISO. */
    private val NUMERIC_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)

    private val NAMED_MONTH_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH)
            .withResolverStyle(ResolverStyle.STRICT)

    /** Writes [rows] as one CSV document, header first, with a trailing newline. */
    fun encode(rows: List<CsvTransaction>): String = buildString {
        append(HEADER)
        append(NEWLINE)
        rows.forEach { row ->
            append(row.date.toString())
            append(DELIMITER)
            append(row.type.name)
            append(DELIMITER)
            append(escape(row.categoryName))
            append(DELIMITER)
            append(escape(row.description.orEmpty()))
            append(DELIMITER)
            append(amountText(row.amountPaise))
            append(DELIMITER)
            append(escape(row.currency))
            append(DELIMITER)
            append(row.quantityThousandths?.let(::quantityText).orEmpty())
            append(DELIMITER)
            append(row.ratePaise?.let(::amountText).orEmpty())
            append(DELIMITER)
            append(escape(row.notes.orEmpty()))
            append(NEWLINE)
        }
    }

    /**
     * Reads [text] as a CSV document.
     *
     * The whole file is read even after a bad row, so the summary can say how many rows are usable and
     * a household can fix them all in one edit rather than discovering one problem per attempt.
     */
    fun read(text: String): CsvReadResult {
        val withoutBom = text.removePrefix("\uFEFF")
        val records = parseRecords(withoutBom)
        if (records.isEmpty()) return CsvReadResult.Unreadable("The file is empty.")

        val header = records.first().map { it.trim().lowercase(Locale.ENGLISH) }
        if (header.size != header.toSet().size) {
            return CsvReadResult.Unreadable("This file has two columns with the same name.")
        }
        val missing = REQUIRED_COLUMNS.filterNot { it in header }
        if (missing.isNotEmpty()) {
            return CsvReadResult.Unreadable(
                "This file is not a transaction export. It is missing the " +
                    missing.joinToString(", ") { "\"$it\"" } +
                    " column${if (missing.size == 1) "" else "s"}.",
            )
        }

        val rows = mutableListOf<CsvRow>()
        val problems = mutableListOf<String>()

        records.drop(1).forEachIndexed { offset, fields ->
            // Row 2 is the first data row, because row 1 is the header.
            val rowNumber = offset + 2
            if (fields.size == 1 && fields.first().isBlank()) return@forEachIndexed

            if (fields.size != header.size) {
                problems += "Row $rowNumber has ${fields.size} columns but the header has ${header.size}."
                return@forEachIndexed
            }

            val values = header.zip(fields).toMap()
            when (val parsed = readRow(values)) {
                is CsvRowResult.Ok -> rows += CsvRow(rowNumber = rowNumber, transaction = parsed.row)
                is CsvRowResult.Problem -> problems += "Row $rowNumber: ${parsed.reason}"
            }
        }

        return CsvReadResult.Read(rows = rows, problems = problems)
    }

    private sealed interface CsvRowResult {
        data class Ok(val row: CsvTransaction) : CsvRowResult
        data class Problem(val reason: String) : CsvRowResult
    }

    private fun readRow(values: Map<String, String>): CsvRowResult {
        val date = values["date"].orEmpty().trim().let { raw ->
            if (raw.isEmpty()) return CsvRowResult.Problem("the date is blank.")
            runCatching { LocalDate.parse(raw) }.getOrNull()
                ?: runCatching { LocalDate.parse(raw, NUMERIC_DATE) }.getOrNull()
                ?: runCatching { LocalDate.parse(raw, NAMED_MONTH_DATE) }.getOrNull()
                ?: return CsvRowResult.Problem(
                    "the date \"$raw\" is not a date. Use yyyy-MM-dd, for example 2026-11-05.",
                )
        }

        val typeText = values["type"].orEmpty().trim()
        val type = TransactionType.entries.firstOrNull {
            it.name.equals(typeText, ignoreCase = true)
        } ?: return CsvRowResult.Problem(
            "the type \"$typeText\" is not INCOME or EXPENSE.",
        )

        val category = values["category"].orEmpty().trim()
        if (category.isEmpty()) return CsvRowResult.Problem("the category is blank.")

        val amountText = values["amount"].orEmpty()
        val amount = when (val parsed = MoneyInput.parseRupeesToPaise(amountText)) {
            is MoneyParseResult.Success -> parsed.paise
            is MoneyParseResult.Problem -> return CsvRowResult.Problem(
                "the amount \"${amountText.trim()}\" ${parsed.describe()}",
            )
        }

        val currency = values["currency"].orEmpty().trim()
        if (currency.isNotEmpty() && !currency.equals(AppCurrency.INR.code, ignoreCase = true)) {
            return CsvRowResult.Problem(
                "the currency \"$currency\" is not supported. " +
                    "This app records amounts in ${AppCurrency.INR.code} only, and does not convert.",
            )
        }

        val quantityText = values["quantity"].orEmpty().trim()
        val quantity = if (quantityText.isEmpty()) {
            null
        } else {
            parseThousandths(quantityText)
                ?: return CsvRowResult.Problem(
                    "the quantity \"$quantityText\" is not a positive number with at most three decimals.",
                )
        }

        val rateText = values["rate"].orEmpty().trim()
        val rate = if (rateText.isEmpty()) {
            null
        } else {
            when (val parsed = MoneyInput.parseRupeesToPaise(rateText)) {
                is MoneyParseResult.Success -> parsed.paise
                is MoneyParseResult.Problem -> return CsvRowResult.Problem(
                    "the rate \"$rateText\" ${parsed.describe()}",
                )
            }
        }

        if ((quantity == null) != (rate == null)) {
            return CsvRowResult.Problem(
                "a quantity and a rate must be given together, or both left blank.",
            )
        }

        return CsvRowResult.Ok(
            CsvTransaction(
                date = date,
                type = type,
                categoryName = category,
                description = values["description"].orEmpty().trim().ifEmpty { null },
                amountPaise = amount,
                currency = AppCurrency.INR.code,
                quantityThousandths = quantity,
                ratePaise = rate,
                notes = values["notes"].orEmpty().trim().ifEmpty { null },
            ),
        )
    }

    /** Splits the document into records, honouring quoted fields and doubled quotes. */
    private fun parseRecords(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0

        fun endField() {
            fields += field.toString()
            field.setLength(0)
        }

        fun endRecord() {
            endField()
            records += fields.toList()
            fields.clear()
        }

        while (index < text.length) {
            val char = text[index]
            when {
                quoted && char == QUOTE && index + 1 < text.length && text[index + 1] == QUOTE -> {
                    field.append(QUOTE)
                    index += 2
                    continue
                }
                quoted && char == QUOTE -> quoted = false
                !quoted && char == QUOTE -> quoted = true
                !quoted && char == DELIMITER -> endField()
                !quoted && (char == NEWLINE || char == '\r') -> {
                    // Consume a CRLF pair as one record break rather than producing an empty line.
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == NEWLINE) {
                        index += 1
                    }
                    endRecord()
                }
                else -> field.append(char)
            }
            index += 1
        }

        // A file that does not end with a newline still has a final record.
        if (field.isNotEmpty() || fields.isNotEmpty()) endRecord()

        return records
    }

    private fun escape(value: String): String {
        val needsQuotes = value.any { it == DELIMITER || it == QUOTE || it == '\n' || it == '\r' } ||
            value != value.trim()
        return if (!needsQuotes) {
            value
        } else {
            QUOTE + value.replace("\"", "\"\"") + QUOTE
        }
    }

    /** Two decimal places, from the integer paise, with no floating point anywhere. */
    private fun amountText(paise: Long): String =
        BigDecimal.valueOf(paise, 2).toPlainString()

    /** Three decimal places, from the thousandths the database stores. */
    private fun quantityText(thousandths: Long): String =
        BigDecimal.valueOf(thousandths, 3).toPlainString()

    private fun parseThousandths(raw: String): Long? {
        val decimal = runCatching { BigDecimal(raw) }.getOrNull() ?: return null
        if (decimal.signum() <= 0) return null
        val stripped = decimal.stripTrailingZeros()
        if (stripped.scale() > 3) return null
        return runCatching { stripped.movePointRight(3).longValueExact() }.getOrNull()
    }

    private fun MoneyParseResult.Problem.describe(): String = when (this) {
        MoneyParseResult.Problem.Blank -> "is blank."
        MoneyParseResult.Problem.NotANumber -> "is not a number, for example 250 or 250.50."
        MoneyParseResult.Problem.TooManyDecimalPlaces -> "has more than two decimal places."
        MoneyParseResult.Problem.NotPositive -> "must be more than zero."
        MoneyParseResult.Problem.TooLarge -> "is too large."
    }
}
