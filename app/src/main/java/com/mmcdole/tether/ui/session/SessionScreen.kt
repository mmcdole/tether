package com.mmcdole.tether.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mmcdole.tether.ui.theme.TetherColors
import com.mmcdole.tether.ui.uptime

@Composable
fun SessionScreen(onWorlds: () -> Unit, viewModel: SessionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // safeDrawing includes the keyboard, so the output shrinks to sit above it.
    Column(
        Modifier
            .fillMaxSize()
            .background(TetherColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        FirstRunPrompts()
        TopBar(state, onDisconnect = viewModel::disconnect, onWorlds = onWorlds)
        OutputPane(state.lines, Modifier.weight(1f).fillMaxWidth())
        if (state.status == ConnectionStatus.Idle) {
            Disconnected(onReconnect = viewModel::reconnect.takeIf { state.canReconnect })
        }
        CommandInput(password = state.password, history = state.history, onSend = viewModel::send)
    }
}

@Composable
private fun TopBar(state: SessionUiState, onDisconnect: () -> Unit, onWorlds: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(state.status.dotColor, CircleShape))
        Spacer(Modifier.width(8.dp))
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
        Spacer(Modifier.weight(1f))
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = TetherColors.TextDim)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = TetherColors.SurfaceRaised) {
                if (state.status != ConnectionStatus.Idle) {
                    DropdownMenuItem(text = { Text("Disconnect") }, onClick = { menu = false; onDisconnect() })
                }
                DropdownMenuItem(text = { Text("Worlds") }, onClick = { menu = false; onWorlds() })
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
