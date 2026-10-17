package com.homefinance.app.core.util

import java.math.BigDecimal

/**
 * What a typed amount turned out to be.
 *
 * [Success] carries the exact [Long] count of **paise**; every other member is a distinct reason
 * the text could not be accepted, so the form can say *why* rather than printing one generic
 * "invalid amount" for a blank field, a typo and a number too large to store.
 *
 * The failures are grouped under [Problem] on purpose. The form holds a `Problem?` for its error
 * slot, so "an error, but unfortunately it was a success" is not a state the screen can express -
 * the same reasoning that keeps rows out of
 * [com.homefinance.app.feature.transactions.TransactionsUiState.Error].
 */
sealed interface MoneyParseResult {

    /** The text is exactly [paise], with no rounding anywhere along the way. */
    data class Success(val paise: Long) : MoneyParseResult

    sealed interface Problem : MoneyParseResult {

        /** Nothing was typed. */
        data object Blank : Problem

        /** Not a plain decimal number: letters, a stray symbol, `1e5`, `1,000`, `1.2.3`, `1.`. */
        data object NotANumber : Problem

        /** More than two decimal places, for example `100.999`. Never rounded on the user's behalf. */
        data object TooManyDecimalPlaces : Problem

        /** Zero or negative: an amount is a magnitude, and the direction comes from the type. */
        data object NotPositive : Problem

        /** A positive amount that does not fit in the [Long] paise count the ledger stores. */
        data object TooLarge : Problem
    }
}

/**
 * The one place a household's typed rupee amount is turned into the paise integer the ledger
 * stores.
 *
 * ### Why this is not `toDouble() * 100`
 *
 * Binary floating point cannot represent `0.10`, so a `Double` round-trip does not merely lose a
 * paise here and there - it loses them *invisibly*, and a ledger that disagrees with the receipt
 * by one paise is worse than one that refuses the entry. The text is therefore parsed as an exact
 * decimal ([BigDecimal]) and scaled with powers of ten, so `100.50` becomes exactly `10050`
 * paise. No value ever passes through a `Float` or a `Double`.
 *
 * ### Why it refuses rather than rounds
 *
 * `100.999` is rejected as [MoneyParseResult.Problem.TooManyDecimalPlaces] instead of being
 * rounded to `101.00` or truncated to `100.99`. Silently reinterpreting a figure the household
 * typed is exactly the class of bug this class exists to prevent; telling them to fix it is not.
 * Trailing zeros beyond two places are not treated as extra precision, so `1.000` is accepted as
 * ₹1.00 - it says exactly the same number.
 *
 * ### What "valid" means
 *
 *  - surrounding whitespace is ignored, so a pasted `" 250 "` is ₹250.00;
 *  - an optional leading `+` is accepted, a leading `-` is not ([Problem.NotPositive]);
 *  - `0`, `0.00` and `-5` are all [Problem.NotPositive]: the ledger stores a positive magnitude
 *    and takes the direction from the transaction type;
 *  - a number too large for the `Long` paise column is [Problem.TooLarge], never wrapped around;
 *  - `\d` is ASCII, so full-width digits are not silently accepted as if they were ordinary ones.
 */
object MoneyInput {

    /** Digits, optionally with a fractional part; or a bare fraction such as `.5`. */
    private val AMOUNT = Regex("""^\d+(\.\d+)?$|^\.\d+$""")

    private const val PAISE_SCALE = 2
    private const val MAX_DECIMAL_PLACES = 2
    private const val MINUS = '-'
    private const val PLUS = '+'

    /**
     * Parses a typed rupee amount such as `250`, `250.5` or `.5` into an exact count of paise.
     *
     * The result is never rounded and never negative: see the class documentation for the exact
     * rules and for why each failure is reported separately.
     */
    fun parseRupeesToPaise(raw: String): MoneyParseResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return MoneyParseResult.Problem.Blank

        val negative = trimmed.startsWith(MINUS)
        val digits = when {
            negative || trimmed.startsWith(PLUS) -> trimmed.substring(1)
            else -> trimmed
        }

        if (!AMOUNT.matches(digits)) return MoneyParseResult.Problem.NotANumber
        if (negative) return MoneyParseResult.Problem.NotPositive

        // `stripTrailingZeros` so `1.000` and `1.00` are the same money while `100.999` still
        // carries too much precision to store exactly. `BigDecimal` reads a leading-dot form such
        // as `.5` correctly, so no normalisation of the text is needed first.
        val amount = BigDecimal(digits).stripTrailingZeros()

        if (amount.scale() > MAX_DECIMAL_PLACES) {
            return MoneyParseResult.Problem.TooManyDecimalPlaces
        }
        if (amount.signum() == 0) return MoneyParseResult.Problem.NotPositive

        val paise = try {
            amount.movePointRight(PAISE_SCALE).longValueExact()
        } catch (_: ArithmeticException) {
            // The scale is at most two, so the only way this can throw is a value wider than Long.
            return MoneyParseResult.Problem.TooLarge
        }

        return MoneyParseResult.Success(paise)
    }

    /**
     * The exact rupee text for [paise] - `320` for 32000, `90.5` for 9050 - which is what the
     * amount field holds while an existing transaction is being edited.
     *
     * This is [parseRupeesToPaise]'s inverse, and it is written the other way round from
     * [CurrencyFormatter.formatInr] on purpose. That one is *display*: it adds the rupee symbol
     * and Indian grouping, and its output is for reading, not for typing. This one goes back into
     * an input field the parser will read again, so it must be plain text the parser accepts - a
     * `\u20B9` or a `,` would come back as [MoneyParseResult.Problem.NotANumber] and the household
     * would be told their own saved amount is invalid.
     *
     * Trailing zeros are dropped (`320`, not `320.00`) so the figure looks like something a person
     * typed, and `toPlainString` keeps a whole number whole - `stripTrailingZeros` alone would
     * render 32000 paise as `3.2E+2`. Parsing the result returns [paise] exactly, which is asserted
     * for every value in the range a ledger can hold.
     */
    fun toEditableText(paise: Long): String =
        BigDecimal.valueOf(paise, PAISE_SCALE).stripTrailingZeros().toPlainString()
}
