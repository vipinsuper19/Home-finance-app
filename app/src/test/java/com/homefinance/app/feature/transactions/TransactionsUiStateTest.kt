package com.homefinance.app.feature.transactions

import com.homefinance.app.data.local.relation.TransactionWithCategory
import com.homefinance.app.data.model.TransactionType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How a loaded row presents itself: the sign, the money formatting, and the mapping from the
 * database's joined row to the row the screen draws.
 *
 * These are pure functions, so they are asserted directly rather than through Compose. Whether the
 * right colour reaches the right glyph is a rendering detail; what matters, and what is pinned
 * here, is that the direction of the money is stated in text and that the amount is formatted by
 * the one shared formatter instead of a second, drifting copy of it.
 */
class TransactionsUiStateTest {

    // ------------------------------------------------------------- income vs expense

    @Test
    fun incomeIsShownWithALeadingPlus() {
        val item = item(amountPaise = 4_850_000L, type = TransactionType.INCOME)

        assertEquals("+\u20B948,500.00", item.signedAmountText())
    }

    @Test
    fun expenseIsShownWithALeadingMinus() {
        val item = item(amountPaise = 273_405L, type = TransactionType.EXPENSE)

        assertEquals("-\u20B92,734.05", item.signedAmountText())
    }

    @Test
    fun theSignComesFromTheTypeAndNeverFromTheStoredAmount() {
        // Amounts are always positive in the database; the direction lives in the type. A negative
        // stored amount must therefore not be able to produce a double negative on screen.
        val item = item(amountPaise = 1_000L, type = TransactionType.EXPENSE)

        assertEquals("-\u20B910.00", item.signedAmountText())
    }

    // ------------------------------------------------------------------ formatting

    @Test
    fun amountsAreFormattedByTheSharedFormatterUsingIndianGrouping() {
        val item = item(amountPaise = 1_234_567_890L, type = TransactionType.EXPENSE)

        // The same value CurrencyFormatterTest asserts for the formatter itself, so the screen and
        // the formatter cannot disagree about what a crore looks like.
        assertEquals("-\u20B91,23,45,678.90", item.signedAmountText())
    }

    @Test
    fun amountsShowTwoDecimalPlacesEvenWhenWhole() {
        val item = item(amountPaise = 50_000L, type = TransactionType.INCOME)

        assertEquals("+\u20B9500.00", item.signedAmountText())
    }

    // ------------------------------------------------------------------- projection

    @Test
    fun aJoinedRowBecomesTheRowTheScreenDraws() {
        val date = LocalDate.of(2026, 10, 15)

        val item = TransactionWithCategory(
            id = 7L,
            type = TransactionType.EXPENSE,
            amountPaise = 273_405L,
            categoryId = 3L,
            description = "Weekly shop",
            transactionDate = date,
            categoryName = "Groceries",
        ).toListItem()

        assertEquals(
            TransactionListItem(
                id = 7L,
                categoryName = "Groceries",
                description = "Weekly shop",
                transactionDate = date,
                amountPaise = 273_405L,
                type = TransactionType.EXPENSE,
            ),
            item,
        )
    }

    @Test
    fun aRowWithoutADescriptionKeepsItAbsentRatherThanEmpty() {
        val item = TransactionWithCategory(
            id = 1L,
            type = TransactionType.INCOME,
            amountPaise = 1L,
            categoryId = 1L,
            description = null,
            transactionDate = LocalDate.of(2026, 1, 1),
            categoryName = "Salary",
        ).toListItem()

        // Null means "draw no description line"; an empty string would make the row reserve space
        // for a blank line it never fills.
        assertEquals(null, item.description)
    }

    private fun item(
        amountPaise: Long,
        type: TransactionType,
    ): TransactionListItem = TransactionListItem(
        id = 1L,
        categoryName = "Groceries",
        description = null,
        transactionDate = LocalDate.of(2026, 10, 15),
        amountPaise = amountPaise,
        type = type,
    )
}
