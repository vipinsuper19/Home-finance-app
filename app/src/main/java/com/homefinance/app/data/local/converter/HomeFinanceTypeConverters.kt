package com.homefinance.app.data.local.converter

import androidx.room.TypeConverter
import com.homefinance.app.data.model.TransactionType
import java.time.Instant
import java.time.LocalDate

/**
 * Room type converters for the Home Finance schema.
 *
 * Registered once on [com.homefinance.app.data.local.HomeFinanceDatabase], so they apply to every
 * entity and every DAO in the database.
 *
 * Storage choices, and why they are the ones that will not silently shift a household's numbers:
 *
 *  - **Money** never passes through here. Amounts are stored as `INTEGER` paise directly, so no
 *    conversion can ever introduce a rounding error.
 *  - **Transaction type** is stored as the enum's [name] (`"INCOME"` / `"EXPENSE"`) rather than
 *    an ordinal. Ordinals would silently re-label every historical row the first time a constant
 *    was inserted into, or removed from, the middle of the enum.
 *  - **Dates** ([LocalDate]) are stored as the number of days since 1970-01-01 (ISO epoch day).
 *    A transaction date is a calendar day, not an instant: storing it this way means a row dated
 *    15 August stays on 15 August no matter how the device's timezone or DST offset later
 *    changes.
 *  - **Timestamps** ([Instant]) are stored as UTC epoch milliseconds. They are audit metadata
 *    (when the row was written or last edited) rather than anything the household reads.
 */
class HomeFinanceTypeConverters {

    @TypeConverter
    fun fromTransactionType(type: TransactionType): String = type.name

    @TypeConverter
    fun toTransactionType(stored: String): TransactionType = TransactionType.fromStorage(stored)

    @TypeConverter
    fun fromLocalDate(date: LocalDate): Long = date.toEpochDay()

    @TypeConverter
    fun toLocalDate(epochDay: Long): LocalDate = LocalDate.ofEpochDay(epochDay)

    @TypeConverter
    fun fromInstant(instant: Instant): Long = instant.toEpochMilli()

    @TypeConverter
    fun toInstant(epochMilli: Long): Instant = Instant.ofEpochMilli(epochMilli)
}
