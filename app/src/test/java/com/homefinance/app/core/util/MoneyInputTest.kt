package com.homefinance.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rupee-to-paise parser.
 *
 * These are the boundary cases that decide whether the household's figure is the one that reaches
 * the database: exact halves of a rupee, trailing zeros, one paise too much precision, zero, and
 * the largest value the `Long` paise column can hold.
 */
class MoneyInputTest {

    // -------------------------------------------------------------------- exact

    @Test
    fun wholeRupeesBecomePaise() {
        assertEquals(MoneyParseResult.Success(10_000L), parse("100"))
    }

    @Test
    fun paiseAreKeptExactly() {
        assertEquals(MoneyParseResult.Success(10_050L), parse("100.50"))
    }

    @Test
    fun twentyFivePaiseIsTwentyFivePaise() {
        assertEquals(MoneyParseResult.Success(25L), parse("0.25"))
    }

    @Test
    fun aTenPaiseAmountDoesNotBecomeNine() {
        // 0.1 is the classic binary-floating-point trap: a Double round trip would not be exact.
        assertEquals(MoneyParseResult.Success(10L), parse("0.1"))
        assertEquals(MoneyParseResult.Success(30L), parse("0.3"))
        assertEquals(MoneyParseResult.Success(110L), parse("1.1"))
    }

    @Test
    fun aLeadingDotIsAccepted() {
        assertEquals(MoneyParseResult.Success(50L), parse(".5"))
    }

    @Test
    fun surroundingWhitespaceIsIgnored() {
        assertEquals(MoneyParseResult.Success(25_000L), parse("  250  "))
    }

    @Test
    fun anExplicitPlusSignIsAccepted() {
        assertEquals(MoneyParseResult.Success(25_000L), parse("+250"))
    }

    @Test
    fun leadingZerosDoNotChangeTheValue() {
        assertEquals(MoneyParseResult.Success(50L), parse("00.50"))
        assertEquals(MoneyParseResult.Success(7L * 100), parse("007"))
    }

    @Test
    fun trailingZerosBeyondTwoPlacesAreTheSameNumber() {
        // 1.000 says exactly what 1.00 says, so it is not extra precision to refuse.
        assertEquals(MoneyParseResult.Success(100L), parse("1.000"))
        assertEquals(MoneyParseResult.Success(25L), parse("0.250"))
    }

    @Test
    fun twoDecimalPlacesIsTheLimitAndIsAccepted() {
        assertEquals(MoneyParseResult.Success(10_099L), parse("100.99"))
    }

    // ------------------------------------------------------------------ refused

    @Test
    fun aThirdDecimalPlaceIsRefusedRatherThanRounded() {
        // 100.999 is neither 101.00 nor 100.99; the parser refuses to choose for the household.
        assertEquals(MoneyParseResult.Problem.TooManyDecimalPlaces, parse("100.999"))
    }

    @Test
    fun anEmptyOrBlankAmountIsReportedAsBlank() {
        assertEquals(MoneyParseResult.Problem.Blank, parse(""))
        assertEquals(MoneyParseResult.Problem.Blank, parse("   "))
    }

    @Test
    fun zeroIsRefused() {
        assertEquals(MoneyParseResult.Problem.NotPositive, parse("0"))
        assertEquals(MoneyParseResult.Problem.NotPositive, parse("0.00"))
        assertEquals(MoneyParseResult.Problem.NotPositive, parse("0.000"))
    }

    @Test
    fun aNegativeAmountIsRefused() {
        assertEquals(MoneyParseResult.Problem.NotPositive, parse("-5"))
        assertEquals(MoneyParseResult.Problem.NotPositive, parse("-0.25"))
    }

    @Test
    fun textThatIsNotAPlainDecimalIsRefused() {
        listOf("abc", "1,000", "1e5", "1.2.3", "12.", ".", "1 000", "₹100", "1.2f").forEach { raw ->
            assertEquals("expected '$raw' to be refused", MoneyParseResult.Problem.NotANumber, parse(raw))
        }
    }

    @Test
    fun aValueTooLargeForTheColumnIsRefusedRatherThanWrapped() {
        // One paise beyond Long.MAX_VALUE, expressed in rupees.
        assertEquals(MoneyParseResult.Problem.TooLarge, parse("92233720368547758.08"))
        assertEquals(MoneyParseResult.Problem.TooLarge, parse("99999999999999999999"))
    }

    @Test
    fun theLargestStorableAmountIsAcceptedExactly() {
        // 92,23,37,20,36,85,477.5807 rupees is Long.MAX_VALUE paise, and it must not be refused -
        // the boundary belongs to the accepted side, and it must round-trip to the exact value.
        assertEquals(
            MoneyParseResult.Success(Long.MAX_VALUE),
            parse("92233720368547758.07"),
        )
    }

    @Test
    fun aFailedResultNeverCarriesPaise() {
        val failures = listOf("", "abc", "0", "-1", "1.234", "92233720368547758.08")

        failures.forEach { raw ->
            val result = parse(raw)
            assertTrue("expected '$raw' to be refused, was $result", result is MoneyParseResult.Problem)
        }
    }

    // ------------------------------------------------------------ toEditableText

    @Test
    fun editableTextIsThePlainFigureTheParserWouldReadBack() {
        assertEquals("100", MoneyInput.toEditableText(10_000L))
        assertEquals("100.5", MoneyInput.toEditableText(10_050L))
        assertEquals("0.25", MoneyInput.toEditableText(25L))
        assertEquals("0.1", MoneyInput.toEditableText(10L))
    }

    @Test
    fun editableTextHasNoSymbolOrGroupingToConfuseTheParser() {
        // ₹1,00,000.00 is what the household *reads*; `100000` is what the field must hold, because
        // the parser would refuse a rupee sign or a comma as NotANumber.
        val editable = MoneyInput.toEditableText(10_000_000L)

        assertEquals("100000", editable)
        assertEquals(MoneyParseResult.Success(10_000_000L), parse(editable))
    }

    @Test
    fun aWholeNumberOfRupeesIsNotWrittenInScientificNotation() {
        // `stripTrailingZeros` on its own renders 32000 paise as `3.2E+2`; `toPlainString` is what
        // keeps the figure something a person could have typed.
        assertEquals("320", MoneyInput.toEditableText(32_000L))
        assertEquals("1000", MoneyInput.toEditableText(100_000L))
    }

    @Test
    fun editableTextRoundTripsEveryBoundaryValue() {
        val values = listOf(
            1L,
            9L,
            10L,
            99L,
            100L,
            101L,
            12_345L,
            99_999_999L,
            Long.MAX_VALUE,
        )

        values.forEach { paise ->
            val text = MoneyInput.toEditableText(paise)
            assertEquals(
                "round trip of $paise through '$text'",
                MoneyParseResult.Success(paise),
                parse(text),
            )
        }
    }

    @Test
    fun editingAnAmountAndSavingItUnchangedStoresTheSamePaise() {
        // The one property that matters for the edit form: prefill, touch nothing, save, and the
        // amount is what it was rather than one paise out.
        val stored = 10_050L
        val prefilled = MoneyInput.toEditableText(stored)

        assertEquals(MoneyParseResult.Success(stored), parse(prefilled))
    }

    private fun parse(raw: String): MoneyParseResult = MoneyInput.parseRupeesToPaise(raw)
}
