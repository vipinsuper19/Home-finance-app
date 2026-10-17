package com.homefinance.app.core.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The handful of glyphs the transaction screens need that Material's icon *core* set does not ship.
 *
 * ### Why these are drawn here rather than depended upon
 *
 * The list marks each row with the shape of the thing it records - a milk bottle, a note of
 * money, a lightning bolt for a bill. Material provides those in `material-icons-extended`, which
 * this project deliberately does not depend on: it is a multi-megabyte artifact added for a
 * decoration, and adding it would mean introducing a dependency and a version the approved
 * manifest never had.
 *
 * Where Material's *core* set already has the right glyph it is used as-is (the trolley for
 * groceries is `Icons.Filled.ShoppingCart`); only the six shapes it lacks are drawn here. All of
 * them are solid-filled on a 24x24 viewport with the same visual weight, so a mark looks the same
 * whether it came from the platform or from this file.
 *
 * Each vector is a pure `val`: it is built once, never mutated, and safe to share across
 * compositions.
 */
object HomeFinanceIcons {

    /**
     * A milk bottle: a narrow cap sitting on a rounded body.
     *
     * The cap is drawn as a plain rectangle that overlaps the body's top edge, so the two collapse
     * into a single silhouette rather than showing a seam.
     */
    val MilkBottle: ImageVector = icon("MilkBottle") {
        path(fill = SolidColor(Color.Black)) {
            // Cap.
            moveTo(9f, 2f)
            lineTo(15f, 2f)
            lineTo(15f, 5.5f)
            lineTo(9f, 5.5f)
            close()

            // Body, with its corners rounded by a quadratic at each turn.
            moveTo(8.5f, 5.5f)
            lineTo(15.5f, 5.5f)
            quadTo(17f, 5.5f, 17f, 7f)
            lineTo(17f, 19.5f)
            quadTo(17f, 21f, 15.5f, 21f)
            lineTo(8.5f, 21f)
            quadTo(7f, 21f, 7f, 19.5f)
            lineTo(7f, 7f)
            quadTo(7f, 5.5f, 8.5f, 5.5f)
            close()
        }
    }

    /**
     * A banknote: a wide rectangle with a small circle punched out of its middle.
     *
     * The proportions are what make it read as a note rather than as a camera: twice as wide as it
     * is tall, with a hole only a third of its height. The circle is a second sub-path drawn with
     * the even-odd rule, which is what turns it into a hole instead of an invisible shape filled in
     * the same colour as its surround.
     */
    val Banknote: ImageVector = icon("Banknote") {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(3f, 7f)
            lineTo(21f, 7f)
            quadTo(22.5f, 7f, 22.5f, 8.5f)
            lineTo(22.5f, 15.5f)
            quadTo(22.5f, 17f, 21f, 17f)
            lineTo(3f, 17f)
            quadTo(1.5f, 17f, 1.5f, 15.5f)
            lineTo(1.5f, 8.5f)
            quadTo(1.5f, 7f, 3f, 7f)
            close()

            // Two half-arcs sweeping the same way complete a circle.
            moveTo(12f, 10.2f)
            arcTo(1.8f, 1.8f, 0f, false, true, 12f, 13.8f)
            arcTo(1.8f, 1.8f, 0f, false, true, 12f, 10.2f)
            close()
        }
    }

    /** A lightning bolt, for electricity and the bills that behave like it. */
    val Bolt: ImageVector = icon("Bolt") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(13.5f, 2f)
            lineTo(5f, 13.5f)
            lineTo(10.5f, 13.5f)
            lineTo(10f, 22f)
            lineTo(19f, 10.5f)
            lineTo(13.5f, 10.5f)
            close()
        }
    }

    /**
     * A receipt: a straight-topped slip with a torn edge along the bottom.
     *
     * This is the fallback mark, drawn for any category whose name says nothing about what it
     * holds. A serrated edge is used rather than plain inner lines because lines would need their
     * own holes to be visible, and nine sides read as "a slip of paper" at 22dp where ruled lines
     * would blur into a solid block.
     */
    val Receipt: ImageVector = icon("Receipt") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(5.5f, 2.5f)
            lineTo(18.5f, 2.5f)
            lineTo(18.5f, 21.5f)
            lineTo(16.875f, 20.2f)
            lineTo(15.25f, 21.5f)
            lineTo(13.625f, 20.2f)
            lineTo(12f, 21.5f)
            lineTo(10.375f, 20.2f)
            lineTo(8.75f, 21.5f)
            lineTo(7.125f, 20.2f)
            lineTo(5.5f, 21.5f)
            close()
        }
    }

    /**
     * Money going out: an arrow pointing down, to the ledger's floor.
     *
     * The transaction details screen labels the direction of the movement with this arrow as well
     * as with colour. Colour alone is the one cue a household with a colour vision deficiency
     * cannot read, and "which way did this go" is not a detail they should have to guess - so the
     * direction is also a shape, and the shape is the same one in both themes.
     *
     * Drawn as a single silhouette - a stem with a triangular head wider than the stem - so it still
     * reads as an arrow at the 18dp the details rows draw it at. [ArrowUp] is its exact mirror: the
     * two are one shape rotated, not two drawings that could drift apart.
     */
    val ArrowDown: ImageVector = icon("ArrowDown") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(10.75f, 2.5f)
            lineTo(13.25f, 2.5f)
            lineTo(13.25f, 12.75f)
            lineTo(18.5f, 12.75f)
            lineTo(12f, 21.5f)
            lineTo(5.5f, 12.75f)
            lineTo(10.75f, 12.75f)
            close()
        }
    }

    /** Money coming in: the same arrow pointing up. See [ArrowDown] for why it is drawn at all. */
    val ArrowUp: ImageVector = icon("ArrowUp") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(10.75f, 21.5f)
            lineTo(13.25f, 21.5f)
            lineTo(13.25f, 11.25f)
            lineTo(18.5f, 11.25f)
            lineTo(12f, 2.5f)
            lineTo(5.5f, 11.25f)
            lineTo(10.75f, 11.25f)
            close()
        }
    }
}

/**
 * Builds a filled 24x24 vector.
 *
 * `defaultWidth`/`defaultHeight` are the intrinsic size, not the drawn size: the callers scale
 * these to the row's circle with `Modifier.size`, as they do for Material's own icons.
 *
 * [fill] is set to black on every path even though no glyph renders black - `Icon` recolours the
 * vector with its `tint`, so the stored colour is only a placeholder that keeps the vector
 * non-transparent if it is ever drawn without a tint.
 */
private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(block).build()
