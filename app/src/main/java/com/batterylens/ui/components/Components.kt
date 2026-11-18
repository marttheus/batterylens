package com.batterylens.ui.components

import android.content.pm.PackageManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
fun StatRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = valueColor)
    }
}

/** Two big numbers side by side, e.g. screen-on vs screen-off drain. */
@Composable
fun StatPair(leftLabel: String, leftValue: String, rightLabel: String, rightValue: String) {
    Row(Modifier.fillMaxWidth()) {
        BigStat(leftLabel, leftValue, Modifier.weight(1f))
        BigStat(rightLabel, rightValue, Modifier.weight(1f))
    }
}

@Composable
fun BigStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 270° arc gauge with a value in the middle. */
@Composable
fun ArcGauge(
    fraction: Float,
    color: Color,
    centerText: String,
    subText: String,
    modifier: Modifier = Modifier,
    size: Dp = 180.dp,
    marker: Float? = null,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val markerColor = MaterialTheme.colorScheme.onSurface
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(
                color, 135f, 270f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            marker?.let { m ->
                val angle = Math.toRadians((135 + 270 * m.coerceIn(0f, 1f)).toDouble())
                val r = arcSize.width / 2
                val c = Offset(this.size.width / 2, this.size.height / 2)
                val inner = Offset(c.x + (r - stroke) * kotlin.math.cos(angle).toFloat(), c.y + (r - stroke) * kotlin.math.sin(angle).toFloat())
                val outer = Offset(c.x + (r + stroke) * kotlin.math.cos(angle).toFloat(), c.y + (r + stroke) * kotlin.math.sin(angle).toFloat())
                drawLine(markerColor, inner, outer, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerText, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text(subText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

data class ChartPoint(val x: Long, val y: Float)

/** Lightweight line chart with min/max labels and a soft fill. Downsamples large series. */
@Composable
fun LineChart(
    points: List<ChartPoint>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 150.dp,
    format: (Float) -> String = { it.toInt().toString() },
    fixedMin: Float? = null,
    fixedMax: Float? = null,
) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val data = remember(points) {
        if (points.size <= 400) points else {
            val step = points.size / 400.0
            (0 until 400).map { points[(it * step).toInt()] } + points.last()
        }
    }
    if (data.size < 2) {
        Box(modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            Text("Dados insuficientes ainda", style = MaterialTheme.typography.bodySmall, color = labelColor)
        }
        return
    }
    Canvas(modifier.fillMaxWidth().height(height)) {
        val labelW = 48.dp.toPx()
        val w = size.width - labelW
        val h = size.height - 16.dp.toPx()
        val minX = data.first().x
        val maxX = data.last().x.coerceAtLeast(minX + 1)
        var minY = fixedMin ?: data.minOf { it.y }
        var maxY = fixedMax ?: data.maxOf { it.y }
        if (maxY - minY < 1e-3f) { minY -= 1f; maxY += 1f }
        fun px(p: ChartPoint) = Offset(
            labelW + (p.x - minX).toFloat() / (maxX - minX) * w,
            h - (p.y - minY) / (maxY - minY) * h,
        )
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
        for (i in 0..2) {
            val y = h * i / 2f
            drawLine(gridColor, Offset(labelW, y), Offset(size.width, y), 1f, pathEffect = dash)
            val v = maxY - (maxY - minY) * i / 2f
            drawText(measurer, format(v), Offset(0f, (y - 7.sp.toPx()).coerceAtLeast(0f)), TextStyle(fontSize = 10.sp, color = labelColor))
        }
        drawText(measurer, formatTime(minX), Offset(labelW, h + 2.dp.toPx()), TextStyle(fontSize = 10.sp, color = labelColor))
        val endLabel = measurer.measure(formatTime(maxX), TextStyle(fontSize = 10.sp))
        drawText(measurer, formatTime(maxX), Offset(size.width - endLabel.size.width, h + 2.dp.toPx()), TextStyle(fontSize = 10.sp, color = labelColor))

        val line = Path()
        data.forEachIndexed { i, p -> val o = px(p); if (i == 0) line.moveTo(o.x, o.y) else line.lineTo(o.x, o.y) }
        val fill = Path().apply {
            addPath(line)
            lineTo(px(data.last()).x, h)
            lineTo(px(data.first()).x, h)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), Color.Transparent), 0f, h))
        drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun AppIconLabel(packageName: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    val pm = LocalContext.current.packageManager
    val info = remember(packageName) {
        runCatching {
            val ai = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            pm.getApplicationLabel(ai).toString() to pm.getApplicationIcon(ai).toBitmap(96, 96).asImageBitmap()
        }.getOrNull()
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (info != null) Image(info.second, contentDescription = null, modifier = Modifier.size(32.dp))
        else Spacer(Modifier.size(32.dp))
        Spacer(Modifier.width(12.dp))
        Text(info?.first ?: packageName, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        trailing()
    }
}
