package com.homefinance.app.core.ui.icon

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.homefinance.app.data.model.CategoryIconKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which glyph and which two colours each key draws.
 *
 * These are the *only* assertions in the project about what a mark looks like, and they are here
 * rather than in a screenshot test because the decision being checked is a lookup, not a layout. What
 * a rendered mark then does at 24dp or 72dp is the composable's business.
 *
 * The most valuable test in this file is not any of the individual mappings: it is the one that
 * proves a key cannot be added to the vocabulary and quietly forgotten here, because a forgotten key
 * draws the neutral receipt - a picture that looks deliberate and is wrong.
 */
class CategoryIconsTest {

    @Test
    fun eachKeyDrawsTheGlyphItNames() {
        assertSameGlyph(Icons.Filled.LocalDrink, CategoryIconKey.MILK)
        assertSameGlyph(Icons.Filled.LocalGroceryStore, CategoryIconKey.GROCERIES)
        assertSameGlyph(Icons.Filled.Store, CategoryIconKey.SHOP_RENT)
        assertSameGlyph(Icons.Filled.Home, CategoryIconKey.HOME_RENT)
        assertSameGlyph(Icons.Filled.Paid, CategoryIconKey.SALARY)
        assertSameGlyph(Icons.Filled.Bolt, CategoryIconKey.ELECTRICITY)
        assertSameGlyph(Icons.Filled.LocalGasStation, CategoryIconKey.GAS)
        assertSameGlyph(Icons.Filled.Wifi, CategoryIconKey.INTERNET)
        assertSameGlyph(Icons.Filled.Restaurant, CategoryIconKey.VEGETABLES)
        assertSameGlyph(Icons.Filled.LocalHospital, CategoryIconKey.MEDICAL)
        assertSameGlyph(Icons.Filled.DirectionsCar, CategoryIconKey.TRANSPORT)
        assertSameGlyph(Icons.Filled.Savings, CategoryIconKey.INVESTMENTS)
        assertSameGlyph(Icons.AutoMirrored.Filled.ReceiptLong, CategoryIconKey.OTHER)
    }

    @Test
    fun everyKnownKeyHasItsOwnGlyphAndNoneFallsThroughToTheFallback() {
        // `else` is a real branch and a real risk: a key added to `CategoryIconKey` but missed in
        // the lookup would draw the neutral slip without any error anywhere. This is the test that
        // turns that silent mistake into a failure.
        val fallback = getCategoryIconAndColors(CategoryIconKey.OTHER).first

        val fellThrough = CategoryIconKey.KNOWN
            .filter { it != CategoryIconKey.OTHER }
            .filter { getCategoryIconAndColors(it).first === fallback }

        assertTrue(
            "these keys draw the fallback glyph instead of one of their own: $fellThrough",
            fellThrough.isEmpty(),
        )
    }

    @Test
    fun aKeyThisBuildDoesNotKnowDrawsTheNeutralSlip() {
        assertEquals(
            getCategoryIconAndColors(CategoryIconKey.OTHER),
            getCategoryIconAndColors("something_a_later_build_added"),
        )
    }

    @Test
    fun aKeysCaseDoesNotChangeItsPicture() {
        assertEquals(
            getCategoryIconAndColors(CategoryIconKey.MILK),
            getCategoryIconAndColors("MILK"),
        )
    }

    @Test
    fun theNamesTheMockupUsedAsAliasesStillResolve() {
        // The mapping accepts a few plain-word synonyms, so a key written by hand - or by a build
        // that used the shorter word - draws the same picture rather than the fallback.
        assertEquals(getCategoryIconAndColors(CategoryIconKey.MILK), getCategoryIconAndColors("milk"))
        assertEquals(getCategoryIconAndColors(CategoryIconKey.SHOP_RENT), getCategoryIconAndColors("rent"))
        assertEquals(getCategoryIconAndColors(CategoryIconKey.HOME_RENT), getCategoryIconAndColors("home"))
        assertEquals(getCategoryIconAndColors(CategoryIconKey.SALARY), getCategoryIconAndColors("pay"))
        assertEquals(getCategoryIconAndColors(CategoryIconKey.INTERNET), getCategoryIconAndColors("wifi"))
        assertEquals(getCategoryIconAndColors(CategoryIconKey.VEGETABLES), getCategoryIconAndColors("food"))
        assertEquals(getCategoryIconAndColors(CategoryIconKey.TRANSPORT), getCategoryIconAndColors("car"))
    }

    @Test
    fun theHouseAndTheShopAreDrawnAsTwoDifferentPlaces() {
        // They share a colour on purpose - one rent looks like another - so the glyph is the only
        // thing telling them apart, and it has to actually differ.
        val shop = getCategoryIconAndColors(CategoryIconKey.SHOP_RENT)
        val home = getCategoryIconAndColors(CategoryIconKey.HOME_RENT)

        assertEquals(shop.second, home.second)
        assertEquals(shop.third, home.third)
        assertTrue("shop rent and home rent draw the same glyph", shop.first !== home.first)
    }

    @Test
    fun aMarkCarriesAFixedTintAndBackgroundRatherThanAThemeRole() {
        // Spot-checked rather than asserted for all thirteen: what matters is that the palette is
        // the key's own and does not move with the theme, so a row is recognisable at a glance.
        val (_, milkTint, milkBackground) = getCategoryIconAndColors(CategoryIconKey.MILK)
        assertEquals(Color(0xFF0284C7), milkTint)
        assertEquals(Color(0xFFE0F2FE), milkBackground)

        val (_, salaryTint, salaryBackground) = getCategoryIconAndColors(CategoryIconKey.SALARY)
        assertEquals(Color(0xFF10B981), salaryTint)
        assertEquals(Color(0xFFDCFCE7), salaryBackground)
    }

    @Test
    fun everyKeyDrawsItsOwnGlyph() {
        // Two keys drawn with the same glyph would be two categories a household cannot tell apart
        // in a list. The mapping is allowed to repeat a *colour* - one rent looks like another - but
        // never a shape, and this is also what catches a key that quietly fell through to `else`.
        val repeated = CategoryIconKey.KNOWN
            .groupBy { getCategoryIconAndColors(it).first }
            .filterValues { it.size > 1 }
            .values
            .toList()

        assertTrue("these keys share a glyph: $repeated", repeated.isEmpty())
    }

    private fun assertSameGlyph(expected: ImageVector, iconKey: String) {
        assertEquals(
            "the mark for '$iconKey' is not the glyph the mapping names",
            expected,
            getCategoryIconAndColors(iconKey).first,
        )
    }
}
