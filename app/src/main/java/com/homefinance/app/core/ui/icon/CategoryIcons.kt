package com.homefinance.app.core.ui.icon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.homefinance.app.data.model.CategoryIconKey
import java.util.Locale

/**
 * The mark for a category: a Material glyph on a tinted square.
 *
 * ### Why Material's own glyphs, and why extended
 *
 * Every picture here is a `ImageVector` that ships with Material - nothing in the app draws a path
 * of its own any more. That is worth the one dependency it costs: the glyphs are drawn by the
 * people who draw all the other icons on the screen, so a mark sits beside a back arrow as part of
 * the same family rather than merely near it, and there is no hand-rolled 24x24 path to keep true at
 * every size and density.
 *
 * The cost is real and is worth naming: `material-icons-extended` is a large artifact, and the
 * project previously went out of its way to avoid it. It is accepted now because the alternative was
 * a second, home-made icon set - a maintenance burden and a visible style clash for a feature the
 * household looks at on every row. Only a dozen of its glyphs are referenced, and R8 strips the rest
 * from a shrinking release build. This is the one place the app spends on decoration.
 *
 * ### The two colours
 *
 * A mark is a glyph colour on a background colour, and both are fixed brand tones rather than theme
 * roles. The theme is light-only, so a fixed palette cannot end up unreadable, and it buys something
 * a theme role cannot: milk is *blue* on every screen and in every state, so the household
 * recognises a row before reading it. The tones are deliberately light backgrounds with a saturated,
 * darker glyph, which is what keeps every pair legible without checking contrast per pair.
 *
 * A category's key, not its name, chooses the pair - see [CategoryIconKey] for where a key comes
 * from. A key this build does not recognise, and every key from a build that added one since, falls
 * back to the neutral receipt rather than drawing nothing.
 */
fun getCategoryIconAndColors(iconKey: String): Triple<ImageVector, Color, Color> =
    when (iconKey.lowercase(Locale.ROOT)) {
        CategoryIconKey.MILK -> Triple(Icons.Filled.LocalDrink, Color(0xFF0284C7), Color(0xFFE0F2FE))
        CategoryIconKey.GROCERIES ->
            Triple(Icons.Filled.LocalGroceryStore, Color(0xFFDC2626), Color(0xFFFEE2E2))
        CategoryIconKey.SHOP_RENT, "rent", "store" ->
            Triple(Icons.Filled.Store, Color(0xFFEA580C), Color(0xFFFFEDD5))
        CategoryIconKey.HOME_RENT, "house", "home" ->
            Triple(Icons.Filled.Home, Color(0xFFEA580C), Color(0xFFFFEDD5))
        CategoryIconKey.SALARY, "pay", "income" ->
            Triple(Icons.Filled.Paid, Color(0xFF10B981), Color(0xFFDCFCE7))
        CategoryIconKey.ELECTRICITY, "power" ->
            Triple(Icons.Filled.Bolt, Color(0xFFD97706), Color(0xFFFEF3C7))
        CategoryIconKey.GAS, "cylinder", "lpg" ->
            Triple(Icons.Filled.LocalGasStation, Color(0xFFE11D48), Color(0xFFFFE4E6))
        CategoryIconKey.INTERNET, "wifi" ->
            Triple(Icons.Filled.Wifi, Color(0xFF2563EB), Color(0xFFDBEAFE))
        CategoryIconKey.VEGETABLES, "food" ->
            Triple(Icons.Filled.Restaurant, Color(0xFF65A30D), Color(0xFFECFCCB))
        CategoryIconKey.MEDICAL, "health" ->
            Triple(Icons.Filled.LocalHospital, Color(0xFFDB2777), Color(0xFFFCE7F3))
        CategoryIconKey.TRANSPORT, "car" ->
            Triple(Icons.Filled.DirectionsCar, Color(0xFF4F46E5), Color(0xFFE0E7FF))
        CategoryIconKey.INVESTMENTS, "savings" ->
            Triple(Icons.Filled.Savings, Color(0xFF059669), Color(0xFFD1FAE5))
        else -> Triple(
            Icons.AutoMirrored.Filled.ReceiptLong,
            Color(0xFF475569),
            Color(0xFFF1F5F9),
        )
    }

/**
 * How round the mark's corners are, as a share of its own size.
 *
 * A share rather than a fixed 14dp, so a 24dp mark in a details row and a 72dp mark on a details
 * header are the same shape at two sizes instead of a square and a circle. 0.29 is the ratio the
 * design was drawn at: 14dp on a 48dp mark.
 */
private const val CORNER_TO_SIZE_RATIO = 0.29f

/**
 * The rounded-square mark for a category: [iconKey]'s glyph on [iconKey]'s background.
 *
 * This is the only thing in the app that draws a category, so a mark cannot look like one picture in
 * the ledger and another in the picker. It is deliberately stateless and free of any database
 * knowledge - it takes a key and draws it - which is what lets the picker, the form, the details
 * screen and the ledger all use it.
 *
 * The glyph is decoration beside a name that is always drawn, so it carries no `contentDescription`:
 * a screen reader announcing "milk" before the category's own name would say the same word twice and
 * would say a guessed word the second time. The row's text is the description.
 *
 * @param iconKey the stored key, or [CategoryIconKey.OTHER] for "no particular picture". Unrecognised
 *   keys fall back to the neutral receipt.
 * @param size the width and height of the mark.
 * @param iconSize the glyph's size inside it.
 * @param customTint overrides the key's own glyph colour, for a caller that has a reason to recolour
 *   a mark without inventing a key for it. `null` means "use the key's".
 * @param customBackground as [customTint], for the background.
 */
@Composable
fun CategorySquircleIcon(
    iconKey: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 26.dp,
    cornerRadius: Dp = size * CORNER_TO_SIZE_RATIO,
    customTint: Color? = null,
    customBackground: Color? = null,
) {
    val (icon, defaultTint, defaultBackground) = getCategoryIconAndColors(iconKey)

    Box(
        modifier = modifier
            .size(size)
            .background(
                color = customBackground ?: defaultBackground,
                shape = RoundedCornerShape(cornerRadius),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = customTint ?: defaultTint,
            modifier = Modifier.size(iconSize),
        )
    }
}
