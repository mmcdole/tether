package com.mmcdole.tether.ui.session

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mmcdole.tether.ui.theme.TetherColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A chat tray line, styled for display. */
@Immutable
class ChatMessage(val id: Long, val text: AnnotatedString, val time: Instant)

/** How far the tray is pulled down. It always starts at [Line]. */
private enum class TrayHeight { Line, Few, Full }

/**
 * The chat tray's one-line strip, which takes its own space above the output. Pulled down, the tray
 * grows over the output: to a few lines or all the way, snapping to whichever is closest. Tapping the
 * strip opens it to a few lines; tapping the handle or Back closes it.
 */
@Composable
fun ChatTray(messages: List<ChatMessage>, textSize: Int, output: @Composable (Modifier) -> Unit, modifier: Modifier = Modifier) {
    var height by rememberSaveable { mutableStateOf(TrayHeight.Line) }
    // While dragging: how far past the snapped height, in px.
    var drag by remember { mutableFloatStateOf(0f) }
    // The newest line seen with the tray open; anything newer gets the dot.
    var seenId by rememberSaveable { mutableLongStateOf(0L) }
    val newest = messages.lastOrNull()
    SideEffect { if (height != TrayHeight.Line && newest != null) seenId = newest.id }
    BackHandler(enabled = height != TrayHeight.Line) { height = TrayHeight.Line }

    val style = remember(textSize) { mudTextStyle(textSize) }
    val density = LocalDensity.current
    val lineHeight = with(density) { style.lineHeight.toDp() }
    val stripHeight = lineHeight + StripPadding * 2

    BoxWithConstraints(modifier) {
        val heights = mapOf(
            TrayHeight.Line to stripHeight,
            TrayHeight.Few to minOf(lineHeight * FEW_LINES + RowGap * (FEW_LINES - 1) + PanelPadding * 2 + HandleSpace, maxHeight),
            TrayHeight.Full to maxHeight,
        )
        val shown = (heights.getValue(height) + with(density) { drag.toDp() }).coerceIn(stripHeight, maxHeight)

        Column {
            Strip(newest, newest != null && newest.id > seenId, style, stripHeight) { height = TrayHeight.Few; drag = 0f }
            output(Modifier.weight(1f))
        }

        if (shown > stripHeight) {
            Column(Modifier.fillMaxWidth().height(shown).background(TetherColors.Chrome)) {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = PanelPadding),
                    verticalArrangement = Arrangement.spacedBy(RowGap),
                ) {
                    items(messages.asReversed(), key = { it.id }) { message ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            BasicText(message.text, Modifier.weight(1f), style = style)
                            TimeText(message.time)
                        }
                    }
                }
                HorizontalDivider(color = TetherColors.Line)
            }
        }

        // The handle sits on the tray's bottom edge and is what you drag.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = shown - HandleTouch / 2)
                .size(width = 96.dp, height = HandleTouch)
                .pointerInput(heights) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            val target = heights.getValue(height) + drag.toDp()
                            height = heights.minBy { (_, h) -> kotlin.math.abs((h - target).value) }.key
                            drag = 0f
                        },
                        onDragCancel = { drag = 0f },
                    ) { change, amount ->
                        change.consume()
                        drag += amount
                    }
                }
                .clickable(onClickLabel = "Close chat") { height = TrayHeight.Line },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(width = 36.dp, height = 5.dp).background(if (shown > stripHeight) HandleOpen else Handle, RoundedCornerShape(3.dp)))
        }
    }
}

@Composable
private fun Strip(newest: ChatMessage?, unread: Boolean, style: TextStyle, height: Dp, onOpen: () -> Unit) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .height(height)
                .background(TetherColors.Chrome)
                .clickable(onClickLabel = "Open chat", onClick = onOpen)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (newest != null) {
                BasicText(newest.text, Modifier.weight(1f), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (unread) Box(Modifier.size(7.dp).background(TetherColors.AccentText, CircleShape))
                TimeText(newest.time)
            }
        }
        HorizontalDivider(color = TetherColors.Line)
    }
}

@Composable
private fun TimeText(time: Instant) {
    val context = LocalContext.current
    val format = remember(context) { DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm") }
    Text(
        format.format(time.atZone(ZoneId.systemDefault())),
        Modifier.width(36.dp),
        fontSize = 11.sp,
        color = TetherColors.TextDim,
        maxLines = 1,
    )
}

private const val FEW_LINES = 5
private val StripPadding = 8.dp
private val PanelPadding = 8.dp
private val RowGap = 6.dp
private val HandleSpace = 8.dp
private val HandleTouch = 24.dp
private val Handle = Color(0xFF3A3C42)
private val HandleOpen = Color(0xFF5A5D66)
