package com.weighttrend.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
 * One metric over time: measurements as faint dots, the trend as a thin smooth
 * line with a soft gradient under it. [accent] colours the trend (green when
 * things go well); [goal] draws a dashed target line when it is in view.
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
    accent: Color = MaterialTheme.colorScheme.primary,
    goal: Double? = null,
) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = colors.onSurfaceVariant)
    val visible = points.filter { it.m.timestampMs in fromMs..toMs }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
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
        // include the goal only when it is close, so a far target does not flatten the curve
        val dataLo = visible.minOf { min(it.value, it.trend) }
        val dataHi = visible.maxOf { max(it.value, it.trend) }
        val goalInView = goal?.takeIf { it in (dataLo - 1.5)..(dataHi + 1.5) }
        val rawLo = min(dataLo, goalInView ?: dataLo)
        val rawHi = max(dataHi, goalInView ?: dataHi)
        val step = niceStep((rawHi - rawLo).coerceAtLeast(0.5))
        val lo = floor((rawLo - step / 2) / step) * step
        val hi = ceil((rawHi + step / 2) / step) * step
        val span = (toMs - fromMs).coerceAtLeast(1L).toFloat()
        fun x(ms: Long) = left + w * (ms - fromMs) / span
        fun y(v: Double) = top + h * (1f - ((v - lo) / (hi - lo)).toFloat())

        // horizontal grid (hairlines) + value labels
        val hairline = 1f
        val digits = if (step < 1.0) 1 else 0
        var g = lo
        while (g <= hi + 1e-9) {
            val yy = y(g)
            drawLine(colors.outlineVariant.copy(alpha = 0.45f), Offset(left, yy), Offset(size.width, yy), strokeWidth = hairline)
            val t = measurer.measure(Format.num(g, digits), labelStyle)
            drawText(t, topLeft = Offset(left - t.size.width - 6.dp.toPx(), yy - t.size.height / 2f))
            g += step
        }

        // date ticks along the time axis
        val maxTicks = (w / 56.dp.toPx()).toInt().coerceIn(2, 8)
        var lastLabelRight = Float.NEGATIVE_INFINITY
        for (tick in TimeAxis.ticks(fromMs, toMs, ZoneId.systemDefault(), maxTicks)) {
            val xx = x(tick.epochMs)
            val t = measurer.measure(tick.label, labelStyle)
            val lx = (xx - t.size.width / 2f).coerceIn(left, size.width - t.size.width)
            if (lx > lastLabelRight + 4.dp.toPx()) {
                drawText(t, topLeft = Offset(lx, size.height - t.size.height))
                lastLabelRight = lx + t.size.width
            }
        }

        // goal: thin dashed line with a small label
        goalInView?.let { gv ->
            val gy = y(gv)
            drawLine(
                accent.copy(alpha = 0.7f), Offset(left, gy), Offset(size.width, gy), strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
            )
            val t = measurer.measure("цель ${Format.num(gv, metric.digits)}", labelStyle.copy(color = accent))
            drawText(t, topLeft = Offset(size.width - t.size.width, gy - t.size.height - 2.dp.toPx()))
        }

        // measurements: small, faint
        val dot = 2.dp.toPx()
        for (p in visible) {
            drawCircle(accent.copy(alpha = 0.28f), radius = dot, center = Offset(x(p.m.timestampMs), y(p.value)))
        }

        // trend: smooth curve (horizontal tangents at the points — no overshoot)
        val pts = visible.map { Offset(x(it.m.timestampMs), y(it.trend)) }
        val path = Path()
        pts.forEachIndexed { i, o ->
            if (i == 0) path.moveTo(o.x, o.y)
            else {
                val prev = pts[i - 1]
                val mx = (prev.x + o.x) / 2f
                path.cubicTo(mx, prev.y, mx, o.y, o.x, o.y)
            }
        }
        if (pts.size > 1) {
            val area = Path().apply {
                addPath(path)
                lineTo(pts.last().x, top + h)
                lineTo(pts.first().x, top + h)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(accent.copy(alpha = 0.20f), accent.copy(alpha = 0f)), startY = top, endY = top + h))
        }
        drawPath(path, accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        // the latest trend value: a ringed dot
        pts.lastOrNull()?.let { o ->
            drawCircle(colors.surface, radius = 4.5.dp.toPx(), center = o)
            drawCircle(accent, radius = 4.5.dp.toPx(), center = o, style = Stroke(1.75.dp.toPx()))
        }

        // selection
        selected?.takeIf { it in visible }?.let { p ->
            val xx = x(p.m.timestampMs)
            drawLine(colors.onSurfaceVariant.copy(alpha = 0.5f), Offset(xx, top), Offset(xx, top + h), strokeWidth = hairline)
            drawCircle(accent.copy(alpha = 0.6f), radius = 3.5.dp.toPx(), center = Offset(xx, y(p.value)))
            drawCircle(colors.surface, radius = 4.5.dp.toPx(), center = Offset(xx, y(p.trend)))
            drawCircle(accent, radius = 4.5.dp.toPx(), center = Offset(xx, y(p.trend)), style = Stroke(1.75.dp.toPx()))
        }
    }
}

/** Grid step giving roughly 4–6 horizontal lines. */
private fun niceStep(range: Double): Double {
    val candidates = doubleArrayOf(0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0)
    return candidates.firstOrNull { range / it <= 6 } ?: 50.0
}
