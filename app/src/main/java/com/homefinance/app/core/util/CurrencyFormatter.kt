package com.homefinance.app.core.util

import java.math.BigDecimal

private const val PAISE_SCALE = 2
private const val RUPEE_SYMBOL = "\u20B9"
private const val GROUP_SEPARATOR = ","
private const val DECIMAL_SEPARATOR = "."

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
