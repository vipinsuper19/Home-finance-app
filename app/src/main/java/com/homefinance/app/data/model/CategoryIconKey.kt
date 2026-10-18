package com.homefinance.app.data.model

import java.util.Locale

/**
 * The stored names of a category's mark.
 *
 * ### Why the key is stored rather than only guessed
 *
 * A category's picture used to be derived from its name alone, at the moment it was drawn. That
 * works for "Milk" and fails for the household's own words: "Kirana", "Doodh", a shop named after
 * its owner. Once the mark is a *stored* value it is a fact about the category rather than a guess
 * made beside it, so the twelve categories the app ships with can each say exactly what they look
 * like, and a category the household adds still gets a sensible picture without being asked for one.
 *
 * ### The key is not the picture
 *
 * A key is a short stable token - `"milk"`, `"home_rent"` - and nothing more. Which glyph and which
 * two colours that token means is a *presentation* decision and lives in
 * `com.homefinance.app.core.ui.icon.CategoryIcons`, so the palette can change without a migration.
 * Nothing financial is ever keyed off this value: it is decoration beside a name that is always
 * shown, and getting it wrong costs a wrong picture, never a wrong number.
 *
 * ### Storage
 *
 * [com.homefinance.app.data.local.entity.CategoryEntity.iconKey] holds one of these strings, or
 * `null` for "nobody chose one" - which is the state of every category written before marks were
 * stored, and of every category the household adds through the picker. `null` means *derive it from
 * the name*, never "no picture".
 */
object CategoryIconKey {

    /** Milk and the rest of the dairy. */
    const val MILK: String = "milk"

    /** What comes home from a shop: groceries, provisions, a supermarket run. */
    const val GROCERIES: String = "groceries"

    /** The rent on a shop, an office or any premises money is earned from. */
    const val SHOP_RENT: String = "shop_rent"

    /** The rent on the house the household lives in. */
    const val HOME_RENT: String = "home_rent"

    /** Money that arrived as pay. */
    const val SALARY: String = "salary"

    /** Power, current, a bill with a meter behind it. */
    const val ELECTRICITY: String = "electricity"

    /** A gas cylinder or a piped connection. */
    const val GAS: String = "gas"

    /** Broadband, wi-fi and the other things that keep a connection alive. */
    const val INTERNET: String = "internet"

    /** Vegetables, fruit and the day's food. */
    const val VEGETABLES: String = "vegetables"

    /** A doctor, a chemist, a hospital. */
    const val MEDICAL: String = "medical"

    /** Fares and fuel. */
    const val TRANSPORT: String = "transport"

    /** Money deliberately put away rather than spent. */
    const val INVESTMENTS: String = "investments"

    /** The fallback: a category whose name describes nothing more specific. */
    const val OTHER: String = "other"

    /**
     * Every key this build knows, so a stored value can be told apart from a stale or hand-written
     * one. Anything not in here is treated as "nobody chose one" and derived from the name instead,
     * which is always better than showing the household a generic picture because of a typo.
     */
    val KNOWN: Set<String> = setOf(
        MILK,
        GROCERIES,
        SHOP_RENT,
        HOME_RENT,
        SALARY,
        ELECTRICITY,
        GAS,
        INTERNET,
        VEGETABLES,
        MEDICAL,
        TRANSPORT,
        INVESTMENTS,
        OTHER,
    )

    /**
     * The canonical form of [raw], or `null` when there is nothing usable in it.
     *
     * Trimming and lower-casing is the whole of the policy, and it is applied on the way *in* (the
     * seeder) and on the way *out* (the renderer) so that a value typed into a database by hand
     * still matches.
     */
    fun normalize(raw: String?): String? =
        raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    /** True when [raw] is, once normalised, one of the keys [KNOWN] lists. */
    fun isKnown(raw: String?): Boolean = normalize(raw) in KNOWN
}

/**
 * Guesses the mark for a category from the name the household wrote and the direction of its money.
 *
 * The type is consulted as well as the name, because a household's income categories are usually
 * named for a person or a client ("Freelance", "Ravi") rather than for money, and a wage slip is the
 * right picture for an amount that arrived. An income category always ends up [CategoryIconKey.SALARY]
 * unless its name says otherwise.
 *
 * Matching is on lower-cased substrings so "Electricity bill", "electricity" and "POWER BILL" agree,
 * and the order of the tests is the order of specificity: a name mentioning milk is milk even if it
 * also contains a word from another group. Shopping rent is tested before rent in general, because
 * "Shop rent" contains both words and is the more particular of the two.
 *
 * This is only ever used for a category that has no stored key, or a stored key this build does not
 * recognise. It is deliberately generous - a word list, not a parser - because being wrong costs a
 * wrong picture beside a name that is still correct.
 */
fun deriveCategoryIconKey(categoryName: String, type: TransactionType): String {
    val name = categoryName.lowercase(Locale.ROOT)

    return when {
        name.containsAny(MILK_WORDS) -> CategoryIconKey.MILK
        name.containsAny(SHOP_RENT_WORDS) -> CategoryIconKey.SHOP_RENT
        name.containsAny(HOME_RENT_WORDS) -> CategoryIconKey.HOME_RENT
        name.containsAny(GROCERY_WORDS) -> CategoryIconKey.GROCERIES
        name.containsAny(VEGETABLE_WORDS) -> CategoryIconKey.VEGETABLES
        name.containsAny(ELECTRICITY_WORDS) -> CategoryIconKey.ELECTRICITY
        name.containsAny(GAS_WORDS) -> CategoryIconKey.GAS
        name.containsAny(INTERNET_WORDS) -> CategoryIconKey.INTERNET
        name.containsAny(MEDICAL_WORDS) -> CategoryIconKey.MEDICAL
        name.containsAny(TRANSPORT_WORDS) -> CategoryIconKey.TRANSPORT
        name.containsAny(INVESTMENT_WORDS) -> CategoryIconKey.INVESTMENTS
        type == TransactionType.INCOME -> CategoryIconKey.SALARY
        else -> CategoryIconKey.OTHER
    }
}

/**
 * The key a renderer should use for one category: the stored one when it is usable, and a guess from
 * the name otherwise.
 */
fun categoryIconKeyFor(categoryName: String, type: TransactionType, storedIconKey: String?): String =
    CategoryIconKey.normalize(storedIconKey)
        ?.takeIf { it in CategoryIconKey.KNOWN }
        ?: deriveCategoryIconKey(categoryName = categoryName, type = type)

private fun String.containsAny(words: Set<String>): Boolean = words.any { contains(it) }

/**
 * Stems rather than whole words, so "groceries", "grocery" and "grocers" all agree without listing
 * every inflection a household might type.
 */
private val MILK_WORDS = setOf("milk", "dairy", "curd", "doodh", "paneer", "butter", "ghee", "lassi")

private val SHOP_RENT_WORDS = setOf("shop", "store", "dukan", "dukaan", "commercial", "market", "office")

/** `rent`, `house`, `home` and the rest - see the ordering note on [deriveCategoryIconKey]. */
private val HOME_RENT_WORDS = setOf("rent", "house", "home", "flat", "kiraya", "landlord", "residence")

private val GROCERY_WORDS = setOf(
    "grocer",
    "supermarket",
    "kirana",
    "ration",
    "provision",
    "essentials",
    "shopping",
)

private val VEGETABLE_WORDS = setOf(
    "vegetab",
    "fruit",
    "sabzi",
    "sabji",
    "food",
    "snack",
    "meal",
    "kitchen",
    "restaurant",
    "dhaba",
    "tiffin",
)

private val ELECTRICITY_WORDS = setOf("electric", "power", "bolt", "current")

private val GAS_WORDS = setOf("gas", "cylinder", "lpg")

private val INTERNET_WORDS = setOf(
    "internet",
    "wifi",
    "wi-fi",
    "broadband",
    "dth",
    "cable",
    "recharge",
    "mobile",
    "phone",
    "postpaid",
    "prepaid",
)

private val MEDICAL_WORDS = setOf(
    "medical",
    "medicine",
    "health",
    "hospital",
    "doctor",
    "clinic",
    "pharmacy",
    "chemist",
    "dental",
)

private val TRANSPORT_WORDS = setOf(
    "transport",
    "car",
    "bike",
    "scooter",
    "petrol",
    "diesel",
    "fuel",
    "bus",
    "train",
    "auto",
    "taxi",
    "cab",
    "metro",
    "travel",
    "fare",
)

private val INVESTMENT_WORDS = setOf(
    "invest",
    "saving",
    "sip",
    "mutual",
    "share",
    "stock",
    "insurance",
    "policy",
    "pension",
)
