package com.mmcdole.tether.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mmcdole.tether.data.settings.AppSettings
import com.mmcdole.tether.ui.theme.TetherColors
import kotlin.math.floor
import kotlin.math.roundToInt

/** The terminal size the MUD is told about through NAWS. */
data class WindowSize(val columns: Int, val rows: Int)

/** How many monospace cells of [charWidth] by [lineHeight] fit in [width] by [height], all in pixels. */
fun windowSize(width: Float, height: Float, charWidth: Float, lineHeight: Float) = WindowSize(
    columns = floor(width / charWidth).toInt().coerceAtLeast(1),
    rows = floor(height / lineHeight).toInt().coerceAtLeast(1),
)

/** The width of one character and the height of one output line in [style], in pixels. */
fun TextMeasurer.cellSize(style: TextStyle): Pair<Float, Float> {
    val sample = measure(SAMPLE, style, softWrap = false, maxLines = 1)
    return sample.getLineRight(0) / SAMPLE.length to sample.size.height.toFloat()
}

// Long enough that rounding in the measured width doesn't matter.
private val SAMPLE = "0".repeat(100)

/**
 * Pinch to change the text size. Watches in the Initial pass and does nothing until two
 * fingers are down, so one-finger touches still reach selection and scrolling untouched.
 * Once pinching it consumes the rest of the gesture, so the fingers don't also scroll or
 * start a selection. [onSize] gets the size as pinching starts and each new one after;
 * [onEnd] comes when the fingers lift.
 */
fun Modifier.textSizeGesture(size: () -> Int, onSize: (Int) -> Unit, onEnd: () -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var pinching = false
        var start = 0
        var zoom = 1f
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val fingers = event.changes.count { it.pressed }
            if (!pinching && fingers >= 2) {
                pinching = true
                start = size()
                onSize(start)
            }
            if (pinching) {
                if (fingers >= 2) {
                    // Clamped, so pinching back from a limit responds at once.
                    zoom = (zoom * event.calculateZoom()).coerceIn(MIN_SIZE / start, MAX_SIZE / start)
                    val next = (start * zoom).roundToInt().coerceIn(AppSettings.MIN_TEXT_SIZE, AppSettings.MAX_TEXT_SIZE)
                    if (next != size()) onSize(next)
                }
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (pinching) onEnd()
    }
}

private val MIN_SIZE = AppSettings.MIN_TEXT_SIZE.toFloat()
private val MAX_SIZE = AppSettings.MAX_TEXT_SIZE.toFloat()

/** The size and how many columns fit, shown while pinching. */
@Composable
fun TextSizeOverlay(size: Int, columns: Int, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(OverlayBackground, OverlayShape)
            .border(1.dp, TetherColors.Line, OverlayShape)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("$size", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Text("$columns cols", fontSize = 13.sp, color = TetherColors.TextDim)
    }
}

private val OverlayShape = RoundedCornerShape(16.dp)
private val OverlayBackground = Color(0xF01E2026)
