package com.homefinance.app.feature.transactions

import com.homefinance.app.data.local.relation.TransactionWithCategory
import com.homefinance.app.data.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a loaded ledger looks like once it has been folded into the state the screen draws: the
 * money formatting, the projection from the database's joined row, and the searching, filtering
 * and day-grouping that turn a list of rows into a screen.
 *
 * These are pure functions, so they are asserted directly rather than through Compose. Rendering
 * is covered on a device by `TransactionsScreenTest`; what is pinned here is the reasoning, which
 * a test can only reach cheaply off-device.
 */
class TransactionsUiStateTest {

    // ------------------------------------------------------------------ formatting

    @Test
    fun amountsAreFormattedByTheSharedFormatterUsingIndianGrouping() {
        val item = item(amountPaise = 1_234_567_890L, type = TransactionType.EXPENSE)

        // The same value CurrencyFormatterTest asserts for the formatter itself, so the screen and
        // the formatter cannot disagree about what a crore looks like.
        assertEquals("\u20B91,23,45,678.90", item.amountText())
    }

    @Test
    fun amountsShowTwoDecimalPlacesEvenWhenWhole() {
        val item = item(amountPaise = 50_000L, type = TransactionType.INCOME)

        assertEquals("\u20B9500.00", item.amountText())
    }

    @Test
    fun theAmountCarriesNoSign() {
        // The direction of the money is the row's colour, and only that. Were a sign also printed,
        // the two would be two places to change and two places to disagree; this asserts there is
        // exactly one, in `type`.
        val income = item(amountPaise = 4_850_000L, type = TransactionType.INCOME)
        val expense = item(amountPaise = 273_405L, type = TransactionType.EXPENSE)

        assertEquals("\u20B948,500.00", income.amountText())
        assertEquals("\u20B92,734.05", expense.amountText())
        assertFalse(expense.amountText().startsWith("-"))
    }

    // ------------------------------------------------------------------- projection

    @Test
    fun aJoinedRowBecomesTheRowTheScreenDraws() {
        val item = row(
            id = 7L,
            categoryName = "Groceries",
            type = TransactionType.EXPENSE,
            amountPaise = 273_405L,
            description = "Weekly shop",
            transactionDate = LocalDate.of(2026, 10, 15),
        ).toListItem()

        assertEquals(
            TransactionListItem(
                id = 7L,
                categoryName = "Groceries",
                description = "Weekly shop",
                transactionDate = LocalDate.of(2026, 10, 15),
                amountPaise = 273_405L,
                type = TransactionType.EXPENSE,
            ),
            item,
        )
    }

    @Test
    fun aRowWithoutADescriptionKeepsItAbsentRatherThanEmpty() {
        // Null means "draw no description line"; an empty string would make the row reserve space
        // for a blank line it never fills.
        assertEquals(null, row(id = 1L, categoryName = "Salary", description = null).toListItem().description)
    }

    // -------------------------------------------------------------------- grouping

    @Test
    fun rowsAreGroupedUnderTheDayTheyHappenedAndTheNewestDayLeads() {
        val content = ledger(
            row(id = 3L, categoryName = "Milk", transactionDate = TODAY),
            row(id = 2L, categoryName = "Groceries", transactionDate = YESTERDAY),
            row(id = 1L, categoryName = "Salary", transactionDate = EARLIER),
        )

        assertEquals(listOf(TODAY, YESTERDAY, EARLIER), content.groups.map { it.date })
    }

    @Test
    fun rowsOnTheSameDayStayTogetherAndKeepTheOrderTheyArrivedIn() {
        val content = ledger(
            row(id = 9L, categoryName = "Milk", transactionDate = TODAY),
            row(id = 8L, categoryName = "Groceries", transactionDate = TODAY),
        )

        // One heading, not two: the query already ordered these rows and grouping must not reorder
        // them, or the newest entry of a day could appear below the oldest.
        assertEquals(1, content.groups.size)
        assertEquals(listOf(9L, 8L), content.groups.single().items.map { it.id })
    }

    @Test
    fun aDayIsMarkedAsTodayOrYesterdayOnlyWhenItIs() {
        val content = ledger(
            row(id = 3L, categoryName = "Milk", transactionDate = TODAY),
            row(id = 2L, categoryName = "Groceries", transactionDate = YESTERDAY),
            row(id = 1L, categoryName = "Salary", transactionDate = EARLIER),
        )

        assertEquals(
            listOf(DateRelation.TODAY, DateRelation.YESTERDAY, DateRelation.EARLIER),
            content.groups.map { it.relation },
        )
    }

    @Test
    fun anEmptyLedgerHasNoGroupsAndSaysSo() {
        val content = ledger()

        assertTrue(content.groups.isEmpty())
        assertTrue(content.ledgerIsEmpty)
    }

    // ------------------------------------------------------------------ the month

    @Test
    fun opensOnTheCurrentMonthWhileItHasEntries() {
        val content = ledger(
            row(id = 2L, categoryName = "Milk", transactionDate = THIS_MONTH),
            row(id = 1L, categoryName = "Salary", transactionDate = LAST_MONTH),
        )

        assertEquals(YearMonth.from(TODAY), content.filters.month)
        assertEquals(listOf(2L), content.visibleItems.map { it.id })
    }

    @Test
    fun opensOnTheNewestMonthThatHasEntriesWhenThisMonthHasNone() {
        // A household that last recorded something in September must not open the app in October
        // onto a blank page that looks like their data is gone.
        val content = ledger(
            row(id = 2L, categoryName = "Milk", transactionDate = LAST_MONTH),
            row(id = 1L, categoryName = "Salary", transactionDate = LAST_MONTH.minusDays(1)),
        )

        assertEquals(YearMonth.from(LAST_MONTH), content.filters.month)
        assertEquals(listOf(2L, 1L), content.visibleItems.map { it.id })
    }

    @Test
    fun theSelectorOffersEveryMonthWithEntriesNewestFirst() {
        val content = ledger(
            row(id = 3L, categoryName = "Milk", transactionDate = THIS_MONTH),
            row(id = 2L, categoryName = "Groceries", transactionDate = LAST_MONTH),
            row(id = 1L, categoryName = "Salary", transactionDate = OLDEST_MONTH),
        )

        assertEquals(
            listOf(YearMonth.from(THIS_MONTH), YearMonth.from(LAST_MONTH), YearMonth.from(OLDEST_MONTH)),
            content.monthOptions,
        )
    }

    @Test
    fun theMonthThoseEntriesAreFromIsAlwaysOneTheSelectorOffers() {
        // The chip must be able to show the month it is on, even in the moment before a re-read of
        // the ledger it was chosen from has arrived.
        val content = ledger(row(id = 1L, categoryName = "Milk", transactionDate = THIS_MONTH))

        assertTrue(content.filters.month in content.monthOptions)
    }

    @Test
    fun aChosenMonthOverridesTheAutomaticOne() {
        val content = ledger(
            row(id = 2L, categoryName = "Milk", transactionDate = THIS_MONTH),
            row(id = 1L, categoryName = "Salary", transactionDate = LAST_MONTH),
            intent = TransactionFilterIntent(month = YearMonth.from(LAST_MONTH)),
        )

        assertEquals(YearMonth.from(LAST_MONTH), content.filters.month)
        assertEquals(listOf(1L), content.visibleItems.map { it.id })
    }

    // ----------------------------------------------------------------- the filters

    @Test
    fun theDirectionFilterKeepsOnlyOneKindOfEntry() {
        val expensesOnly = ledger(
            row(id = 2L, categoryName = "Milk", type = TransactionType.EXPENSE, transactionDate = TODAY),
            row(id = 1L, categoryName = "Salary", type = TransactionType.INCOME, transactionDate = TODAY),
            intent = TransactionFilterIntent(type = TransactionTypeFilter.EXPENSE),
        )

        assertEquals(listOf(2L), expensesOnly.visibleItems.map { it.id })
        assertEquals(TransactionTypeFilter.EXPENSE, expensesOnly.filters.type)
    }

    @Test
    fun everyDirectionIsKeptByDefault() {
        val content = ledger(
            row(id = 2L, categoryName = "Milk", type = TransactionType.EXPENSE, transactionDate = TODAY),
            row(id = 1L, categoryName = "Salary", type = TransactionType.INCOME, transactionDate = TODAY),
        )

        assertEquals(TransactionTypeFilter.ALL, content.filters.type)
        assertEquals(listOf(2L, 1L), content.visibleItems.map { it.id })
    }

    @Test
    fun searchingMatchesTheCategoryName() {
        val content = ledger(
            row(id = 2L, categoryName = "Groceries", transactionDate = TODAY),
            row(id = 1L, categoryName = "Salary", type = TransactionType.INCOME, transactionDate = TODAY),
            intent = TransactionFilterIntent(query = "groc"),
        )

        assertEquals(listOf(2L), content.visibleItems.map { it.id })
    }

    @Test
    fun searchingAlsoMatchesTheDescription() {
        // "chai" is how a household remembers a purchase; "Groceries" is only how it was filed.
        val content = ledger(
            row(id = 2L, categoryName = "Groceries", description = "Chai and biscuits", transactionDate = TODAY),
            row(id = 1L, categoryName = "Milk", description = "Two litres", transactionDate = TODAY),
            intent = TransactionFilterIntent(query = "chai"),
        )

        assertEquals(listOf(2L), content.visibleItems.map { it.id })
    }

    @Test
    fun searchingIgnoresCaseAndSurroundingSpaces() {
        val content = ledger(
            row(id = 1L, categoryName = "Groceries", transactionDate = TODAY),
            intent = TransactionFilterIntent(query = "  GROCERIES  "),
        )

        assertEquals(listOf(1L), content.visibleItems.map { it.id })
    }

    @Test
    fun aRowWithoutADescriptionIsNotMatchedByASearchForOne() {
        val content = ledger(
            row(id = 1L, categoryName = "Milk", description = null, transactionDate = TODAY),
            intent = TransactionFilterIntent(query = "chai"),
        )

        assertTrue(content.visibleItems.isEmpty())
    }

    @Test
    fun theFiltersTheHouseholdSetAreEchoedBackSoTheControlsCanShowThem() {
        val content = ledger(
            row(id = 1L, categoryName = "Milk", transactionDate = TODAY),
            intent = TransactionFilterIntent(
                query = "mil",
                type = TransactionTypeFilter.EXPENSE,
                month = YearMonth.from(TODAY),
            ),
        )

        // The raw query, spaces and all: the search box shows what was typed, not a trimmed copy
        // the caret would jump around in.
        assertEquals(
            TransactionFilters(query = "mil", type = TransactionTypeFilter.EXPENSE, month = YearMonth.from(TODAY)),
            content.filters,
        )
    }

    @Test
    fun filteringEveryRowOutIsNotTheSameAsAnEmptyLedger() {
        val filteredOut = ledger(
            row(id = 1L, categoryName = "Milk", transactionDate = TODAY),
            intent = TransactionFilterIntent(query = "nothing matches this"),
        )

        // The ledger has entries, so the household keeps the controls they need to undo the filter;
        // only `ledgerIsEmpty` earns the "record your first transaction" invitation.
        assertTrue(filteredOut.groups.isEmpty())
        assertFalse(filteredOut.ledgerIsEmpty)
    }

    // -------------------------------------------------------------------- fixtures

    private fun ledger(
        vararg rows: TransactionWithCategory,
        intent: TransactionFilterIntent = TransactionFilterIntent(),
    ): TransactionsUiState.Content = rows.toList().toContent(intent = intent, today = TODAY)

    private fun item(
        amountPaise: Long,
        type: TransactionType,
    ): TransactionListItem = TransactionListItem(
        id = 1L,
        categoryName = "Groceries",
        description = null,
        transactionDate = TODAY,
        amountPaise = amountPaise,
        type = type,
    )

    private fun row(
        id: Long,
        categoryName: String,
        type: TransactionType = TransactionType.EXPENSE,
        amountPaise: Long = 1_000L,
        description: String? = null,
        transactionDate: LocalDate = TODAY,
        notes: String? = null,
        quantityThousandths: Long? = null,
        ratePaise: Long? = null,
    ): TransactionWithCategory = TransactionWithCategory(
        id = id,
        type = type,
        amountPaise = amountPaise,
        categoryId = 1L,
        description = description,
        notes = notes,
        quantityThousandths = quantityThousandths,
        ratePaise = ratePaise,
        transactionDate = transactionDate,
        createdAt = RECORDED_AT,
        updatedAt = RECORDED_AT,
        categoryName = categoryName,
    )

    private companion object {
        /** The day the screen is being looked at. Deliberately mid-month so nothing lands on a boundary. */
        val TODAY: LocalDate = LocalDate.of(2026, 10, 17)
        val YESTERDAY: LocalDate = TODAY.minusDays(1)
        val EARLIER: LocalDate = TODAY.minusDays(7)

        val THIS_MONTH: LocalDate = TODAY
        val LAST_MONTH: LocalDate = TODAY.minusMonths(1)
        val OLDEST_MONTH: LocalDate = TODAY.minusMonths(2)

        /** When every row in these tests was written. The list draws none of it; the read model carries it. */
        val RECORDED_AT: Instant = Instant.parse("2026-10-14T05:00:00Z")
    }
}
