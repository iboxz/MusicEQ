package com.boxeq.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boxeq.dsp.BandMapping
import com.boxeq.dsp.ResponseCurve

private const val RANGE_DB = 15f
private val GRID_FREQS = listOf(100f, 1000f, 10000f)
private val GRID_LABELS = listOf("100", "1k", "10k")
private val GRID_DB = listOf(-12f, -6f, 0f, 6f, 12f)

/** Approximate frequency response of the current band gains (log frequency axis, ±15 dB). */
@Composable
fun ResponseCurveView(gains: List<Float>, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val zero = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 9.sp, color = labelColor)

    Canvas(modifier = modifier.fillMaxWidth().height(150.dp)) {
        val left = 30.dp.toPx()
        val bottom = size.height - 14.dp.toPx()
        val width = size.width - left - 4.dp.toPx()
        val height = bottom - 4.dp.toPx()
        fun x(freq: Float) = left + ResponseCurve.logPosition(freq) * width
        fun y(db: Float) = 4.dp.toPx() + (1f - (db + RANGE_DB) / (2 * RANGE_DB)) * height

        GRID_DB.forEach { db ->
            drawLine(
                if (db == 0f) zero else grid, Offset(left, y(db)), Offset(left + width, y(db)),
                strokeWidth = if (db == 0f) 1.5.dp.toPx() else 1.dp.toPx(),
            )
            val text = if (db > 0) "+${db.toInt()}" else db.toInt().toString()
            drawText(measurer, text, Offset(2.dp.toPx(), y(db) - 6.dp.toPx()), labelStyle)
        }
        GRID_FREQS.forEachIndexed { i, f ->
            drawLine(grid, Offset(x(f), 4.dp.toPx()), Offset(x(f), bottom), strokeWidth = 1.dp.toPx())
            drawText(measurer, GRID_LABELS[i], Offset(x(f) - 8.dp.toPx(), bottom + 1.dp.toPx()), labelStyle)
        }

        val points = 160
        val samples = ResponseCurve.sample(gains, points)
        val stroke = Path()
        val fill = Path()
        samples.forEachIndexed { k, db ->
            val px = left + width * k / (points - 1)
            val py = y(db)
            if (k == 0) {
                stroke.moveTo(px, py)
                fill.moveTo(px, y(0f))
                fill.lineTo(px, py)
            } else {
                stroke.lineTo(px, py)
                fill.lineTo(px, py)
            }
        }
        fill.lineTo(left + width, y(0f))
        fill.close()
        drawPath(fill, line.copy(alpha = 0.18f))
        drawPath(stroke, line, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        BandMapping.CENTER_FREQS_HZ.forEachIndexed { i, f ->
            drawCircle(line, radius = 3.dp.toPx(), center = Offset(x(f), y(gains.getOrElse(i) { 0f })))
        }
    }
}
