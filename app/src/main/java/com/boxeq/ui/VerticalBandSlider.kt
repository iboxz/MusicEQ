package com.boxeq.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.boxeq.dsp.BandMapping
import java.util.Locale
import kotlin.math.abs

/**
 * Touch: drag or tap to set the value, long-press to reset to 0 dB. Exposed to TalkBack as a range control.
 */
@Composable
fun VerticalBandSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    min: Float = BandMapping.MIN_GAIN_DB,
    max: Float = BandMapping.MAX_GAIN_DB,
) {
    val onChange by rememberUpdatedState(onValueChange)
    val padPx = with(LocalDensity.current) { 12.dp.toPx() }
    var heightPx by remember { mutableFloatStateOf(1f) }

    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val activeColor = MaterialTheme.colorScheme.primary
    val zeroColor = MaterialTheme.colorScheme.outline

    fun valueAt(y: Float): Float {
        val usable = (heightPx - 2 * padPx).coerceAtLeast(1f)
        val frac = 1f - ((y - padPx) / usable).coerceIn(0f, 1f)
        return BandMapping.snapGain(min + frac * (max - min))
    }

    val state = String.format(Locale.US, "%.1f dB", value)

    Canvas(
        modifier = modifier
            .onSizeChanged { heightPx = it.height.toFloat() }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { onChange(valueAt(it.y)) },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        onChange(valueAt(change.position.y))
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onChange(valueAt(it.y)) },
                    onLongPress = { onChange(0f) },
                )
            }
            .semantics {
                contentDescription = description
                stateDescription = state
                progressBarRangeInfo = ProgressBarRangeInfo(value, min..max)
                setProgress { target ->
                    onChange(BandMapping.snapGain(target))
                    true
                }
            },
    ) {
        val cx = size.width / 2f
        val trackW = 6.dp.toPx()
        val usable = (size.height - 2 * padPx).coerceAtLeast(1f)
        fun yFor(v: Float) = padPx + (1f - (v - min) / (max - min)) * usable

        drawRoundRect(
            color = trackColor,
            topLeft = Offset(cx - trackW / 2, padPx),
            size = Size(trackW, usable),
            cornerRadius = CornerRadius(trackW / 2),
        )
        val y0 = yFor(0f)
        val yv = yFor(value)
        drawRoundRect(
            color = activeColor,
            topLeft = Offset(cx - trackW / 2, minOf(y0, yv)),
            size = Size(trackW, abs(y0 - yv).coerceAtLeast(1f)),
            cornerRadius = CornerRadius(trackW / 2),
        )
        drawLine(zeroColor, Offset(cx - 13.dp.toPx(), y0), Offset(cx + 13.dp.toPx(), y0), strokeWidth = 1.5.dp.toPx())
        drawCircle(activeColor, radius = 10.dp.toPx(), center = Offset(cx, yv))
    }
}
