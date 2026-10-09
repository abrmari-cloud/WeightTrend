package com.weighttrend.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weighttrend.core.Metric
import com.weighttrend.core.TimeAxis
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * One metric over time: measurements as dots, the trend as a line (Libra style).
 * Tap to select the nearest point.
 */
@Composable
fun TrendChart(
    points: List<Metric.Point>,
    metric: Metric,
    fromMs: Long,
    toMs: Long,
    selected: Metric.Point?,
    onSelect: (Metric.Point?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = colors.onSurfaceVariant)
    val visible = points.filter { it.m.timestampMs in fromMs..toMs }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp)
            .pointerInput(visible, fromMs, toMs) {
                detectTapGestures { tap ->
                    if (visible.isEmpty()) return@detectTapGestures
                    val left = 40.dp.toPx()
                    val w = size.width - left
                    val span = (toMs - fromMs).coerceAtLeast(1L)
                    val nearest = visible.minByOrNull {
                        abs(left + w * (it.m.timestampMs - fromMs) / span.toFloat() - tap.x)
                    }
                    onSelect(if (nearest == selected) null else nearest)
                }
            }
    ) {
        if (visible.isEmpty()) {
            val t = measurer.measure("Нет данных за период", labelStyle)
            drawText(t, topLeft = Offset((size.width - t.size.width) / 2f, (size.height - t.size.height) / 2f))
            return@Canvas
        }
        val left = 40.dp.toPx()
        val bottom = 22.dp.toPx()
        val top = 8.dp.toPx()
        val w = size.width - left
        val h = size.height - bottom - top

        // y range with a little padding, snapped to the grid step
        val rawLo = visible.minOf { min(it.value, it.trend) }
        val rawHi = visible.maxOf { max(it.value, it.trend) }
        val step = niceStep((rawHi - rawLo).coerceAtLeast(0.5))
        val lo = floor((rawLo - step / 2) / step) * step
        val hi = ceil((rawHi + step / 2) / step) * step
        val span = (toMs - fromMs).coerceAtLeast(1L).toFloat()
        fun x(ms: Long) = left + w * (ms - fromMs) / span
        fun y(v: Double) = top + h * (1f - ((v - lo) / (hi - lo)).toFloat())

        // horizontal grid + value labels
        val gridEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
        val digits = if (step < 1.0) 1 else 0
        var g = lo
        while (g <= hi + 1e-9) {
            val yy = y(g)
            drawLine(colors.outlineVariant, Offset(left, yy), Offset(size.width, yy), strokeWidth = 1f, pathEffect = gridEffect)
            val t = measurer.measure(Format.num(g, digits), labelStyle)
            drawText(t, topLeft = Offset(left - t.size.width - 6.dp.toPx(), yy - t.size.height / 2f))
            g += step
        }

        // date ticks along the time axis
        val maxTicks = (w / 56.dp.toPx()).toInt().coerceIn(2, 8)
        var lastLabelRight = Float.NEGATIVE_INFINITY
        for (tick in TimeAxis.ticks(fromMs, toMs, ZoneId.systemDefault(), maxTicks)) {
            val xx = x(tick.epochMs)
            drawLine(colors.outlineVariant.copy(alpha = 0.5f), Offset(xx, top), Offset(xx, top + h), strokeWidth = 1f)
            val t = measurer.measure(tick.label, labelStyle)
            val lx = (xx - t.size.width / 2f).coerceIn(left, size.width - t.size.width)
            if (lx > lastLabelRight + 4.dp.toPx()) {
                drawText(t, topLeft = Offset(lx, size.height - t.size.height))
                lastLabelRight = lx + t.size.width
            }
        }

        // measurements
        for (p in visible) {
            drawCircle(colors.primary.copy(alpha = 0.35f), radius = 3.dp.toPx(), center = Offset(x(p.m.timestampMs), y(p.value)))
        }

        // trend line
        val path = Path()
        visible.forEachIndexed { i, p ->
            val o = Offset(x(p.m.timestampMs), y(p.trend))
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, colors.primary, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        // selection
        selected?.takeIf { it in visible }?.let { p ->
            val xx = x(p.m.timestampMs)
            drawLine(colors.tertiary, Offset(xx, top), Offset(xx, top + h), strokeWidth = 1.5f)
            drawCircle(colors.tertiary, radius = 5.dp.toPx(), center = Offset(xx, y(p.value)))
            drawCircle(colors.surface, radius = 4.dp.toPx(), center = Offset(xx, y(p.trend)))
            drawCircle(colors.primary, radius = 4.dp.toPx(), center = Offset(xx, y(p.trend)), style = Stroke(2.dp.toPx()))
        }
    }
}

/** Grid step giving roughly 4–6 horizontal lines. */
private fun niceStep(range: Double): Double {
    val candidates = doubleArrayOf(0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0)
    return candidates.firstOrNull { range / it <= 6 } ?: 50.0
}
