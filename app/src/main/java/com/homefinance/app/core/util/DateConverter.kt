package com.homefinance.app.core.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Converts the calendar dates a household reads to and from the epoch-millisecond values the
 * Material 3 date picker works in.
 *
 * ### Why UTC, and why this exists at all
 *
 * A transaction date is a plain calendar day with no time and no timezone (see
 * [com.homefinance.app.data.local.entity.TransactionEntity]): the 15th means the 15th whatever
 * the device's timezone later becomes. The picker, however, speaks "milliseconds from the epoch",
 * and the obviously-tempting conversion -
 * `date.atStartOfDay(ZoneId.systemDefault())` - attaches the *device's* offset. In a timezone
 * east of UTC that turns midnight into the previous evening, and a picked 15 October would come
 * back out of the picker as 14 October. Pinning both directions to [ZoneOffset.UTC] makes the
 * round trip exact and timezone-independent.
 *
 * This is the same reasoning [DateFormatter] uses for formatting, and it lives in its own class
 * so the two directions cannot drift apart: a conversion that is only correct one way would be
 * worse than no conversion at all.
 */
object DateConverter {

    /** The UTC instant of midnight at the start of [date], as epoch milliseconds. */
    fun toUtcEpochMillis(date: LocalDate): Long =
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** The UTC calendar day the epoch milliseconds [millis] fall on. */
    fun fromUtcEpochMillis(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
}
