package com.mmcdole.tether.ui.rules

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mmcdole.tether.mud.rules.Scope
import com.mmcdole.tether.ui.theme.TetherColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RuleEditorSheet(editor: RuleEditor, world: WorldOption?, viewModel: RulesViewModel) {
    ModalBottomSheet(
        onDismissRequest = viewModel::dismissEditor,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = TetherColors.Surface,
        contentColor = TetherColors.Text,
        scrimColor = TetherColors.Background.copy(alpha = 0.6f),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (editor) {
                is RuleEditor.EditTrigger -> TriggerFields(editor.form, world, viewModel)
                is RuleEditor.EditAlias -> AliasFields(editor.form, world, viewModel)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TriggerFields(form: TriggerForm, world: WorldOption?, viewModel: RulesViewModel) {
    val update = viewModel::updateTrigger
    Text(if (form.isNew) "New trigger" else "Edit trigger", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    SheetField(
        "Pattern",
        form.pattern,
        { v -> update { it.copy(pattern = v) } },
        error = form.patternError,
        visualTransformation = WildcardTransformation(!form.isRegex),
    )
    SheetField("Send command", form.send, { v -> update { it.copy(send = v) } }, placeholder = "kill %1")

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SheetLabel("Actions")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val color = form.highlight?.let { HighlightColor.of(it) }
            RuleChip(
                label = color?.let { "Highlight ${it.label}" } ?: "Highlight",
                selected = form.highlight != null,
                onClick = { update { it.copy(highlight = if (it.highlight == null) HighlightColor.Red.rgb else null) } },
                leading = form.highlight?.let {
                    { Box(Modifier.size(12.dp).background(color?.color ?: TetherColors.TextDim, CircleShape)) }
                },
            )
            RuleChip("Gag", form.gag, { update { it.copy(gag = !it.gag) } })
            RuleChip("Vibrate", form.vibrate, { update { it.copy(vibrate = !it.vibrate) } })
            RuleChip("Notify", form.notify, { update { it.copy(notify = !it.notify) } })
        }
        AnimatedVisibility(form.highlight != null) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                HighlightColor.entries.forEach { c ->
                    val selected = c.rgb == form.highlight
                    Box(
                        Modifier
                            .size(32.dp)
                            .selectedRing(selected, TetherColors.Text)
                            .padding(if (selected) 4.dp else 0.dp)
                            .background(c.color, CircleShape)
                            .clickable { update { it.copy(highlight = c.rgb) } }
                            .semantics { contentDescription = c.label },
                    )
                }
            }
        }
    }

    AnimatedVisibility(form.notify) {
        SheetField("Reply template", form.replyTemplate, { v -> update { it.copy(replyTemplate = v) } }, placeholder = "tell %1")
    }

    AppliesTo(form.scope, world) { s -> update { it.copy(scope = s) } }

    AnimatedVisibility(form.advanced) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SwitchRow("Regex", form.isRegex) { v -> update { it.copy(isRegex = v) } }
            if (form.notify) {
                SwitchRow("Require unlock", form.replyRequiresUnlock) { v -> update { it.copy(replyRequiresUnlock = v) } }
            }
        }
    }

    Buttons(
        advanced = form.advanced,
        onAdvanced = { update { it.copy(advanced = !it.advanced) } },
        canSave = form.canSave,
        onSave = viewModel::save,
    )
}

@Composable
private fun AliasFields(form: AliasForm, world: WorldOption?, viewModel: RulesViewModel) {
    val update = viewModel::updateAlias
    Text(if (form.isNew) "New alias" else "Edit alias", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    SheetField(
        "Name",
        form.name,
        { v -> update { it.copy(name = v) } },
        error = form.nameError,
        accept = { it.filterNot(Char::isWhitespace) },
        placeholder = "k",
    )
    SheetField("Expansion", form.expansion, { v -> update { it.copy(expansion = v) } }, placeholder = "kill %1")
    AppliesTo(form.scope, world) { s -> update { it.copy(scope = s) } }
    Buttons(advanced = null, onAdvanced = {}, canSave = form.canSave, onSave = viewModel::save)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppliesTo(scope: Scope, world: WorldOption?, onScope: (Scope) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SheetLabel("Applies to")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            world?.let { RuleChip(it.name, scope == Scope.World(it.id), { onScope(Scope.World(it.id)) }) }
            RuleChip("All worlds", scope == Scope.Global, { onScope(Scope.Global) })
        }
    }
}

@Composable
private fun Buttons(advanced: Boolean?, onAdvanced: () -> Unit, canSave: Boolean, onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        if (advanced != null) {
            TextButton(onClick = onAdvanced) {
                Text("Advanced", color = TetherColors.AccentText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Icon(
                    if (advanced) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = TetherColors.AccentText,
                )
            }
            Spacer(Modifier.size(8.dp))
        }
        Button(
            onClick = onSave,
            enabled = canSave,
            colors = ButtonDefaults.buttonColors(
                containerColor = TetherColors.Accent,
                contentColor = TetherColors.Text,
                disabledContainerColor = TetherColors.Tag,
                disabledContentColor = TetherColors.TextDim,
            ),
        ) { Text("Save", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
    }
}
