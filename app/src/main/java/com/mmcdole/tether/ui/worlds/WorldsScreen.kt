package com.mmcdole.tether.ui.worlds

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mmcdole.tether.ui.theme.MudFont
import com.mmcdole.tether.ui.theme.TetherColors
import com.mmcdole.tether.ui.uptime
import kotlinx.coroutines.delay

@Composable
fun WorldsScreen(
    onOpenSession: () -> Unit,
    onWorldLongClick: (Long) -> Unit,
    onAddWorld: () -> Unit,
    onSettings: () -> Unit,
    onGlobalRules: () -> Unit,
    viewModel: WorldsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var switchTo by remember { mutableStateOf<WorldRow?>(null) }

    fun open(world: WorldRow) {
        if (state.active == null) {
            viewModel.connect(world.id)
            onOpenSession()
        } else {
            switchTo = world
        }
    }

    Scaffold(
        containerColor = TetherColors.Background,
        contentWindowInsets = WindowInsets.safeDrawing,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddWorld,
                shape = RoundedCornerShape(16.dp),
                containerColor = TetherColors.Accent,
                contentColor = TetherColors.Text,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add world")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Worlds",
                    modifier = Modifier.weight(1f).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onSettings, modifier = Modifier.padding(end = 4.dp, top = 6.dp)) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = TetherColors.TextDim)
                }
            }
            if (!state.loading) GlobalRulesRow(state.globalTriggers, state.globalAliases, onGlobalRules)
            val active = state.active
            when {
                state.loading -> Unit
                active == null && state.worlds.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No worlds yet", fontSize = 15.sp, color = TetherColors.TextDim)
                }
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                    if (active != null) {
                        item(key = "active") {
                            ActiveWorldCard(
                                active = active,
                                onOpen = onOpenSession,
                                onDisconnect = viewModel::disconnect,
                                onLongClick = { onWorldLongClick(active.world.id) },
                            )
                        }
                        if (state.worlds.isNotEmpty()) item(key = "other") { OtherWorldsLabel() }
                    }
                    items(state.worlds, key = { it.id }) { world ->
                        WorldItem(
                            world = world,
                            onClick = { open(world) },
                            onLongClick = { onWorldLongClick(world.id) },
                        )
                        HorizontalDivider(color = TetherColors.Line)
                    }
                }
            }
        }
    }

    switchTo?.let { world ->
        AlertDialog(
            onDismissRequest = { switchTo = null },
            title = { Text("Switch to ${world.name}?") },
            text = { Text("This disconnects ${state.active?.world?.name.orEmpty()}.") },
            confirmButton = {
                TextButton(onClick = {
                    switchTo = null
                    viewModel.connect(world.id)
                    onOpenSession()
                }) { Text("Switch", color = TetherColors.AccentText) }
            },
            dismissButton = {
                TextButton(onClick = { switchTo = null }) { Text("Cancel", color = TetherColors.Text) }
            },
            containerColor = TetherColors.SurfaceRaised,
            titleContentColor = TetherColors.Text,
            textContentColor = TetherColors.TextDim,
        )
    }
}

@Composable
private fun ActiveWorldCard(active: ActiveWorld, onOpen: () -> Unit, onDisconnect: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, TetherColors.LiveBorder, shape)
            .background(TetherColors.LiveBackground)
            .combinedClickable(onClick = onOpen, onLongClick = onLongClick, onLongClickLabel = "Edit")
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(active.world.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    active.world.address,
                    fontFamily = MudFont,
                    fontSize = 13.sp,
                    color = TetherColors.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            StatusBadge(active)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onOpen,
                modifier = Modifier.weight(1f).height(40.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TetherColors.Accent, contentColor = Color.White),
            ) { Text("Open", fontWeight = FontWeight.SemiBold) }
            DisconnectButton(onDisconnect, Modifier.weight(1f).height(40.dp))
        }
    }
}

@Composable
private fun StatusBadge(active: ActiveWorld) {
    val (label, color) = when (active.status) {
        ActiveStatus.Connected -> uptime(active.since) to TetherColors.Connected
        ActiveStatus.Connecting -> "Connecting" to TetherColors.Pending
        ActiveStatus.Reconnecting -> "Reconnecting" to TetherColors.Pending
    }
    Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, color = color, maxLines = 1)
    }
}

/** Disconnects on the second tap within 3 seconds, so a mis-tap doesn't drop the session. */
@Composable
private fun DisconnectButton(onDisconnect: () -> Unit, modifier: Modifier) {
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(confirming) {
        if (confirming) {
            delay(3_000)
            confirming = false
        }
    }
    OutlinedButton(
        onClick = {
            if (confirming) {
                confirming = false
                onDisconnect()
            } else {
                confirming = true
            }
        },
        modifier = modifier,
        border = BorderStroke(1.dp, if (confirming) TetherColors.Danger else TetherColors.DangerBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TetherColors.Danger),
    ) { Text(if (confirming) "Tap again" else "Disconnect", fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun OtherWorldsLabel() {
    Text(
        "OTHER WORLDS",
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
        fontSize = 12.sp,
        letterSpacing = 0.08.em,
        color = TetherColors.TextDim,
    )
}

@Composable
private fun WorldItem(world: WorldRow, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Edit")
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(world.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            world.address,
            fontFamily = MudFont,
            fontSize = 13.sp,
            color = TetherColors.TextDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        world.lastPlayed?.let { Text(it, fontSize = 13.sp, color = TetherColors.TextDim) }
    }
}

@Composable
private fun GlobalRulesRow(triggers: Int, aliases: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, TetherColors.Line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Global rules", fontSize = 16.sp)
            Text(
                "$triggers ${if (triggers == 1) "trigger" else "triggers"} · $aliases ${if (aliases == 1) "alias" else "aliases"}",
                fontSize = 13.sp,
                color = TetherColors.TextDim,
            )
        }
        Text("›", fontSize = 22.sp, color = TetherColors.TextDim)
    }
}
