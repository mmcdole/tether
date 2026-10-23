package com.mmcdole.tether.ui.rules

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mmcdole.tether.data.rules.OnConflict
import com.mmcdole.tether.mud.rules.Scope
import com.mmcdole.tether.ui.theme.MudFont
import com.mmcdole.tether.ui.theme.TetherColors

/** The long-press sheet: Edit, Copy / move and Delete, with the copy / move checklist inline. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CopyMoveSheet(actions: RuleActions, worlds: List<WorldOption>, viewModel: RulesViewModel) {
    val transfer = actions.transfer
    ModalBottomSheet(
        onDismissRequest = viewModel::dismissActions,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = TetherColors.Surface,
        contentColor = TetherColors.Text,
        scrimColor = TetherColors.Background.copy(alpha = 0.6f),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                actions.row.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, TetherColors.Line, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                fontFamily = MudFont,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuleChip("Edit", false, { viewModel.edit(actions.row) })
                RuleChip("Copy / move", transfer != null, viewModel::startTransfer)
                RuleChip("Delete", false, viewModel::requestDelete)
            }
            if (transfer != null) TransferFields(actions.row.scope, transfer, worlds, viewModel)
        }
    }

    transfer?.conflicts?.let { names -> ConflictDialog(actions.row.title, names, viewModel::resolveConflicts) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferFields(current: Scope, transfer: TransferState, worlds: List<WorldOption>, viewModel: RulesViewModel) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        TransferMode.entries.forEachIndexed { i, mode ->
            SegmentedButton(
                selected = transfer.mode == mode,
                onClick = { viewModel.setTransferMode(mode) },
                shape = SegmentedButtonDefaults.itemShape(i, TransferMode.entries.size),
                icon = {},
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = TetherColors.Selected,
                    activeContentColor = TetherColors.AccentText,
                    activeBorderColor = TetherColors.SelectedBorder,
                    inactiveContainerColor = TetherColors.Background,
                    inactiveContentColor = TetherColors.Text,
                    inactiveBorderColor = TetherColors.Line,
                ),
            ) { Text(mode.name, fontSize = 15.sp) }
        }
    }

    Column {
        SheetLabel("To", Modifier.padding(bottom = 2.dp))
        Destination("All worlds", Scope.Global, current, transfer.selected, viewModel::toggleDestination)
        worlds.forEach { w -> Destination(w.name, Scope.World(w.id), current, transfer.selected, viewModel::toggleDestination) }
    }

    val n = transfer.selected.size
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = viewModel::dismissActions) { Text("Cancel", color = TetherColors.AccentText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        Button(
            onClick = viewModel::applyTransfer,
            enabled = n > 0 && !transfer.working,
            colors = ButtonDefaults.buttonColors(
                containerColor = TetherColors.Accent,
                contentColor = TetherColors.Text,
                disabledContainerColor = TetherColors.Tag,
                disabledContentColor = TetherColors.TextDim,
            ),
        ) { Text("${transfer.mode.name} to $n", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun Destination(name: String, scope: Scope, current: Scope, selected: Set<Scope>, onToggle: (Scope) -> Unit) {
    val isCurrent = scope == current
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (isCurrent) 0.45f else 1f)
            .clickable(enabled = !isCurrent) { onToggle(scope) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = scope in selected,
            onCheckedChange = null,
            modifier = Modifier.padding(12.dp),
            enabled = !isCurrent,
            colors = CheckboxDefaults.colors(
                checkedColor = TetherColors.Accent,
                uncheckedColor = TetherColors.TextDim,
                checkmarkColor = TetherColors.Text,
                disabledUncheckedColor = TetherColors.TextDim,
            ),
        )
        Text(name, Modifier.weight(1f, fill = false), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (isCurrent) Text("current", Modifier.padding(start = 8.dp), fontSize = 13.sp, color = TetherColors.TextDim)
    }
}

@Composable
private fun ConflictDialog(name: String, worlds: List<String>, onResolve: (OnConflict?) -> Unit) {
    AlertDialog(
        onDismissRequest = { onResolve(null) },
        title = { Text("“$name” already exists") },
        text = { Text(worlds.joinToString("\n"), fontSize = 15.sp) },
        confirmButton = {
            Row {
                TextButton(onClick = { onResolve(null) }) { Text("Cancel", color = TetherColors.Text) }
                TextButton(onClick = { onResolve(OnConflict.Skip) }) { Text("Skip those", color = TetherColors.AccentText) }
                TextButton(onClick = { onResolve(OnConflict.Replace) }) { Text("Replace", color = TetherColors.Danger) }
            }
        },
        containerColor = TetherColors.SurfaceRaised,
        titleContentColor = TetherColors.Text,
        textContentColor = TetherColors.TextDim,
    )
}
