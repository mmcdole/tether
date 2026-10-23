package com.mmcdole.tether.ui.session

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mmcdole.tether.ui.theme.TetherColors
import com.mmcdole.tether.ui.uptime

@Composable
fun SessionScreen(onWorlds: () -> Unit, onSettings: () -> Unit, viewModel: SessionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val hideStatusBar = settings?.hideStatusBar == true
    HideStatusBar(hideStatusBar)
    // safeDrawing includes the keyboard, so the output shrinks to sit above it. With the
    // status bar hidden the top bar moves up into its space, cutout included.
    val insets = if (hideStatusBar) {
        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
    } else {
        WindowInsets.safeDrawing
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(TetherColors.Background)
            .windowInsetsPadding(insets),
    ) {
        FirstRunPrompts()
        TopBar(state, onDisconnect = viewModel::disconnect, onWorlds = onWorlds, onSettings = onSettings)
        val current = settings
        if (current == null) {
            Spacer(Modifier.weight(1f))
        } else {
            OutputPane(
                lines = state.lines,
                textSize = current.textSize,
                onTextSize = viewModel::pinchTextSize,
                onTextSizeEnd = viewModel::endPinch,
                onWindowSize = viewModel::setWindowSize,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
        if (state.status == ConnectionStatus.Idle) {
            Disconnected(onReconnect = viewModel::reconnect.takeIf { state.canReconnect })
        }
        CommandInput(
            password = state.password,
            history = state.history,
            onSend = viewModel::send,
            autocorrect = current?.autocorrect == true,
            keepKeyboardOpen = current?.keepKeyboardOpen != false,
        )
    }
}

/** Hides the status bar while [hide] is set and this is on screen. A swipe down shows it for a moment. */
@Composable
private fun HideStatusBar(hide: Boolean) {
    val view = LocalView.current
    DisposableEffect(hide) {
        val window = view.context.findActivity()?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        if (hide) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        }
        onDispose { if (hide) controller.show(WindowInsetsCompat.Type.statusBars()) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun TopBar(state: SessionUiState, onDisconnect: () -> Unit, onWorlds: () -> Unit, onSettings: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(state.status.dotColor, CircleShape))
        Spacer(Modifier.width(8.dp))
        // Its own weighted row, so a long name ellipsizes and the menu stays at the end.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.worldName,
                modifier = Modifier.weight(1f, fill = false),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            val meta = when (state.status) {
                ConnectionStatus.Connected -> uptime(state.connectedSince)
                ConnectionStatus.Connecting -> "Connecting"
                ConnectionStatus.Reconnecting -> "Reconnecting…"
                ConnectionStatus.Idle -> ""
            }
            Text(meta, fontSize = 12.sp, color = TetherColors.TextDim, maxLines = 1)
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = TetherColors.TextDim)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = TetherColors.SurfaceRaised) {
                if (state.status != ConnectionStatus.Idle) {
                    DropdownMenuItem(text = { Text("Disconnect") }, onClick = { menu = false; onDisconnect() })
                }
                DropdownMenuItem(text = { Text("Worlds") }, onClick = { menu = false; onWorlds() })
                DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; onSettings() })
            }
        }
    }
    HorizontalDivider(color = TetherColors.Line)
}

private val ConnectionStatus.dotColor: Color
    get() = when (this) {
        ConnectionStatus.Connected -> TetherColors.Connected
        ConnectionStatus.Connecting, ConnectionStatus.Reconnecting -> TetherColors.Pending
        ConnectionStatus.Idle -> TetherColors.TextDim
    }

@Composable
private fun Disconnected(onReconnect: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Disconnected", fontSize = 13.sp, color = TetherColors.TextDim)
        if (onReconnect != null) {
            Text(" · ", fontSize = 13.sp, color = TetherColors.TextDim)
            Text(
                "Reconnect",
                modifier = Modifier.clickable(onClick = onReconnect).padding(vertical = 6.dp, horizontal = 4.dp),
                fontSize = 13.sp,
                color = TetherColors.AccentText,
            )
        }
    }
}
