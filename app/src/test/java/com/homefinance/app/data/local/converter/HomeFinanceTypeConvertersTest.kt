package com.homefinance.app.data.local.converter

import com.homefinance.app.data.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The converters are the only place a stored value becomes a Kotlin type again, so these tests
 * pin the on-disk representation itself - not just that a value survives a round trip. A round
 * trip alone would still pass if the format silently changed from epoch days to epoch millis,
 * which would reinterpret every existing row.
 */
class HomeFinanceTypeConvertersTest {

    private val converters = HomeFinanceTypeConverters()

    @Test
    fun transactionTypeIsStoredUnderItsEnumName() {
        assertEquals("INCOME", converters.fromTransactionType(TransactionType.INCOME))
        assertEquals("EXPENSE", converters.fromTransactionType(TransactionType.EXPENSE))
    }

    @Test
    fun everyTransactionTypeRoundTrips() {
        TransactionType.entries.forEach { type ->
            assertEquals(type, converters.toTransactionType(converters.fromTransactionType(type)))
        }
    }

    /**
     * An unrecognised stored value means the row was written by something that does not respect
     * this model. Guessing at it - falling back to EXPENSE, say - would mis-state the household's
     * money, so it has to fail loudly instead.
     */
    @Test
    fun anUnknownStoredTransactionTypeIsRejected() {
        listOf("", "income", "TRANSFER", "EXPENSE ", "0").forEach { stored ->
            assertThrows(IllegalArgumentException::class.java) {
                converters.toTransactionType(stored)
            }
        }
    }

    @Test
    fun datesAreStoredAsEpochDays() {
        assertEquals(0L, converters.fromLocalDate(LocalDate.of(1970, 1, 1)))
        assertEquals(1L, converters.fromLocalDate(LocalDate.of(1970, 1, 2)))
        assertEquals(20_454L, converters.fromLocalDate(LocalDate.of(2026, 1, 1)))
    }

    /**
     * A transaction date is a plain calendar day. If it were stored as an instant, or as anything
     * derived from the default timezone, then travelling or a DST change could move a household's
     * August expense into July. This pins that it does not.
     */
    @Test
    fun aDateSurvivesTheDefaultTimezoneChanging() {
        val date = LocalDate.of(2026, 8, 15)
        val original = TimeZone.getDefault()

        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati")) // UTC+14
            val stored = converters.fromLocalDate(date)
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Midway")) // UTC-11

            assertEquals(date, converters.toLocalDate(stored))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun timestampsAreStoredAsUtcEpochMilliseconds() {
        assertEquals(0L, converters.fromInstant(Instant.parse("1970-01-01T00:00:00Z")))
        assertEquals(1_000L, converters.fromInstant(Instant.parse("1970-01-01T00:00:01Z")))
        // 2026-10-16 is ISO epoch day 20742; 06:30 UTC adds 23 400 seconds.
        assertEquals(1_792_132_200_000L, converters.fromInstant(Instant.parse("2026-10-16T06:30:00Z")))
    }

    @Test
    fun epochZeroIsTheUnixEpoch() {
        assertEquals(Instant.parse("1970-01-01T00:00:00Z"), converters.toInstant(0L))
    }
}
