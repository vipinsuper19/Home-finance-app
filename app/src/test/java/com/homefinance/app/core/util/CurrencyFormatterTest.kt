package com.homefinance.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class CurrencyFormatterTest {

    @Test
    fun formatsPaiseAsRupeesWithTwoDecimals() {
        assertEquals("\u20B91,234.56", CurrencyFormatter.formatInr(123_456L))
    }

    @Test
    fun formatsZero() {
        assertEquals("\u20B90.00", CurrencyFormatter.formatInr(0L))
    }

    @Test
    fun formatsWholeRupeesWithTwoDecimalPlaces() {
        assertEquals("\u20B9500.00", CurrencyFormatter.formatInr(50_000L))
    }

    @Test
    fun usesIndianDigitGroupingForLargeAmounts() {
        // 1,234,567,890 paise is ₹1,23,45,678.90 in the Indian numbering system.
        assertEquals("\u20B91,23,45,678.90", CurrencyFormatter.formatInr(1_234_567_890L))
    }

    @Test
    fun formatsNegativeAmountsWithALeadingMinus() {
        assertEquals("-\u20B9500.00", CurrencyFormatter.formatInr(-50_000L))
    }
}
