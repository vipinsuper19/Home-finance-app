package com.homefinance.app.core.util

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * What a typed quantity turned out to be.
 *
 * [Success] carries the exact [Long] count of **thousandths** of a unit; every other member is a
 * distinct reason the text could not be accepted, so the form can say *why* rather than printing one
 * generic "invalid quantity" for a blank field, a typo and a number too large to store.
 *
 * The failures are grouped under [Problem] on purpose, exactly as [MoneyParseResult] groups its own:
 * the form holds a `Problem?` for its error slot, so "an error, but unfortunately it was a success"
 * is not a state the screen can express.
 */
sealed interface QuantityParseResult {

    /** The text is exactly [thousandths] of a unit, with no rounding anywhere along the way. */
    data class Success(val thousandths: Long) : QuantityParseResult

    sealed interface Problem : QuantityParseResult {

        /** Nothing was typed. */
        data object Blank : Problem

        /** Not a plain decimal number: letters, a stray symbol, `1e5`, `1,5`, `1.2.3`, `1.`. */
        data object NotANumber : Problem

        /** More than three decimal places, for example `1.2345`. Never rounded on the user's behalf. */
        data object TooManyDecimalPlaces : Problem

        /** Zero or negative: a quantity is a magnitude, and the direction comes from the type. */
        data object NotPositive : Problem

        /** A positive quantity that does not fit in the [Long] count the ledger stores. */
        data object TooLarge : Problem
    }
}

/**
 * The one place a household's typed **quantity** is turned into the integer the ledger stores, and
 * the one place a quantity and a rate are turned into money.
 *
 * ### Why a quantity is an integer, and why it is not the amount
 *
 * A household buys milk in litres and vegetables in kilograms, so a quantity is genuinely fractional:
 * `1.5`, `0.25`, `2`. It is stored as a whole number of **thousandths** (1.5 becomes `1500`), for the
 * same reason money is stored as paise - a binary `Float` or `Double` cannot represent `0.1` exactly,
 * and a ledger that disagrees with the receipt is worse than one that refuses the entry. Three
 * decimal places is the precision a household scale or a milk bill actually uses.
 *
 * ### Why the amount is derived rather than typed
 *
 * When an entry is recorded by quantity and rate, the amount is *not* a second opinion: it is
 * `quantity × rate`. [amountPaise] is that multiplication, and it is the only definition of it in the
 * project. [com.homefinance.app.data.repository.TransactionRepository] calls the same function before
 * every write, so a row whose stored amount disagreed with its stored quantity and rate cannot be
 * written at all - see its `requireQuantityAndRateAgree`.
 *
 * ### Why the product is exact, and what "half-up" means here
 *
 * The multiplication and the division by a thousand both happen in [BigDecimal], so no value ever
 * passes through a `Float` or a `Double`. The exact product can land between two paise - `0.001`
 * units at `₹15.00` each is exactly `1.5` paise - and that one case is resolved the way a shop
 * resolves it: `RoundingMode.HALF_UP`, to the nearest paise with an exact half going up. Because the
 * multiplication is exact, the rounding decision is made on a real decimal half and never on a
 * binary approximation of one. A product that does not fit in the `Long` paise column comes back as
 * `null` rather than wrapped.
 */
object QuantityInput {

    /** Digits, optionally with a fractional part; or a bare fraction such as `.5`. */
    private val QUANTITY = Regex("""^\d+(\.\d+)?$|^\.\d+$""")

    private const val THOUSANDTHS_SCALE = 3
    private const val MAX_DECIMAL_PLACES = 3
    private const val MINUS = '-'
    private const val PLUS = '+'

    /** The unit a quantity is stored in, as a value rather than a literal in the division below. */
    private val THOUSANDTHS_PER_UNIT = BigDecimal(1_000)

    /**
     * Parses a typed quantity such as `1.5`, `0.25` or `2` into an exact count of thousandths.
     *
     * The rules mirror [MoneyInput.parseRupeesToPaise] deliberately - surrounding whitespace is
     * ignored, an optional leading `+` is accepted, a leading `-` is not, `0` is refused, a number too
     * large for the column is refused rather than wrapped, `\d` is ASCII so full-width digits are not
     * silently accepted - with three decimal places instead of two. The result is never rounded.
     */
    fun parseToThousandths(raw: String): QuantityParseResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return QuantityParseResult.Problem.Blank

        val negative = trimmed.startsWith(MINUS)
        val digits = when {
            negative || trimmed.startsWith(PLUS) -> trimmed.substring(1)
            else -> trimmed
        }

        if (!QUANTITY.matches(digits)) return QuantityParseResult.Problem.NotANumber
        if (negative) return QuantityParseResult.Problem.NotPositive

        // `stripTrailingZeros` so `1.500` and `1.5` are the same quantity while `1.2345` still
        // carries too much precision to store exactly. `BigDecimal` reads a leading-dot form such as
        // `.5` correctly, so no normalisation of the text is needed first.
        val quantity = BigDecimal(digits).stripTrailingZeros()

        if (quantity.scale() > MAX_DECIMAL_PLACES) {
            return QuantityParseResult.Problem.TooManyDecimalPlaces
        }
        if (quantity.signum() == 0) return QuantityParseResult.Problem.NotPositive

        val thousandths = try {
            quantity.movePointRight(THOUSANDTHS_SCALE).longValueExact()
        } catch (_: ArithmeticException) {
            // The scale is at most three, so the only way this can throw is a value wider than Long.
            return QuantityParseResult.Problem.TooLarge
        }

        return QuantityParseResult.Success(thousandths)
    }

    /**
     * The exact paise for [quantityThousandths] units bought at [ratePaise] each, rounded half-up to
     * the nearest paise, or `null` when the product does not fit in a `Long` paise count.
     *
     * Both arguments are expected to be positive - they come from the two parsers above, or from a
     * stored row that this same rule admitted - so the rounding is only ever applied to a positive
     * value, where "half up" and "half away from zero" are the same rule.
     */
    fun amountPaise(quantityThousandths: Long, ratePaise: Long): Long? {
        val product = BigDecimal.valueOf(quantityThousandths)
            .multiply(BigDecimal.valueOf(ratePaise))

        return try {
            product.divide(THOUSANDTHS_PER_UNIT, 0, RoundingMode.HALF_UP).longValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }
}
