package com.homefinance.app.core.util

import java.math.BigDecimal

private const val PAISE_SCALE = 2
private const val RUPEE_SYMBOL = "\u20B9"
private const val GROUP_SEPARATOR = ","
private const val DECIMAL_SEPARATOR = "."

/** One thousand rupees, the first unit a compact label switches to. */
private const val THOUSAND = 1_000L

/** One lakh: a hundred thousand rupees. */
private const val LAKH = 100_000L

/** One crore: a hundred lakh, or ten million rupees. */
private const val CRORE = 10_000_000L

/** How many tenths one unit is divided into, for [CurrencyFormatter.formatCompactInr]. */
private const val TENTHS_PER_UNIT = 10L

/**
 * Formats money for display in Indian Rupees.
 *
 * Two deliberate choices:
 *  - Amounts are held as [Long] **paise** (1 rupee = 100 paise) and converted with
 *    [BigDecimal], so money is never represented with floating point.
 *  - Grouping follows the Indian numbering system (₹1,23,45,678.90). It is applied here
 *    instead of being taken from the platform, because the JDK's `DecimalFormat` supports
 *    only one grouping size and therefore cannot express lakh/crore grouping at all — the
 *    output would otherwise differ between the unit tests and the device.
 */
object CurrencyFormatter {

    fun formatInr(amountInPaise: Long): String {
        val amountInRupees = BigDecimal.valueOf(amountInPaise, PAISE_SCALE)
        val sign = if (amountInRupees.signum() < 0) "-" else ""
        val plain = amountInRupees.abs().toPlainString() // always exactly two decimal places
        val wholeRupees = plain.substringBefore(DECIMAL_SEPARATOR)
        val paise = plain.substringAfter(DECIMAL_SEPARATOR)

        return sign + RUPEE_SYMBOL + groupIndianDigits(wholeRupees) + DECIMAL_SEPARATOR + paise
    }

    /**
     * Formats money as the short label of a chart's value axis - for example `₹20K`, `₹2.5L`, `₹3Cr`.
     *
     * ### This is a label, never a figure
     *
     * Every amount the household *reads as their money* is drawn by [formatInr], which is exact to
     * the paise. This one is deliberately not that: it names a gridline, where the useful question
     * is "roughly how much is this line?" and the answer has to be short enough to sit in a 44dp
     * column. A tick of ₹20,000 printed as `₹20,000.00` would be wider than the chart.
     *
     * ### Still integer arithmetic
     *
     * The paise are dropped and the rounding is half-up, in `Long` division - no `Float` and no
     * `Double` anywhere, for the same reason the exact formatter avoids them: this app never lets
     * money become a binary fraction, not even a label. One decimal place is kept when it is not
     * zero, so `₹2.5L` does not print as `₹2.5L` truncated to `₹3L` or padded to `₹2.0L`.
     *
     * The unit suffix is the Indian one the household counts in: thousand (`K`), lakh (`L`) and
     * crore (`Cr`).
     */
    fun formatCompactInr(amountInPaise: Long): String {
        val sign = if (amountInPaise < 0L) "-" else ""
        val magnitude = if (amountInPaise < 0L) -amountInPaise else amountInPaise

        // Dropping the paise is the whole point of a compact label; the value it names is readable
        // off the gridline, and the exact figure is printed wherever the amount itself is shown.
        val rupees = magnitude / 100L

        val (divisor, suffix) = when {
            rupees >= CRORE -> CRORE to "Cr"
            rupees >= LAKH -> LAKH to "L"
            rupees >= THOUSAND -> THOUSAND to "K"
            else -> 1L to ""
        }

        // Tenths, half-up: 25,000,000 paise (₹2,50,000) is 25 tenths of a lakh, and ₹2,50,001 is
        // still 25.0 tenths rather than 25.1, because a gridline label is a magnitude and not a
        // measurement of the household's spending.
        val tenths = (rupees * TENTHS_PER_UNIT + divisor / 2L) / divisor
        val whole = tenths / TENTHS_PER_UNIT
        val fraction = tenths % TENTHS_PER_UNIT
        val number = if (fraction == 0L) "$whole" else "$whole.$fraction"

        return "$sign$RUPEE_SYMBOL$number$suffix"
    }

    /**
     * Groups digits the Indian way: the last three digits keep their group, then digits are
     * grouped in twos. `12345678` becomes `1,23,45,678`.
     */
    private fun groupIndianDigits(digits: String): String {
        if (digits.length <= 3) return digits

        val tail = digits.takeLast(3)
        val head = digits.dropLast(3).reversed().chunked(2).joinToString(GROUP_SEPARATOR).reversed()
        return "$head$GROUP_SEPARATOR$tail"
    }
}
