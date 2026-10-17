package com.homefinance.app.feature.transactions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.homefinance.app.core.ui.icon.HomeFinanceIcons
import com.homefinance.app.core.ui.theme.OnUtilityContainer
import com.homefinance.app.core.ui.theme.UtilityContainer
import com.homefinance.app.data.model.TransactionType
import java.util.Locale

/**
 * The mark drawn on the left of a ledger row.
 *
 * ### Why a kind rather than a stored icon
 *
 * A category stores a name and a type and nothing else: the database has no icon column, and this
 * change deliberately does not add one. The mark is therefore *derived* from the name the household
 * already wrote, which means it appears without anyone having to pick it, and a category created
 * five minutes ago is marked like one created a year ago.
 *
 * The derivation is a guess, and it is presented as one: it is only ever decoration beside the
 * category's name, which is the fact the row actually asserts. Getting it wrong costs a wrong
 * picture, never a wrong number.
 */
internal enum class CategoryIconKind {
    /** Milk, curd, anything from the dairy. */
    MILK,

    /** Groceries and the other things that come home from a shop. */
    GROCERIES,

    /** Money coming in. */
    INCOME,

    /** A recurring bill: power, water, gas, a phone. */
    BILLS,

    /** Anything the name does not describe. */
    OTHER,
}

/**
 * Chooses the mark for a category from its name and the direction of its money.
 *
 * The type is consulted as well as the name, because a household's income categories are usually
 * named for a person or a client ("Freelance", "Ravi") rather than for money, and a receipt is the
 * wrong picture for an amount that arrived. An income category always ends up with the banknote
 * unless its name says otherwise, which is the safe direction to be wrong in.
 *
 * Matching is on lower-cased substrings so "Electricity bill", "electricity" and "POWER BILL" agree,
 * and the order of the tests is the order of specificity: a name mentioning milk is milk even if it
 * also contains a word from another group.
 */
internal fun categoryIconKindFor(categoryName: String, type: TransactionType): CategoryIconKind {
    val name = categoryName.lowercase(Locale.ROOT)

    return when {
        name.containsAny(MILK_WORDS) -> CategoryIconKind.MILK
        name.containsAny(GROCERY_WORDS) -> CategoryIconKind.GROCERIES
        type == TransactionType.INCOME -> CategoryIconKind.INCOME
        name.containsAny(BILL_WORDS) -> CategoryIconKind.BILLS
        else -> CategoryIconKind.OTHER
    }
}

/**
 * The glyph for this kind.
 *
 * Groceries come from Material's core set, which ships a trolley; the rest are drawn in
 * [HomeFinanceIcons] because Material's core set ships none of them.
 */
internal val CategoryIconKind.imageVector: ImageVector
    get() = when (this) {
        CategoryIconKind.MILK -> HomeFinanceIcons.MilkBottle
        CategoryIconKind.GROCERIES -> Icons.Filled.ShoppingCart
        CategoryIconKind.INCOME -> HomeFinanceIcons.Banknote
        CategoryIconKind.BILLS -> HomeFinanceIcons.Bolt
        CategoryIconKind.OTHER -> HomeFinanceIcons.Receipt
    }

/**
 * The colour of the circle behind the glyph.
 *
 * These are the theme's own container tones rather than a fresh palette, so a row's mark belongs to
 * the same surface family as the rest of the app. Amber is the one addition: a bill is not an
 * error, and painting it in the error red would say something the household did not.
 */
@Composable
internal fun CategoryIconKind.containerColor(): Color = when (this) {
    CategoryIconKind.MILK -> MaterialTheme.colorScheme.tertiaryContainer
    CategoryIconKind.GROCERIES -> MaterialTheme.colorScheme.secondaryContainer
    CategoryIconKind.INCOME -> MaterialTheme.colorScheme.primaryContainer
    CategoryIconKind.BILLS -> UtilityContainer
    CategoryIconKind.OTHER -> MaterialTheme.colorScheme.surfaceVariant
}

/** The glyph colour that sits on [containerColor] at a readable contrast. */
@Composable
internal fun CategoryIconKind.onContainerColor(): Color = when (this) {
    CategoryIconKind.MILK -> MaterialTheme.colorScheme.onTertiaryContainer
    CategoryIconKind.GROCERIES -> MaterialTheme.colorScheme.onSecondaryContainer
    CategoryIconKind.INCOME -> MaterialTheme.colorScheme.onPrimaryContainer
    CategoryIconKind.BILLS -> OnUtilityContainer
    CategoryIconKind.OTHER -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun String.containsAny(words: Set<String>): Boolean = words.any { contains(it) }

/**
 * Stems rather than whole words, so "groceries", "grocery" and "grocers" all agree without listing
 * every inflection a household might type.
 */
private val MILK_WORDS = setOf("milk", "dairy", "curd", "doodh", "paneer", "butter", "ghee", "lassi")

private val GROCERY_WORDS = setOf(
    "grocer",
    "vegetab",
    "fruit",
    "ration",
    "kirana",
    "provision",
    "supermarket",
    "food",
    "snack",
    "meal",
    "kitchen",
    "restaurant",
)

private val BILL_WORDS = setOf(
    "electric",
    "power",
    "water",
    "gas",
    "bill",
    "recharge",
    "mobile",
    "phone",
    "internet",
    "broadband",
    "wifi",
    "dth",
    "cable",
    "utility",
    "insurance",
    "tax",
    "maintenance",
)
