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
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Weigh-ins as dots, the trend as a line (Libra style). Tap to select a point.
 */
@Composable
fun TrendChart(
    points: List<TrendPoint>,
    fromMs: Long,
    toMs: Long,
    selected: TrendPoint?,
    onSelect: (TrendPoint?) -> Unit,
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
                        kotlin.math.abs(left + w * (it.m.timestampMs - fromMs) / span.toFloat() - tap.x)
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

        var lo = visible.minOf { min(it.m.weightKg, it.trend) }
        var hi = visible.maxOf { max(it.m.weightKg, it.trend) }
        lo = floor(lo * 2 - 1) / 2
        hi = ceil(hi * 2 + 1) / 2
        val step = when {
            hi - lo > 12 -> 2.0
            hi - lo > 5 -> 1.0
            else -> 0.5
        }
        val span = (toMs - fromMs).coerceAtLeast(1L).toFloat()
        fun x(ms: Long) = left + w * (ms - fromMs) / span
        fun y(kg: Double) = top + h * (1f - ((kg - lo) / (hi - lo)).toFloat())

        // horizontal grid + labels
        var g = ceil(lo / step) * step
        while (g <= hi) {
            val yy = y(g)
            drawLine(colors.outlineVariant, Offset(left, yy), Offset(size.width, yy), strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val t = measurer.measure(Format.num(g), labelStyle)
            drawText(t, topLeft = Offset(left - t.size.width - 6.dp.toPx(), yy - t.size.height / 2f))
            g += step
        }

        // date labels: start and end of the visible range
        val zone = ZoneId.systemDefault()
        listOf(fromMs, toMs).forEachIndexed { i, ms ->
            val t = measurer.measure(Format.shortDate(Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()), labelStyle)
            val xx = if (i == 0) left else size.width - t.size.width
            drawText(t, topLeft = Offset(xx, size.height - t.size.height))
        }

        // weigh-ins
        for (p in visible) {
            drawCircle(colors.primary.copy(alpha = 0.35f), radius = 3.dp.toPx(), center = Offset(x(p.m.timestampMs), y(p.m.weightKg)))
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
            drawCircle(colors.tertiary, radius = 5.dp.toPx(), center = Offset(xx, y(p.m.weightKg)))
            drawCircle(colors.surface, radius = 4.dp.toPx(), center = Offset(xx, y(p.trend)))
            drawCircle(colors.primary, radius = 4.dp.toPx(), center = Offset(xx, y(p.trend)), style = Stroke(2.dp.toPx()))
        }
    }
}
