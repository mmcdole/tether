package com.mmcdole.tether.ui.session

import android.text.InputType
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mmcdole.tether.ui.theme.MudFont
import com.mmcdole.tether.ui.theme.TetherColors
import kotlin.math.abs

/**
 * The command line: one line tall, scrolls sideways, keeps focus after Send unless
 * [keepKeyboardOpen] is off. A vertical swipe steps through [history]. While [password]
 * is set it masks input and remembers nothing.
 */
@Composable
fun CommandInput(
    password: Boolean,
    history: List<String>,
    onSend: (String) -> Unit,
    autocorrect: Boolean,
    keepKeyboardOpen: Boolean,
    modifier: Modifier = Modifier,
) {
    val command = rememberTextFieldState()
    val secret = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val currentHistory by rememberUpdatedState(history)
    // How far back we are in history; -1 is the line being typed, kept in draft.
    var recalled by remember { mutableIntStateOf(-1) }
    var draft by remember { mutableStateOf("") }
    val scroll = rememberScrollState()

    fun step(older: Boolean) {
        val list = currentHistory
        val next = (recalled + if (older) 1 else -1).coerceIn(-1, list.lastIndex)
        if (next == recalled) return
        if (recalled == -1) draft = command.text.toString()
        recalled = next
        command.setTextAndPlaceCursorAtEnd(if (next == -1) draft else list[list.lastIndex - next])
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    val focusManager = LocalFocusManager.current

    fun send(state: TextFieldState) {
        onSend(state.text.toString())
        state.clearText()
        recalled = -1
        if (!keepKeyboardOpen) focusManager.clearFocus()
    }

    // Swapping between the plain and secure field would drop focus and close the keyboard.
    val hadFocus = focused
    LaunchedEffect(password) { if (hadFocus) focus.requestFocus() }

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .background(TetherColors.Chrome, FieldShape)
            .border(1.dp, TetherColors.Line, FieldShape)
            .onFocusChanged { focused = it.hasFocus }
            .historySwipe(enabled = !password, onStep = ::step),
    ) {
        val fieldModifier = Modifier.fillMaxWidth().focusRequester(focus)
        if (password) {
            BasicSecureTextField(
                state = secret,
                modifier = fieldModifier,
                textStyle = FieldText,
                cursorBrush = SolidColor(TetherColors.Text),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Send),
                onKeyboardAction = { send(secret) },
                decorator = decorator(secret, "Password"),
            )
        } else {
            NoSuggestions(enabled = !autocorrect) {
            BasicTextField(
                state = command,
                modifier = fieldModifier.fadeStartWhenScrolled(scroll),
                textStyle = FieldText,
                cursorBrush = SolidColor(TetherColors.Text),
                lineLimits = TextFieldLineLimits.SingleLine,
                scrollState = scroll,
                keyboardOptions = KeyboardOptions(
                    capitalization = if (autocorrect) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
                    autoCorrectEnabled = autocorrect,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Send,
                ),
                // Not calling the default action keeps focus, so the keyboard stays open unless send() drops it.
                onKeyboardAction = { send(command) },
                decorator = decorator(command, "Send a command"),
            )
            }
        }
    }
}

/**
 * Hides the keyboard's suggestion strip. Gboard ignores the no-suggestions flag, so the field also
 * says it's a visible password, as terminal apps do. Compose has no option for either.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun NoSuggestions(enabled: Boolean, content: @Composable () -> Unit) {
    if (!enabled) return content()
    InterceptPlatformTextInput(
        interceptor = { request, next ->
            next.startInputMethod(
                PlatformTextInputMethodRequest { info ->
                    request.createInputConnection(info).also {
                        info.inputType = InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    }
                },
            )
        },
        content = content,
    )
}

private val FieldShape = RoundedCornerShape(8.dp)
private val FieldText = TextStyle(fontFamily = MudFont, fontSize = 14.sp, color = TetherColors.Text)
private val FieldPadding = 12.dp

private fun decorator(state: TextFieldState, placeholder: String) = TextFieldDecorator { inner ->
    Box(Modifier.padding(horizontal = FieldPadding, vertical = 11.dp)) {
        if (state.text.isEmpty()) Text(placeholder, style = FieldText.copy(color = TetherColors.TextDim))
        inner()
    }
}

/** Fades the start of the text once it has scrolled sideways, so it's clear there's more. */
private fun Modifier.fadeStartWhenScrolled(scroll: ScrollState) = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        if (scroll.value > 0) {
            val start = FieldPadding.toPx()
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = start, endX = start + 28.dp.toPx()),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/**
 * Calls [onStep] for each step of a vertical swipe (older when swiping up). Watches in the
 * Initial pass and only consumes once the gesture is clearly vertical, so taps, cursor
 * drags and sideways scrolling still reach the text field.
 */
private fun Modifier.historySwipe(enabled: Boolean, onStep: (older: Boolean) -> Unit) =
    if (!enabled) this else pointerInput(Unit) {
        val stepPx = 28.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var swiping = false
            var origin = down.position
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    if (swiping) change.consume()
                    break
                }
                val delta = change.position - origin
                if (!swiping) {
                    if (abs(delta.x) > viewConfiguration.touchSlop) break
                    if (abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) > 2 * abs(delta.x)) {
                        swiping = true
                        origin = change.position
                        onStep(delta.y < 0)
                    }
                }
                if (swiping) {
                    change.consume()
                    val dy = change.position.y - origin.y
                    if (abs(dy) >= stepPx) {
                        onStep(dy < 0)
                        origin = change.position
                    }
                }
            }
        }
    }
