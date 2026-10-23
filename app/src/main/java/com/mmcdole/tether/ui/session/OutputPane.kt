package com.mmcdole.tether.ui.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mmcdole.tether.mud.text.LineKind
import com.mmcdole.tether.mud.text.StyledLine
import com.mmcdole.tether.ui.theme.AnsiColors
import com.mmcdole.tether.ui.theme.MudFont
import com.mmcdole.tether.ui.theme.TetherColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.mmcdole.tether.mud.ansi.TextStyle as AnsiStyle

/** A scrollback line with its text already styled for display. */
@Immutable
class OutputLine(val line: StyledLine, val text: AnnotatedString) {
    val id: Long get() = line.id
}

/** Styles [lines], reusing [previous] entries for lines that haven't changed. */
fun renderLines(lines: List<StyledLine>, previous: List<OutputLine>): List<OutputLine> {
    val firstId = previous.firstOrNull()?.id ?: 0L
    return lines.map { line ->
        val old = previous.getOrNull((line.id - firstId).toInt())
        if (old != null && old.line === line) old else OutputLine(line, line.toAnnotatedString())
    }
}

fun StyledLine.toAnnotatedString(): AnnotatedString = buildAnnotatedString {
    append(text)
    for (span in spans) addStyle(span.style.toSpanStyle(), span.start, span.end)
    // The tint behind it is drawn across the whole row, by highlightTint.
    highlight?.let { addStyle(SpanStyle(color = Color(0xFF000000.toInt() or it)), 0, text.length) }
    when (kind) {
        LineKind.Echo, LineKind.System -> addStyle(Dim, 0, text.length)
        LineKind.Prompt -> if (echoStart in 0..text.length) addStyle(Dim, echoStart, text.length)
        LineKind.Output -> Unit
    }
}

/** A trigger highlight's faint tint, as in the mockup: the color at 22% across the line. */
fun highlightTint(rgb: Int) = Color(0x38000000 or rgb)

private val Dim = SpanStyle(color = TetherColors.TextDim)

private fun AnsiStyle.toSpanStyle(): SpanStyle {
    val fg = effectiveForeground?.let(AnsiColors::resolve)
    val bg = background?.let(AnsiColors::resolve)
    var color = if (reverse) bg ?: TetherColors.Background else fg
    if (dim) color = (color ?: TetherColors.Text).copy(alpha = 0.6f)
    return SpanStyle(
        color = color ?: Color.Unspecified,
        background = (if (reverse) fg ?: TetherColors.Text else bg) ?: Color.Unspecified,
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = if (underline) TextDecoration.Underline else null,
    )
}

/** Output text at [size] sp. No trimming, so wrapped rows and separate lines sit at the same pitch. */
fun mudTextStyle(size: Int) = TextStyle(
    fontFamily = MudFont,
    fontSize = size.sp,
    lineHeight = (size * LINE_SPACING).sp,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    color = TetherColors.Text,
)

private const val LINE_SPACING = 18f / 13f

/**
 * The scrollback, newest at the bottom, selectable like any Android text. Follows new
 * output while at the bottom. Scrolling up or long-pressing pauses it; a chip counts
 * what arrived since. Pinching changes [textSize] through [onTextSize], and [onTextSizeEnd]
 * comes when the fingers lift. [onWindowSize] gets the columns and rows that fit, once they settle.
 */
@Composable
fun OutputPane(
    lines: List<OutputLine>,
    textSize: Int,
    onTextSize: (Int) -> Unit,
    onTextSizeEnd: () -> Unit,
    onWindowSize: (WindowSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var following by remember { mutableStateOf(true) }
    // The newest line the view last moved to while following.
    var shownId by remember { mutableLongStateOf(0L) }
    val newestId = lines.lastOrNull()?.id ?: 0L
    val atBottom by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
    }

    // Runs before this frame's layout. Never fights a drag in progress; key-based
    // position retention keeps the view still when we don't move it.
    SideEffect {
        if (following && newestId != shownId && !listState.isScrollInProgress) {
            listState.requestScrollToItem(0)
            shownId = newestId
        }
    }

    // Only the user's own scrolling changes whether we follow.
    LaunchedEffect(listState) {
        var wasScrolling = false
        snapshotFlow { listState.isScrollInProgress to atBottom }.collect { (scrolling, bottom) ->
            if (scrolling || wasScrolling) following = bottom
            wasScrolling = scrolling
        }
    }

    val style = remember(textSize) { mudTextStyle(textSize) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var paneSize by remember { mutableStateOf(IntSize.Zero) }
    val window = remember(paneSize, style, density) {
        if (paneSize == IntSize.Zero) return@remember null
        val (charWidth, lineHeight) = measurer.cellSize(style)
        with(density) {
            windowSize(
                width = paneSize.width - 2 * PaneHorizontalPadding.toPx(),
                height = paneSize.height - 2 * PaneVerticalPadding.toPx(),
                charWidth = charWidth,
                lineHeight = lineHeight,
            )
        }
    }
    val currentOnWindowSize by rememberUpdatedState(onWindowSize)
    // Restarting on each change debounces pinching, rotation and the keyboard sliding in.
    LaunchedEffect(window) {
        if (window == null) return@LaunchedEffect
        delay(WINDOW_SIZE_DEBOUNCE_MS)
        currentOnWindowSize(window)
    }
    var pinching by remember { mutableStateOf(false) }
    val currentTextSize by rememberUpdatedState(textSize)

    Box(
        modifier
            .onSizeChanged { paneSize = it }
            .textSizeGesture(
                size = { currentTextSize },
                onSize = {
                    pinching = true
                    onTextSize(it)
                },
                onEnd = {
                    pinching = false
                    onTextSizeEnd()
                },
            )
            .pointerInput(Unit) {
                // Watches for a long-press without consuming anything, so selection still gets it.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val endedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) break
                            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                        }
                        true
                    }
                    if (endedEarly == null) following = false
                }
            },
    ) {
        SelectionContainer(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = PaneHorizontalPadding, vertical = PaneVerticalPadding),
            ) {
                items(count = lines.size, key = { lines[lines.lastIndex - it].id }, contentType = { 0 }) { index ->
                    val line = lines[lines.lastIndex - index]
                    val tint = line.line.highlight?.let { Modifier.background(highlightTint(it)) } ?: Modifier
                    BasicText(line.text, Modifier.fillMaxWidth().then(tint), style = style)
                }
            }
        }

        if (pinching && window != null) TextSizeOverlay(textSize, window.columns, Modifier.align(OverlayAlignment))

        val newLines = if (following) 0L else newestId - shownId
        if (newLines > 0) {
            Surface(
                onClick = {
                    following = true
                    scope.launch { listState.scrollToItem(0) }
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                shape = RoundedCornerShape(50),
                color = ChipBackground,
                border = BorderStroke(1.dp, ChipBorder),
            ) {
                Text(
                    "Paused · $newLines new ${if (newLines == 1L) "line" else "lines"} ↓",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    color = TetherColors.AccentText,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

private val PaneHorizontalPadding = 10.dp
private val PaneVerticalPadding = 4.dp
private const val WINDOW_SIZE_DEBOUNCE_MS = 300L

// A little above center, clear of the fingers.
private val OverlayAlignment = BiasAlignment(0f, -0.15f)

private val ChipBackground = Color(0xFF1D2433)
private val ChipBorder = Color(0xFF2F3D5C)
