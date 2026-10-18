package com.homefinance.app.feature.addtransaction

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.homefinance.app.core.ui.icon.CategorySquircleIcon
import com.homefinance.app.data.model.TransactionType
import com.homefinance.app.data.model.categoryIconKeyFor

/**
 * Keeps the glyph proportional as the mark grows or shrinks, so a 44dp ledger mark and a 36dp form
 * mark look like the same picture at two sizes rather than two different ones.
 */
private const val GLYPH_TO_MARK_RATIO = 0.5f

/**
 * The mark for a category, resolved from its stored key and drawn by [CategorySquircleIcon].
 *
 * ### Why this layer exists at all
 *
 * The ledger, the picker, the form, the details screen and the recurring screens all draw the same
 * mark for the same category, and they do not all hold the same thing: the ledger has a row built
 * from a join, the picker has a form option. What they share is a name, a direction and - where the
 * read carried it - a stored key. Turning those three into a key is the one rule, so it lives here
 * rather than in five screens: [categoryIconKeyFor] prefers the stored key and falls back to a guess
 * from the name, which is what a category the household wrote for itself gets.
 *
 * A mark is decoration beside a name that is always shown, so being wrong costs a wrong picture and
 * never a wrong number; that is the only reason deriving it from a guess is acceptable at all.
 *
 * @param iconKey the category's stored mark, or `null` when nobody ever chose one - which is every
 *   category the household adds through the picker, and every category written before marks were
 *   stored. `null` means "guess from [categoryName]", never "no picture".
 * @param categoryName the stored name, used only when [iconKey] is missing or unrecognisable.
 * @param type the direction of the money, which disambiguates an income category named after a person.
 * @param size the width and height of the mark. The ledger uses the larger of the two sizes.
 */
@Composable
internal fun CategoryMark(
    iconKey: String?,
    categoryName: String,
    type: TransactionType,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    CategorySquircleIcon(
        iconKey = categoryIconKeyFor(
            categoryName = categoryName,
            type = type,
            storedIconKey = iconKey,
        ),
        modifier = modifier,
        size = size,
        iconSize = size * GLYPH_TO_MARK_RATIO,
    )
}
