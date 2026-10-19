package com.homefinance.app.feature.reports

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The report's arithmetic: the two percentage rules and the two axis rules.
 *
 * All four are pure integer functions over `Long` values, which is the point - a figure that reaches
 * the screen has passed through no binary fraction on the way.
 */
class ReportMathTest {

    // -------------------------------------------------------------- percentOf

    @Test
    fun aShareIsAWholePercentageRoundedHalfUp() {
        assertEquals(25, percentOf(value = 2_500L, base = 10_000L))
        assertEquals(33, percentOf(value = 1L, base = 3L))
        assertEquals(67, percentOf(value = 2L, base = 3L))
        // Exactly a half rounds away from zero rather than to even, so the rule is one rule.
        assertEquals(13, percentOf(value = 1L, base = 8L))
    }

    @Test
    fun aShareOfNothingIsZero() {
        assertEquals(0, percentOf(value = 0L, base = 10_000L))
    }

    @Test
    fun aNegativeValueKeepsItsSign() {
        // The only negative figure this is ever asked about is a net cash flow, and a loss has to read
        // as a loss.
        assertEquals(-50, percentOf(value = -5_000L, base = 10_000L))
    }

    @Test
    fun aShareAgainstNoBaseIsRefusedRatherThanGuessed() {
        // Callers decide what "a share of nothing" means before they get here; this cannot invent it,
        // and a silent 0 would hide a caller that had forgotten to.
        assertThrows(IllegalArgumentException::class.java) { percentOf(value = 10L, base = 0L) }
        assertThrows(IllegalArgumentException::class.java) { percentOf(value = 10L, base = -1L) }
    }

    @Test
    fun anAbsurdlyLargeValueSaturatesRatherThanWrapping() {
        // A figure typed by accident must not be able to overflow into a negative percentage.
        val percent = percentOf(value = Long.MAX_VALUE, base = 1L)

        assertEquals(Int.MAX_VALUE, percent)
        assertTrue(percent > 0)
    }

    // ----------------------------------------------------------- percentChange

    @Test
    fun aRiseAndAFallAreReportedInWholePercent() {
        assertEquals(10, percentChange(current = 11_000L, previous = 10_000L))
        assertEquals(-10, percentChange(current = 9_000L, previous = 10_000L))
        assertEquals(0, percentChange(current = 5_000L, previous = 5_000L))
        assertEquals(-100, percentChange(current = 0L, previous = 10_000L))
    }

    @Test
    fun thereIsNoChangeToReportAgainstNothing() {
        // "Up 100%" against a month in which nothing happened is arithmetic dressed as information.
        assertNull(percentChange(current = 10_000L, previous = 0L))
    }

    @Test
    fun thereIsNoChangeToReportAgainstALoss() {
        // A move from -₹5,000 to -₹1,000 is an improvement, but it is not a percentage of anything.
        assertNull(percentChange(current = -1_000L, previous = -5_000L))
        assertNull(percentChange(current = 4_000L, previous = -5_000L))
    }

    // ----------------------------------------------------------- niceAxisMax

    @Test
    fun aChartWithNothingInItStillHasAScale() {
        assertEquals(1L, niceAxisMax(0L))
        assertEquals(1L, niceAxisMax(-5_000L))
    }

    @Test
    fun theScaleRoundsUpToTheNextRoundFigure() {
        assertEquals(1L, niceAxisMax(1L))
        assertEquals(2L, niceAxisMax(2L))
        assertEquals(5L, niceAxisMax(3L))
        assertEquals(5L, niceAxisMax(5L))
        assertEquals(10L, niceAxisMax(6L))
        assertEquals(20L, niceAxisMax(11L))
        assertEquals(20L, niceAxisMax(20L))
        assertEquals(50L, niceAxisMax(21L))
        assertEquals(100L, niceAxisMax(51L))
        assertEquals(200L, niceAxisMax(101L))
        assertEquals(1_000L, niceAxisMax(501L))
    }

    @Test
    fun theScaleIsNeverBelowTheTallestBar() {
        // The whole contract: rounding up, never down, so no bar is silently clipped.
        val values = listOf(1L, 7L, 19L, 83L, 999L, 1_234_567L, Long.MAX_VALUE / 3)

        values.forEach { value -> assertTrue("$value must fit", niceAxisMax(value) >= value) }
    }

    @Test
    fun aFigureTooLargeToScaleAnswersItsOwnValue() {
        // Rather than loop forever or wrap round into a scale the bars would fall off the top of.
        assertEquals(Long.MAX_VALUE, niceAxisMax(Long.MAX_VALUE))
    }

    // ------------------------------------------------------------- axisTicks

    @Test
    fun theAxisIsLabelledFromTheTopDown() {
        assertEquals(listOf(20_000L, 15_000L, 10_000L, 5_000L, 0L), axisTicks(axisMax = 20_000L, axisMin = 0L))
    }

    @Test
    fun theTopAndBottomLabelsAreTheEndsOfTheScale() {
        val ticks = axisTicks(axisMax = 20_000L, axisMin = -10_000L)

        assertEquals(5, ticks.size)
        assertEquals(20_000L, ticks.first())
        assertEquals(-10_000L, ticks.last())
    }

    @Test
    fun theLabelsDescendSoABarCanBeReadAgainstThem() {
        val ticks = axisTicks(axisMax = 20_000L, axisMin = -10_000L)

        ticks.zipWithNext().forEach { (higher, lower) -> assertTrue("$higher then $lower", higher > lower) }
        assertTrue(abs(ticks[0] - ticks[1]) > 0L)
    }

    @Test
    fun twoLabelsAreEnoughForAChartThatAsksForTwo() {
        assertEquals(listOf(100L, 0L), axisTicks(axisMax = 100L, axisMin = 0L, count = 2))
    }

    @Test
    fun aFlatScaleDoesNotDivideByZero() {
        // A period with nothing in it has a scale that starts and ends at the same place.
        assertEquals(listOf(1L, 1L, 1L, 1L, 1L), axisTicks(axisMax = 1L, axisMin = 1L))
    }
}
