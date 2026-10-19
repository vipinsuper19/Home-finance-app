package com.homefinance.app.core.util

/**
 * A whole number as an English ordinal: 1 as "1st", 12 as "12th", 22 as "22nd".
 *
 * ### Why this is not a string resource
 *
 * The Home screen states how a recurring date comes round - "Monthly (on 5th)" - and "5th" is a
 * number wearing a suffix. It cannot be a plural resource, which is where every other counted phrase
 * in this app lives, because English ordinal suffixes are not chosen by *quantity*: 11, 12 and 13 take
 * "th" although each ends in 1, 2 or 3, while 21, 22 and 23 take "st", "nd" and "rd". Android's plural
 * rules would pick "one" for 21 and 1 alike and so render "21th".
 *
 * It is therefore English in the code, and it is honest about that: this app ships in English only -
 * there is no second `strings.xml` for it to disagree with - and the day of the month is the one
 * place it needs a suffix rather than a noun. A localized build would have to replace this with a
 * locale-aware ordinal formatter, and the whole app would need translating at the same time.
 *
 * The rules are the ordinary ones: "1st", "2nd", "3rd", then "th" from 4 to 20, and the same pattern
 * repeated every hundred - so 21st, 22nd, 23rd, 101st, and 111th, 112th, 113th.
 */
object EnglishOrdinal {

    /**
     * [value] written as an ordinal: `5` becomes "5th".
     *
     * Only ever called with a day of a month, so the behaviour outside that range is simply the same
     * arithmetic rather than something a caller should rely on.
     */
    fun format(value: Int): String = "$value${suffixFor(value)}"

    /**
     * The two letters that follow [value].
     *
     * The teens are tested first and as a group of three, because that is the rule: 11, 12 and 13 are
     * not "11st", "12nd" and "13rd" however they end.
     */
    private fun suffixFor(value: Int): String = when {
        value.mod(100) in 11..13 -> "th"
        value.mod(10) == 1 -> "st"
        value.mod(10) == 2 -> "nd"
        value.mod(10) == 3 -> "rd"
        else -> "th"
    }
}
