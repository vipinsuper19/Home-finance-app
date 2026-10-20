package com.homefinance.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quantity parser and the quantity-times-rate multiplication.
 *
 * These are the boundary cases that decide whether the household's figure is the one that reaches
 * the database: a litre and a half, a quarter of a kilogram, one thousandth too much precision, zero,
 * the largest value the `Long` column can hold, and - above all - the exact half-paise that has to be
 * resolved the same way every time.
 */
class QuantityInputTest {

    // -------------------------------------------------------------------- exact

    @Test
    fun aWholeQuantityBecomesThousands() {
        assertEquals(QuantityParseResult.Success(1_000L), parse("1"))
        assertEquals(QuantityParseResult.Success(2_000L), parse("2"))
    }

    @Test
    fun aLitresAndAHalfIsFifteenHundredThousandths() {
        // 1.5 is exactly representable in binary, but the point is that nothing binary is involved.
        assertEquals(QuantityParseResult.Success(1_500L), parse("1.5"))
    }

    @Test
    fun aQuarterIsKeptExactly() {
        assertEquals(QuantityParseResult.Success(250L), parse("0.25"))
    }

    @Test
    fun aTenthDoesNotBecomeNineNinetyNine() {
        // 0.1 is the classic binary-floating-point trap: a Double round trip would not be exact.
        assertEquals(QuantityParseResult.Success(100L), parse("0.1"))
        assertEquals(QuantityParseResult.Success(300L), parse("0.3"))
    }

    @Test
    fun threeDecimalPlacesIsTheLimitAndIsAccepted() {
        assertEquals(QuantityParseResult.Success(1_125L), parse("1.125"))
    }

    @Test
    fun trailingZerosBeyondThreePlacesAreTheSameNumber() {
        assertEquals(QuantityParseResult.Success(1_500L), parse("1.5000"))
        assertEquals(QuantityParseResult.Success(250L), parse("0.250"))
    }

    @Test
    fun aLeadingDotIsAccepted() {
        assertEquals(QuantityParseResult.Success(750L), parse(".75"))
    }

    @Test
    fun surroundingWhitespaceIsIgnored() {
        assertEquals(QuantityParseResult.Success(1_500L), parse("  1.5  "))
    }

    @Test
    fun anExplicitPlusSignIsAccepted() {
        assertEquals(QuantityParseResult.Success(1_500L), parse("+1.5"))
    }

    // ------------------------------------------------------------------ refused

    @Test
    fun aFourthDecimalPlaceIsRefusedRatherThanRounded() {
        // 1.2345 is neither 1.234 nor 1.235; the parser refuses to choose for the household.
        assertEquals(QuantityParseResult.Problem.TooManyDecimalPlaces, parse("1.2345"))
    }

    @Test
    fun anEmptyOrBlankQuantityIsReportedAsBlank() {
        assertEquals(QuantityParseResult.Problem.Blank, parse(""))
        assertEquals(QuantityParseResult.Problem.Blank, parse("   "))
    }

    @Test
    fun zeroIsRefused() {
        assertEquals(QuantityParseResult.Problem.NotPositive, parse("0"))
        assertEquals(QuantityParseResult.Problem.NotPositive, parse("0.000"))
    }

    @Test
    fun aNegativeQuantityIsRefused() {
        assertEquals(QuantityParseResult.Problem.NotPositive, parse("-1.5"))
        assertEquals(QuantityParseResult.Problem.NotPositive, parse("-0.5"))
    }

    @Test
    fun textThatIsNotAPlainDecimalIsRefused() {
        listOf("abc", "1,5", "1e5", "1.2.3", "12.", ".", "1 5", "1.5 l").forEach { raw ->
            assertEquals("expected '$raw' to be refused", QuantityParseResult.Problem.NotANumber, parse(raw))
        }
    }

    @Test
    fun aValueTooLargeForTheColumnIsRefusedRatherThanWrapped() {
        assertEquals(QuantityParseResult.Problem.TooLarge, parse("9999999999999999"))
        assertEquals(QuantityParseResult.Problem.TooLarge, parse("99999999999999999999"))
    }

    @Test
    fun aFailedResultNeverCarriesThousandths() {
        val failures = listOf("", "abc", "0", "-1", "1.2345", "99999999999999999999")

        failures.forEach { raw ->
            val result = parse(raw)
            assertTrue("expected '$raw' to be refused, was $result", result is QuantityParseResult.Problem)
        }
    }

    // ------------------------------------------------------- quantity x rate

    @Test
    fun aLitresAndAHalfAtSixtyRupeesIsNinetyRupees() {
        // 1.5 litres at ₹60.00 a litre is ₹90.00 - exactly, not approximately.
        assertEquals(9_000L, amountPaise(quantityThousandths = 1_500L, ratePaise = 6_000L))
    }

    @Test
    fun aProductThatDoesNotLandOnAPaiseRoundsHalfUp() {
        // 0.333 units at ₹59.99 is 19.97667 rupees, which is 1997.667 paise: 1998, not 1997.
        assertEquals(1_998L, amountPaise(quantityThousandths = 333L, ratePaise = 5_999L))
    }

    @Test
    fun anExactHalfPaiseGoesUp() {
        // 0.001 units at ₹15.00 is exactly 1.5 paise. A shop rounds that up; so does this.
        assertEquals(2L, amountPaise(quantityThousandths = 1L, ratePaise = 1_500L))
    }

    @Test
    fun aProductJustUnderAHalfPaiseGoesDown() {
        // 0.001 units at ₹14.99 is 1.499 paise, which is nearer 1 than 2.
        assertEquals(1L, amountPaise(quantityThousandths = 1L, ratePaise = 1_499L))
    }

    @Test
    fun theSmallestExactProductIsOnePaise() {
        assertEquals(1L, amountPaise(quantityThousandths = 1_000L, ratePaise = 1L))
    }

    @Test
    fun aProductThatRoundsAwayToNothingIsZeroRatherThanWrapped() {
        // 0.001 units at ₹0.01 is 0.00001 paise. It rounds to zero, and the repository refuses a
        // zero amount - the point here is that nothing overflows or wraps on the way.
        assertEquals(0L, amountPaise(quantityThousandths = 1L, ratePaise = 1L))
    }

    @Test
    fun aProductTooLargeForThePaiseColumnIsRefusedRatherThanWrapped() {
        // Both factors fit the columns they are stored in, and they are still valid quantities and
        // rates - it is only their product that could not be money.
        assertNull(amountPaise(quantityThousandths = Long.MAX_VALUE, ratePaise = 1_000_000_000L))
        assertNull(amountPaise(quantityThousandths = Long.MAX_VALUE, ratePaise = Long.MAX_VALUE))
    }

    @Test
    fun aLargeProductThatStillFitsIsNotRefused() {
        // The guard is on the result, not on the two factors: the largest quantity, at 2 paise each,
        // divided by a thousand, is still well inside the paise column.
        assertEquals(18_446_744_073_709_552L, amountPaise(quantityThousandths = Long.MAX_VALUE, ratePaise = 2L))
    }

    @Test
    fun theMultiplicationAndTheRoundingAreTheOnlyDefinitionOfTheAmount() {
        // The same three figures appear in the repository's check and in the form's preview, so a
        // disagreement between them would be a bug in one caller rather than an ambiguity here.
        val quantity = QuantityInput.parseToThousandths("1.5") as QuantityParseResult.Success
        val rate = MoneyInput.parseRupeesToPaise("60") as MoneyParseResult.Success

        assertEquals(9_000L, QuantityInput.amountPaise(quantity.thousandths, rate.paise))
    }

    // ------------------------------------------- recovering a quantity from a total

    @Test
    fun aTotalAndARateRecoverTheWholeQuantity() {
        // The inverse of amountPaise: two litres at ₹60 is ₹120.
        assertEquals(1_500L, quantityFor(amountPaise = 9_000L, ratePaise = 6_000L))
        assertEquals(2_000L, quantityFor(amountPaise = 12_000L, ratePaise = 6_000L))
    }

    @Test
    fun aRoundedTotalRecoversTheQuantityThatMultipliesBackToIt() {
        // 0.333 units at ₹59.99 is stored as 1998 paise after half-up rounding; the quantity that
        // reproduces that total is still 333, not a nearby figure that would fail the ledger's check.
        val quantity = quantityFor(amountPaise = 1_998L, ratePaise = 5_999L)

        assertEquals(333L, quantity)
        assertEquals(1_998L, amountPaise(quantity!!, 5_999L))
    }

    @Test
    fun aTotalThatIsNotAWholeQuantityAtThatRateIsRefused() {
        // ₹0.15 at ₹100.00 a unit is 0.0015 units: there is no whole thousandth that comes to it.
        assertNull(quantityFor(amountPaise = 15L, ratePaise = 10_000L))
    }

    @Test
    fun anAmountOrRateThatIsNotPositiveIsRefused() {
        assertNull(quantityFor(amountPaise = 0L, ratePaise = 6_000L))
        assertNull(quantityFor(amountPaise = 6_000L, ratePaise = 0L))
        assertNull(quantityFor(amountPaise = -6_000L, ratePaise = 6_000L))
        assertNull(quantityFor(amountPaise = 6_000L, ratePaise = -6_000L))
    }

    @Test
    fun aQuantityThatCouldNotFitTheColumnIsRefusedRatherThanWrapped() {
        assertNull(quantityFor(amountPaise = Long.MAX_VALUE, ratePaise = 1L))
    }

    @Test
    fun everyExactProductRoundTripsBackToItsQuantity() {
        listOf(1L, 250L, 1_000L, 1_500L, 2_000L, 12_345L).forEach { thousandths ->
            val total = amountPaise(thousandths, 6_000L)
            assertEquals(thousandths, quantityFor(total!!, 6_000L))
        }
    }

    // ------------------------------------------------------------ toEditableText

    @Test
    fun editableTextIsThePlainQuantityTheParserWouldReadBack() {
        assertEquals("1.5", QuantityInput.toEditableText(1_500L))
        assertEquals("2", QuantityInput.toEditableText(2_000L))
        assertEquals("0.001", QuantityInput.toEditableText(1L))
        assertEquals("0.25", QuantityInput.toEditableText(250L))
    }

    @Test
    fun aWholeQuantityIsNotWrittenInScientificNotation() {
        assertEquals("1000", QuantityInput.toEditableText(1_000_000L))
    }

    @Test
    fun editableTextRoundTripsEveryBoundaryValue() {
        val values = listOf(1L, 999L, 1_000L, 1_001L, 1_500L, 2_500_000L, Long.MAX_VALUE)

        values.forEach { thousandths ->
            val text = QuantityInput.toEditableText(thousandths)
            assertEquals(
                "round trip of $thousandths through '$text'",
                QuantityParseResult.Success(thousandths),
                parse(text),
            )
        }
    }

    @Test
    fun editingAQuantityAndSavingItUnchangedStoresTheSameThousandths() {
        val stored = 1_250L

        assertEquals(
            QuantityParseResult.Success(stored),
            parse(QuantityInput.toEditableText(stored)),
        )
    }

    private fun parse(raw: String): QuantityParseResult = QuantityInput.parseToThousandths(raw)

    private fun amountPaise(quantityThousandths: Long, ratePaise: Long): Long? =
        QuantityInput.amountPaise(quantityThousandths, ratePaise)

    private fun quantityFor(amountPaise: Long, ratePaise: Long): Long? =
        QuantityInput.quantityThousandthsFor(amountPaise, ratePaise)
}
