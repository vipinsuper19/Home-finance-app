package com.homefinance.app.feature.addtransaction

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.homefinance.app.data.model.TransactionType
import com.homefinance.app.feature.transactions.categoryIconKindFor
import com.homefinance.app.feature.transactions.containerColor
import com.homefinance.app.feature.transactions.imageVector
import com.homefinance.app.feature.transactions.onContainerColor

/**
 * Keeps the glyph proportional as the circle grows or shrinks, so a 44dp ledger mark and a 36dp form
 * mark look like the same picture at two sizes rather than two different ones.
 */
private const val GLYPH_TO_CIRCLE_RATIO = 0.5f

/**
 * The circle-and-glyph mark for a category, as the add form and the category picker draw it.
 *
 * ### Why it is here and not in the picker or the form
 *
 * Both screens show the same mark for the same category, and the ledger list shows it too. There is
 * exactly one rule for choosing it - [categoryIconKindFor], which reads the name the household wrote
 * and the direction of the money - so both screens call it rather than each keeping a copy of the
 * "milk looks like a bottle" decision. A mark is decoration beside a name that is always shown, so
 * being wrong costs a wrong picture and never a wrong number; that is the only reason deriving it
 * from a guess is acceptable at all.
 *
 * @param categoryName the stored name; the mark is derived from it, never stored.
 * @param type the direction of the money, which disambiguates an income category named after a person.
 * @param size the diameter of the circle. The form and the picker use the smaller of the two.
 */
@Composable
internal fun CategoryMark(
    categoryName: String,
    type: TransactionType,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    val kind = categoryIconKindFor(categoryName = categoryName, type = type)

    Surface(
        shape = CircleShape,
        color = kind.containerColor(),
        contentColor = kind.onContainerColor(),
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = kind.imageVector,
                contentDescription = null,
                modifier = Modifier.size(size * GLYPH_TO_CIRCLE_RATIO),
            )
        }
    }
}
