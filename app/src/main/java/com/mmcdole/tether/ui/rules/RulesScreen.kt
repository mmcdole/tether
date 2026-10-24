package com.mmcdole.tether.ui.rules

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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mmcdole.tether.mud.rules.Scope
import com.mmcdole.tether.ui.theme.MudFont
import com.mmcdole.tether.ui.theme.TetherColors

@Composable
fun RulesScreen(onBack: () -> Unit, viewModel: RulesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        // Cleared only after it's shown; clearing first would change the key and cancel this effect.
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    Scaffold(
        containerColor = TetherColors.Background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = {
            SnackbarHost(snackbar) { Snackbar(it, containerColor = TetherColors.SurfaceRaised, contentColor = TetherColors.Text) }
        },
        topBar = {
            Column(Modifier.background(TetherColors.Background).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                RulesTopBar(state, onBack, viewModel::selectWorld)
                RulesTabs(state, viewModel::selectTab)
            }
        },
    ) { padding ->
        if (state.loading) return@Scaffold
        val noun = state.tab.name.lowercase()
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.world?.let { world ->
                item(key = "world") {
                    RuleBox(
                        title = world.name,
                        global = false,
                        rows = state.worldRows,
                        empty = "No $noun",
                        onAdd = { viewModel.add(Scope.World(world.id)) },
                        onClick = viewModel::edit,
                        onLongClick = viewModel::showActions,
                        onToggle = viewModel::setEnabled,
                    )
                }
            }
            item(key = "global") {
                RuleBox(
                    title = "All worlds",
                    global = true,
                    rows = state.globalRows,
                    empty = "No $noun",
                    onAdd = { viewModel.add(Scope.Global) },
                    onClick = viewModel::edit,
                    onLongClick = viewModel::showActions,
                    onToggle = viewModel::setEnabled,
                )
            }
        }
    }

    state.editor?.let { RuleEditorSheet(it, state.world, viewModel) }
    state.actions?.let { CopyMoveSheet(it, state.worlds, viewModel) }
    state.confirmDelete?.let { row ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text("Delete ${(state.actions?.tab ?: state.tab).noun}?") },
            text = { Text(row.title, fontFamily = MudFont, color = TetherColors.TextDim) },
            confirmButton = { TextButton(onClick = viewModel::confirmDelete) { Text("Delete", color = TetherColors.Danger) } },
            dismissButton = { TextButton(onClick = viewModel::cancelDelete) { Text("Cancel", color = TetherColors.Text) } },
            containerColor = TetherColors.SurfaceRaised,
            titleContentColor = TetherColors.Text,
            textContentColor = TetherColors.TextDim,
        )
    }
}

@Composable
private fun RulesTopBar(state: RulesUiState, onBack: () -> Unit, onSelectWorld: (Long?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Text(
            state.world?.let { "Rules · ${it.name}" } ?: "Global rules",
            modifier = Modifier.weight(1f),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Switch world", tint = TetherColors.TextDim)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = TetherColors.SurfaceRaised) {
                WorldMenuItem("Global rules", state.world == null) { menu = false; onSelectWorld(null) }
                state.worlds.forEach { world ->
                    WorldMenuItem(world.name, state.world?.id == world.id) { menu = false; onSelectWorld(world.id) }
                }
            }
        }
    }
}

@Composable
private fun WorldMenuItem(name: String, current: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        onClick = onClick,
        trailingIcon = if (current) {
            { Icon(Icons.Filled.Check, contentDescription = null, tint = TetherColors.AccentText) }
        } else {
            null
        },
        colors = MenuDefaults.itemColors(textColor = if (current) TetherColors.AccentText else TetherColors.Text),
    )
}

@Composable
private fun RulesTabs(state: RulesUiState, onSelect: (RuleTab) -> Unit) {
    val selected = state.tab.ordinal
    // Four tabs with counts don't fit a narrow phone, so they scroll.
    SecondaryScrollableTabRow(
        selectedTabIndex = selected,
        edgePadding = 0.dp,
        containerColor = TetherColors.Background,
        contentColor = TetherColors.Text,
        indicator = {
            TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(selected), color = TetherColors.AccentText)
        },
        divider = { HorizontalDivider(color = TetherColors.Line) },
    ) {
        RuleTab.entries.forEach { tab ->
            val count = state.counts[tab] ?: 0
            val on = tab == state.tab
            Tab(
                selected = on,
                onClick = { onSelect(tab) },
                selectedContentColor = TetherColors.Text,
                unselectedContentColor = TetherColors.TextDim,
                text = {
                    Text(
                        "${tab.name}  $count",
                        fontSize = 15.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    )
                },
            )
        }
    }
}

@Composable
private fun RuleBox(
    title: String,
    global: Boolean,
    rows: List<RuleRow>,
    empty: String,
    onAdd: () -> Unit,
    onClick: (RuleRow) -> Unit,
    onLongClick: (RuleRow) -> Unit,
    onToggle: (RuleRow, Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val border = if (global) TetherColors.GlobalBorder else TetherColors.Line
    val line = if (global) TetherColors.GlobalLine else TetherColors.Line
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, border, shape)
            .background(if (global) TetherColors.GlobalBox else TetherColors.Background),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (global) TetherColors.GlobalHeader else TetherColors.BoxHeader)
                .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (global) {
                    Icon(GlobeIcon, contentDescription = null, modifier = Modifier.size(16.dp), tint = TetherColors.AccentText)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    title,
                    modifier = Modifier.weight(1f, fill = false),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(rows.size.toString(), fontSize = 13.sp, color = TetherColors.TextDim)
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (global) TetherColors.GlobalAdd else TetherColors.Tag)
                    .clickable(onClick = onAdd, onClickLabel = "Add"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = if (global) "Add to all worlds" else "Add to $title",
                    modifier = Modifier.size(20.dp),
                    tint = if (global) TetherColors.AccentText else TetherColors.Text,
                )
            }
        }
        HorizontalDivider(color = border)
        if (rows.isEmpty()) {
            Text(empty, Modifier.padding(horizontal = 14.dp, vertical = 14.dp), fontSize = 14.sp, color = TetherColors.TextDim)
        }
        rows.forEachIndexed { i, row ->
            if (i > 0) HorizontalDivider(color = line)
            RuleItem(row, onClick = { onClick(row) }, onLongClick = { onLongClick(row) }, onToggle = { onToggle(row, it) })
        }
    }
}

@Composable
private fun RuleItem(row: RuleRow, onClick: () -> Unit, onLongClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onClickLabel = "Edit", onLongClickLabel = "More")
            .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                row.title,
                fontFamily = MudFont,
                fontSize = 15.sp,
                color = if (row.enabled) TetherColors.Text else TetherColors.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.summary,
                modifier = Modifier.alpha(if (row.enabled) 1f else 0.6f),
                fontSize = 13.sp,
                color = TetherColors.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        RuleSwitch(row.enabled, onToggle)
    }
}

