package com.homefinance.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The English ordinal suffixes, including the rule that makes them impossible as a plural resource:
 * 11, 12 and 13 take "th" although each of them ends in 1, 2 or 3.
 */
class EnglishOrdinalTest {

    @Test
    fun theFirstThreeTakeTheirOwnSuffixes() {
        assertEquals("1st", EnglishOrdinal.format(1))
        assertEquals("2nd", EnglishOrdinal.format(2))
        assertEquals("3rd", EnglishOrdinal.format(3))
    }

    @Test
    fun theRestOfTheFirstTwentyTakeTh() {
        assertEquals("4th", EnglishOrdinal.format(4))
        assertEquals("9th", EnglishOrdinal.format(9))
        assertEquals("10th", EnglishOrdinal.format(10))
        assertEquals("20th", EnglishOrdinal.format(20))
    }

    @Test
    fun theTeensTakeThEvenThoughTheyEndInOneTwoOrThree() {
        assertEquals("11th", EnglishOrdinal.format(11))
        assertEquals("12th", EnglishOrdinal.format(12))
        assertEquals("13th", EnglishOrdinal.format(13))
    }

    @Test
    fun thePatternPicksUpAgainAfterTwenty() {
        assertEquals("21st", EnglishOrdinal.format(21))
        assertEquals("22nd", EnglishOrdinal.format(22))
        assertEquals("23rd", EnglishOrdinal.format(23))
        assertEquals("30th", EnglishOrdinal.format(30))
        assertEquals("31st", EnglishOrdinal.format(31))
    }

    @Test
    fun theTeensRuleAppliesInEveryHundredToo() {
        assertEquals("101st", EnglishOrdinal.format(101))
        assertEquals("111th", EnglishOrdinal.format(111))
        assertEquals("112th", EnglishOrdinal.format(112))
        assertEquals("113th", EnglishOrdinal.format(113))
    }
}
