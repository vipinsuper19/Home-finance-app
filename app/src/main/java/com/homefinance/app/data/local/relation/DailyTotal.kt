package com.homefinance.app.data.local.relation

import androidx.room.ColumnInfo
import java.time.LocalDate

/**
 * One calendar day of the ledger, summed: what came in and what went out.
 *
 * A **read-only projection over a grouped aggregate**, not an entity. The report's charts need the
 * period's shape over time, and asking SQLite for one row per day is what lets that be drawn without
 * pulling every transaction into the UI layer: a year is at most 366 small rows rather than every
 * entry the household ever recorded.
 *
 * ### Why per day, and not per week or per month
 *
 * The database does not know what a "week" or a "month" is in this app, and it should not have to:
 * dates are stored as epoch days and every calendar rule the project has is written in Kotlin
 * against [LocalDate], never in SQL against a string. This read therefore returns the finest bucket
 * the reports need - the day - and the *report* folds those days into weeks or months. That keeps
 * the definition of a week in one testable Kotlin function instead of in a `strftime` call whose
 * behaviour would depend on the device's SQLite build.
 *
 * @param date the calendar day, as it is stored: an epoch day with no time and no timezone.
 * @param incomePaise everything recorded as `INCOME` that day, `0` when nothing was.
 * @param expensePaise everything recorded as `EXPENSE` that day, `0` when nothing was.
 */
data class DailyTotal(
    @ColumnInfo(name = "day")
    val date: LocalDate,

    @ColumnInfo(name = "income_paise")
    val incomePaise: Long,

    @ColumnInfo(name = "expense_paise")
    val expensePaise: Long,
)
