package com.homefinance.app.feature.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.homefinance.app.R
import com.homefinance.app.core.util.CurrencyFormatter
import com.homefinance.app.core.util.DateFormatter

/**
 * The report's charts, drawn as geometry with Compose and labelled with Compose's own text.
 *
 * ### No charting library, and why that is the honest choice
 *
 * The app ships no charting dependency and this feature does not add one. What the design asks for is
 * three small pictures - grouped bars, a line, a ring - and a library that draws them would be a
 * megabyte of code, a second visual language, and a version line to keep in step with Compose, to
 * replace about two hundred lines of `drawLine` and `drawArc` that cannot go out of date.
 *
 * ### Money is never a `Float` here either
 *
 * Every value that arrives in this file is an exact `Long` count of paise, and it stays one: the
 * *only* division performed on a money value is the geometric one that turns it into a fraction of a
 * bar's height or a ring's sweep. Nothing is rounded, nothing is accumulated and nothing is read back
 * out - the figure the household reads is printed by [CurrencyFormatter] from the `Long` it started
 * as.
 *
 * ### Labels are text, not pixels
 *
 * Axis values, bucket names and the legend are `Text` composables rather than glyphs painted into the
 * canvas, so they are found by a screen reader, they scale with the household's font size, and a test
 * can assert them by name instead of comparing pictures.
 */

/**
 * One series of a chart: what it is called, and the colour it is drawn in.
 *
 * The colour is passed in rather than chosen here so an amount keeps the meaning it has everywhere
 * else in the app - income in the emerald the ledger and the Home cards use, an expense in the red -
 * and so that a second series in the same chart can never be given the same one by accident.
 */
@Immutable
internal data class ChartSeries(val label: String, val color: Color)

/**
 * One bar of a chart: the name under it, one value per series in the series' own order, and the
 * sentence a screen reader reads instead of the drawing.
 *
 * `label` is already a finished word - `1 Nov`, `Jun` - because working out what a bucket of days is
 * called in words is a translation, and translations belong where `stringResource` lives.
 *
 * `description` exists because a chart is the one thing on this screen a screen reader cannot read:
 * it is a picture of numbers. Each column therefore carries its own figures as a sentence, and the
 * chart publishes them together, so the information a sighted household reads off the bar heights is
 * available as words rather than lost.
 */
@Immutable
internal data class ChartColumn(
    val label: String,
    val values: List<Long>,
    val description: String = "",
)

/** One plotted point of a line chart, named by its own label. */
@Immutable
internal data class ChartPoint(val label: String, val value: Long)

/** One wedge of the ring, named and coloured by the category it stands for. */
@Immutable
internal data class DonutSlice(val label: String, val value: Long, val color: Color)

/**
 * The smallest "round" number at or above [maxValue]: one of 1, 2, 5, 10, 20, 50, 100, ...
 *
 * A chart's value axis has to be labelled in numbers a person can hold in their head, so the scale is
 * rounded *up* to the next round figure rather than taken to be the largest bar. Rounding up rather
 * than to nearest is deliberate: a bar that reached exactly the top gridline would be
 * indistinguishable from one that was taller, and the household would be reading a clipped chart
 * without being told.
 *
 * The search walks one [DECADE] at a time and tries 1, 2 and 5 within it, which is why `20` and `100`
 * are reachable and a figure such as `25` never is. The scale the axis is then divided into stays
 * readable in [CurrencyFormatter.formatCompactInr]'s short form: `₹20,000` splits into `₹20K`, `₹15K`,
 * `₹10K`, `₹5K` and zero, where a scale of `₹25,000` would produce `₹25K`, `₹18K`, `₹12K`, `₹6K`.
 *
 * `0` and every negative value answer `1`, because a chart with nothing in it still needs a scale to
 * divide by, and no bar is ever drawn from a negative value.
 */
internal fun niceAxisMax(maxValue: Long): Long {
    if (maxValue <= 0L) return 1L

    var magnitude = 1L
    while (true) {
        for (factor in NICE_FACTORS) {
            val candidate = multiplyOrNull(factor, magnitude) ?: return maxValue
            if (candidate >= maxValue) return candidate
        }
        magnitude = multiplyOrNull(DECADE, magnitude) ?: return maxValue
    }
}

/**
 * The labelled values of the value axis, from the top down.
 *
 * Five of them - the top, the bottom, and three between - because that is as many numbers as fit
 * beside a phone-sized chart without the labels crowding each other, and because a gridline every
 * quarter is fine enough to read a bar's height against without counting pixels.
 */
internal fun axisTicks(axisMax: Long, axisMin: Long, count: Int = AXIS_TICK_COUNT): List<Long> {
    val span = axisMax - axisMin
    val steps = (count - 1).coerceAtLeast(1)

    return (0 until count).map { index -> axisMax - (span * index) / steps }
}

/** `left * right`, or `null` when the product would not fit in a `Long`. */
private fun multiplyOrNull(left: Long, right: Long): Long? =
    if (left != 0L && right > Long.MAX_VALUE / left) null else left * right

/**
 * Bars for the period, one group per bucket and one bar per series.
 *
 * @param columns the buckets, in the order they are drawn, each carrying one value per series.
 * @param series the series, in the order their bars appear inside a group.
 */
@Composable
internal fun GroupedBarChart(
    columns: List<ChartColumn>,
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    height: Dp = CHART_HEIGHT,
) {
    BarChart(
        columns = columns,
        series = series,
        netValues = null,
        netLabel = null,
        modifier = modifier,
        height = height,
    )
}

/**
 * The recent-months chart: income and expense as bars, with each month's net cash flow drawn over
 * them as a line.
 *
 * The bars answer "what came in and what went out", and the line answers "which way did the month
 * actually go" - which is the one thing the two bars alone cannot say, because net is their
 * difference and a household reading two heights has to do the subtraction itself.
 *
 * The line shares the bars' value axis, and the axis extends *below zero* when any month's net is
 * negative, so a month that went backwards is drawn going backwards rather than being clamped to the
 * baseline and read as a month that broke even.
 *
 * @param points one entry per month, oldest first. Months with nothing in them are present and worth
 *   zero, so a quiet month is drawn as a month that stood still.
 */
@Composable
internal fun NetTrendChart(
    points: List<TrendPoint>,
    modifier: Modifier = Modifier,
    height: Dp = CHART_HEIGHT,
) {
    val columns = points.map { point ->
        ChartColumn(
            label = DateFormatter.formatShortMonth(point.month.atDay(1)),
            values = listOf(point.incomePaise, point.expensePaise),
            description = stringResource(
                R.string.reports_chart_column,
                DateFormatter.formatMonth(point.month),
                CurrencyFormatter.formatInr(point.incomePaise),
                CurrencyFormatter.formatInr(point.expensePaise),
            ),
        )
    }
    val series = listOf(
        ChartSeries(
            label = stringResource(R.string.reports_series_income),
            color = MaterialTheme.colorScheme.primary,
        ),
        ChartSeries(
            label = stringResource(R.string.reports_series_expense),
            color = MaterialTheme.colorScheme.error,
        ),
    )

    BarChart(
        columns = columns,
        series = series,
        netValues = points.map { it.netPaise },
        netLabel = stringResource(R.string.reports_series_net),
        modifier = modifier,
        height = height,
    )
}

/**
 * One series drawn as a line with the area beneath it filled - the spending trend of one category.
 *
 * @param points the buckets, in the order they are drawn, each with the value for that bucket.
 * @param description the sentence a screen reader reads instead of the line, since a line chart is a
 *   picture of numbers like every other chart here.
 */
@Composable
internal fun TimeLineChart(
    points: List<ChartPoint>,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = CHART_HEIGHT,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val axisMax = niceAxisMax(points.maxOfOrNull { it.value } ?: 0L)
    val ticks = axisTicks(axisMax = axisMax, axisMin = 0L)
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Column(modifier = modifier) {
        ChartBody(
            ticks = ticks,
            labels = points.map { it.label },
            height = height,
            description = description,
        ) { plot ->
            drawGridlines(ticks.size, gridColor)

            if (points.isEmpty()) return@ChartBody

            val step = plot.width / points.size

            fun xOf(index: Int): Float = index * step + step / 2f
            fun yOf(value: Long): Float = plot.yOf(value)

            val area = Path().apply {
                moveTo(xOf(0), plot.baseY)
                points.forEachIndexed { index, point -> lineTo(xOf(index), yOf(point.value)) }
                lineTo(xOf(points.lastIndex), plot.baseY)
                close()
            }
            drawPath(area, lineColor.copy(alpha = AREA_ALPHA))

            val line = Path().apply {
                points.forEachIndexed { index, point ->
                    if (index == 0) moveTo(xOf(index), yOf(point.value)) else lineTo(xOf(index), yOf(point.value))
                }
            }
            drawPath(line, lineColor, style = Stroke(width = LINE_WIDTH.toPx(), cap = StrokeCap.Round))

            points.forEachIndexed { index, point ->
                drawCircle(lineColor, radius = POINT_RADIUS.toPx(), center = Offset(xOf(index), yOf(point.value)))
            }
        }
    }
}

/**
 * The ring a breakdown is drawn as, with the total inside it.
 *
 * A ring rather than a stack of bars because the question a breakdown answers is "what share of the
 * whole is this?", and a ring is the shape that question is asked in. Each wedge is drawn in its own
 * category's colour - the same tone as that category's mark, taken from the one palette that decides
 * it - so milk is the same blue in the ring as it is in the ledger.
 *
 * The wedges are separated by a hairline gap so two adjacent categories of similar size cannot read
 * as one, and the legend beside the ring is what actually names them: colour is decoration over a
 * number that is always printed.
 */
@Composable
internal fun CategoryDonutChart(
    slices: List<DonutSlice>,
    centreValue: String,
    centreLabel: String,
    modifier: Modifier = Modifier,
    size: Dp = DONUT_SIZE,
    strokeWidth: Dp = DONUT_STROKE,
) {
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxWidth().height(size)) {
            val total = slices.sumOf { it.value }
            if (total <= 0L) return@Canvas

            val diameter = size.toPx() - strokeWidth.toPx()
            val topLeft = Offset(
                x = (size.toPx() - diameter) / 2f,
                y = (size.toPx() - diameter) / 2f,
            )
            val arcSize = Size(diameter, diameter)

            var startAngle = START_ANGLE
            slices.forEach { slice ->
                // The one division a money value takes part in: a share of the ring. The value itself
                // is untouched - it is still the exact `Long` the database summed.
                val sweep = FULL_CIRCLE * slice.value.toFloat() / total.toFloat()

                drawArc(
                    color = slice.color,
                    startAngle = startAngle,
                    sweepAngle = (sweep - WEDGE_GAP_DEGREES).coerceAtLeast(0f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Butt),
                )
                startAngle += sweep
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = centreValue,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = centreLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ------------------------------------------------------------------------------ shared drawing

/** Where the plot sits inside its canvas, as a fraction of the canvas height. */
private const val PLOT_INSET = 0.06f

/** How much of a wedge's sweep is given up to the gap between wedges, in degrees. */
private const val WEDGE_GAP_DEGREES = 1.5f

private const val FULL_CIRCLE = 360f
private const val START_ANGLE = -90f
private const val AREA_ALPHA = 0.12f

private val NICE_FACTORS = longArrayOf(1L, 2L, 5L)
private const val AXIS_TICK_COUNT = 5

/** The step [niceAxisMax] walks the magnitude by: 1, 2 and 5 within each power of ten. */
private const val DECADE = 10L

private val CHART_HEIGHT = 148.dp
private val DONUT_SIZE = 132.dp
private val DONUT_STROKE = 26.dp
private val BAR_MAX_WIDTH = 16.dp
private val BAR_GAP = 2.dp
private val LINE_WIDTH = 2.dp
private val POINT_RADIUS = 3.dp
private val LABEL_SPACING = 6.dp

/**
 * The geometry of one plot area, worked out once so every chart puts a value on screen the same way.
 *
 * `yOf` is the only place a value becomes a pixel, and it is the same function for a bar's top, a
 * line's point and a gridline - which is what makes a bar and a line in the same chart genuinely
 * comparable rather than merely adjacent.
 */
private data class PlotGeometry(val width: Float, val top: Float, val baseY: Float, val axisMin: Long, val axisMax: Long) {

    private val span: Long get() = (axisMax - axisMin).coerceAtLeast(1L)

    fun yOf(value: Long): Float {
        val fraction = (value - axisMin).toFloat() / span.toFloat()
        return baseY - fraction * (baseY - top)
    }
}

/** Draws the five gridlines of a plot, evenly spaced between its top and its baseline. */
private fun DrawScope.drawGridlines(tickCount: Int, color: Color) {
    val geometry = plotGeometry(axisMin = 0L, axisMax = 1L)
    val steps = (tickCount - 1).coerceAtLeast(1)

    repeat(tickCount) { index ->
        val y = geometry.top + (geometry.baseY - geometry.top) * index / steps
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
    }
}

/**
 * The plot area of a chart: the value labels down the left, the drawing surface, and the bucket
 * labels underneath.
 *
 * The value labels and the gridlines are positioned from the same five fractions, so the number
 * `₹20K` sits on the line the bars are measured against. That is the whole reason this is a shared
 * helper rather than a few lines inside each chart: an axis that is one label out is a chart that
 * lies about every bar on it.
 */
@Composable
private fun ChartBody(
    ticks: List<Long>,
    labels: List<String>,
    height: Dp,
    description: String,
    modifier: Modifier = Modifier,
    draw: DrawScope.(PlotGeometry) -> Unit,
) {
    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.height(height),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End,
            ) {
                ticks.forEach { tick ->
                    Text(
                        text = CurrencyFormatter.formatCompactInr(tick),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            Spacer(modifier = Modifier.width(LABEL_SPACING))

            Column(modifier = Modifier.weight(1f)) {
                val chartDescription = description
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(height)
                        .semantics { contentDescription = chartDescription },
                ) {
                    draw(plotGeometry(axisMin = ticks.last(), axisMax = ticks.first()))
                }

                Spacer(modifier = Modifier.height(LABEL_SPACING))

                Row(modifier = Modifier.fillMaxWidth()) {
                    labels.forEach { label ->
                        Text(
                            text = label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** The canvas whose geometry every chart draws into. */
private fun DrawScope.plotGeometry(axisMin: Long, axisMax: Long): PlotGeometry = PlotGeometry(
    width = size.width,
    top = size.height * PLOT_INSET,
    baseY = size.height * (1f - PLOT_INSET),
    axisMin = axisMin,
    axisMax = axisMax,
)

/**
 * Bars and, optionally, the net line over them - the one implementation behind every bar chart.
 *
 * @param netValues one net figure per bucket, or `null` for a chart that draws bars only.
 */
@Composable
private fun BarChart(
    columns: List<ChartColumn>,
    series: List<ChartSeries>,
    netValues: List<Long>?,
    netLabel: String?,
    modifier: Modifier = Modifier,
    height: Dp = CHART_HEIGHT,
) {
    val highest = maxOf(
        columns.maxOfOrNull { column -> column.values.maxOrNull() ?: 0L } ?: 0L,
        netValues?.maxOrNull() ?: 0L,
    )
    val lowest = netValues?.minOrNull() ?: 0L

    val axisMax = niceAxisMax(highest)
    val axisMin = if (lowest < 0L) -niceAxisMax(-lowest) else 0L
    val ticks = axisTicks(axisMax = axisMax, axisMin = axisMin)

    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val netColor = MaterialTheme.colorScheme.tertiary
    val barColors = series.map { it.color }

    Column(modifier = modifier) {
        ChartLegend(
            series = series,
            netLabel = netLabel?.takeIf { netValues != null },
            netColor = netColor,
        )

        ChartBody(
            ticks = ticks,
            labels = columns.map { it.label },
            height = height,
            description = columns.joinToString(separator = "; ") { it.description },
        ) { plot ->
            drawGridlines(ticks.size, gridColor)

            if (columns.isEmpty()) return@ChartBody

            val columnWidth = plot.width / columns.size
            val barWidth = minOf(BAR_MAX_WIDTH.toPx(), columnWidth * 0.34f)
            val gap = if (barColors.size > 1) BAR_GAP.toPx() else 0f
            val groupWidth = barColors.size * barWidth + (barColors.size - 1) * gap
            val zeroY = plot.yOf(0L)

            columns.forEachIndexed { index, column ->
                val groupStart = index * columnWidth + (columnWidth - groupWidth) / 2f

                column.values.forEachIndexed { seriesIndex, value ->
                    val top = plot.yOf(value)
                    val bottom = maxOf(top, zeroY)
                    val barHeight = (bottom - minOf(top, zeroY)).coerceAtLeast(0f)

                    drawRoundRect(
                        color = barColors[seriesIndex],
                        topLeft = Offset(groupStart + seriesIndex * (barWidth + gap), bottom - barHeight),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(minOf(barWidth / 2f, barHeight / 2f)),
                    )
                }
            }

            if (netValues != null && netValues.size == columns.size) {
                drawLineSeries(
                    values = netValues,
                    color = netColor,
                    xOf = { index -> index * columnWidth + columnWidth / 2f },
                    yOf = { value -> plot.yOf(value) },
                )
            }
        }
    }
}

/** A line through one value per bucket, with a dot on each, for the chart's net series. */
private fun DrawScope.drawLineSeries(
    values: List<Long>,
    color: Color,
    xOf: (Int) -> Float,
    yOf: (Long) -> Float,
) {
    if (values.isEmpty()) return

    val line = Path().apply {
        values.forEachIndexed { index, value ->
            if (index == 0) moveTo(xOf(index), yOf(value)) else lineTo(xOf(index), yOf(value))
        }
    }

    drawPath(line, color, style = Stroke(width = LINE_WIDTH.toPx(), cap = StrokeCap.Round))
    values.forEachIndexed { index, value ->
        drawCircle(color, radius = POINT_RADIUS.toPx(), center = Offset(xOf(index), yOf(value)))
    }
}

/**
 * The key above a chart: a dot and a name for each series.
 *
 * Present even when there is only one series, because a line alone does not say what it is measuring -
 * and the words are what carry the meaning, with the colour only agreeing with them.
 */
@Composable
private fun ChartLegend(
    series: List<ChartSeries>,
    netLabel: String?,
    netColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(bottom = LABEL_SPACING),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        series.forEach { item -> LegendEntry(label = item.label, color = item.color) }
        if (netLabel != null) LegendEntry(label = netLabel, color = netColor)
    }
}

/** One dot-and-name pair of a legend. */
@Composable
private fun LegendEntry(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color = color, shape = CircleShape),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
