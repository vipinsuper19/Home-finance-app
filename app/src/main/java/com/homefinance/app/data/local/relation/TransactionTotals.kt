package com.homefinance.app.data.local.relation

import androidx.room.ColumnInfo

/**
 * What one period of the ledger adds up to: each direction's total and its number of entries.
 *
 * This is a **read-only projection over an aggregate**, not an entity: it has no table, and nothing
 * is ever inserted or updated through it. It exists because a report asks the database for two sums
 * and two counts, and answering that as four separate reads would be four opportunities for the four
 * figures to arrive from four slightly different moments.
 *
 * ### Money stays an exact count of paise
 *
 * Every figure here is a `Long` number of paise, summed by SQLite over `INTEGER` columns, so no
 * rounding can enter between the rows and the report. The direction of the money is carried by which
 * field a figure is in - [incomePaise] or [expensePaise] - and never by a sign, exactly as
 * [com.homefinance.app.data.local.entity.TransactionEntity] carries it: there is one place that
 * decides whether a number adds to or subtracts from the household's balance, and it is
 * [netPaise] below.
 *
 * ### What is not in here
 *
 * Only `transactions` rows are summed. A recurring schedule's pending occurrence lives in a
 * different table and is an *expectation* - it has moved no money - so a bill falling due cannot
 * appear in any of these figures. An occurrence that has been recorded as paid appears exactly once,
 * as the ordinary transaction that recording it wrote. See
 * [com.homefinance.app.data.repository.ReportRepository].
 *
 * @param incomePaise the total of every `INCOME` entry in the period, `0` when there are none.
 * @param expensePaise the total of every `EXPENSE` entry in the period, `0` when there are none.
 * @param incomeCount how many `INCOME` entries there were, for "12 transactions".
 * @param expenseCount how many `EXPENSE` entries there were, for "28 transactions".
 */
data class TransactionTotals(
    @ColumnInfo(name = "income_paise")
    val incomePaise: Long,

    @ColumnInfo(name = "expense_paise")
    val expensePaise: Long,

    @ColumnInfo(name = "income_count")
    val incomeCount: Long,

    @ColumnInfo(name = "expense_count")
    val expenseCount: Long,
) {

    /**
     * Money in minus money out, which is **negative** when the household spent more than it earned.
     *
     * Negative is a real answer, not an error: a household that had a hard month needs to see that
     * the figure is below zero rather than be shown a `0` that hides it.
     */
    val netPaise: Long get() = incomePaise - expensePaise

    /** How many entries the period holds altogether. */
    val transactionCount: Long get() = incomeCount + expenseCount

    /**
     * True when the period holds nothing at all.
     *
     * This is the difference between "nothing happened this month" and "everything added up to
     * zero", and the report says the first in words rather than drawing them the same way.
     */
    val isEmpty: Boolean get() = transactionCount == 0L

    companion object {

        /**
         * Nothing at all, for a read that has not answered yet or a period with no rows.
         *
         * Named rather than built at each use, so "the period is empty" has one meaning rather than
         * one per caller.
         */
        val None = TransactionTotals(
            incomePaise = 0L,
            expensePaise = 0L,
            incomeCount = 0L,
            expenseCount = 0L,
        )
    }
}
