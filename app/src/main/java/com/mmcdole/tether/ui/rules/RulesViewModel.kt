package com.mmcdole.tether.ui.rules

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.mmcdole.tether.data.rules.OnConflict
import com.mmcdole.tether.data.rules.RuleLists
import com.mmcdole.tether.data.rules.RuleRepository
import com.mmcdole.tether.data.rules.RuleTransfer
import com.mmcdole.tether.data.world.World
import com.mmcdole.tether.data.world.WorldRepository
import com.mmcdole.tether.mud.rules.Alias
import com.mmcdole.tether.mud.rules.Scope
import com.mmcdole.tether.mud.rules.Trigger
import com.mmcdole.tether.ui.RulesRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RuleRow(
    val id: Long,
    val scope: Scope,
    /** The pattern, or the alias name. */
    val title: String,
    val summary: String,
    val group: String?,
    val enabled: Boolean,
)

data class WorldOption(val id: Long, val name: String)

sealed interface RuleEditor {
    data class EditTrigger(val form: TriggerForm) : RuleEditor
    data class EditAlias(val form: AliasForm) : RuleEditor
}

enum class TransferMode { Copy, Move }

data class TransferState(
    val mode: TransferMode = TransferMode.Copy,
    val selected: Set<Scope> = emptySet(),
    /** Names of destinations that already have this alias, while asking what to do. */
    val conflicts: List<String>? = null,
    val working: Boolean = false,
)

/** The long-press sheet, and the Copy / move section when it's open. */
data class RuleActions(val row: RuleRow, val tab: RuleTab, val transfer: TransferState? = null)

data class RulesUiState(
    val loading: Boolean = true,
    /** Null on the global rules screen. */
    val world: WorldOption? = null,
    val worlds: List<WorldOption> = emptyList(),
    val tab: RuleTab = RuleTab.Triggers,
    val triggerCount: Int = 0,
    val aliasCount: Int = 0,
    /** This tab's rows in the world box and the All worlds box. */
    val worldRows: List<RuleRow> = emptyList(),
    val globalRows: List<RuleRow> = emptyList(),
    val editor: RuleEditor? = null,
    val actions: RuleActions? = null,
    val confirmDelete: RuleRow? = null,
    val message: String? = null,
)

private data class Overlay(
    val tab: RuleTab = RuleTab.Triggers,
    val editor: RuleEditor? = null,
    val actions: RuleActions? = null,
    val confirmDelete: RuleRow? = null,
    val message: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RulesViewModel internal constructor(
    worldId: Long?,
    private val rules: RuleRepository,
    private val transfer: RuleTransfer,
    worlds: WorldRepository,
) : ViewModel() {
    // Route decoding needs a real Bundle, so JVM tests pass the id directly.
    @Inject constructor(
        savedStateHandle: SavedStateHandle,
        rules: RuleRepository,
        transfer: RuleTransfer,
        worlds: WorldRepository,
    ) : this(savedStateHandle.toRoute<RulesRoute>().worldId, rules, transfer, worlds)

    private val worldId = MutableStateFlow(worldId)
    private val overlay = MutableStateFlow(Overlay())
    private var lists = RuleLists()
    private var allWorlds = emptyList<World>()

    val state: StateFlow<RulesUiState> = combine(
        this.worldId.flatMapLatest { id -> rules.observeRules(id).map { id to it } },
        worlds.worlds,
        overlay,
    ) { (id, lists), worlds, overlay ->
        this.lists = lists
        allWorlds = worlds
        val world = worlds.firstOrNull { it.id == id }?.let { WorldOption(it.id, it.name) }
        val triggers = overlay.tab == RuleTab.Triggers
        RulesUiState(
            loading = false,
            world = world,
            worlds = worlds.map { WorldOption(it.id, it.name) },
            tab = overlay.tab,
            triggerCount = lists.globalTriggers.size + if (world != null) lists.worldTriggers.size else 0,
            aliasCount = lists.globalAliases.size + if (world != null) lists.worldAliases.size else 0,
            worldRows = when {
                world == null -> emptyList()
                triggers -> lists.worldTriggers.map { it.toRow() }
                else -> lists.worldAliases.map { it.toRow() }
            },
            globalRows = if (triggers) lists.globalTriggers.map { it.toRow() } else lists.globalAliases.map { it.toRow() },
            editor = overlay.editor,
            actions = overlay.actions,
            confirmDelete = overlay.confirmDelete,
            message = overlay.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RulesUiState())

    fun selectTab(tab: RuleTab) = overlay.update { it.copy(tab = tab) }

    /** Switches to another world's rules, or to only the global ones with null. */
    fun selectWorld(id: Long?) {
        overlay.update { Overlay(tab = it.tab) }
        worldId.value = id
    }

    fun setEnabled(row: RuleRow, enabled: Boolean) {
        viewModelScope.launch { rules.setEnabled(row.id, enabled) }
    }

    /** From a box's +: the new rule starts in that box's scope. */
    fun add(scope: Scope) = overlay.update {
        val editor = when (it.tab) {
            RuleTab.Triggers -> RuleEditor.EditTrigger(TriggerForm(scope = scope))
            RuleTab.Aliases -> RuleEditor.EditAlias(AliasForm(scope = scope, taken = takenNames(excluding = 0)))
        }
        it.copy(editor = editor)
    }

    fun edit(row: RuleRow) = overlay.update { it.copy(editor = editorFor(row, it.tab), actions = null) }

    fun updateTrigger(change: (TriggerForm) -> TriggerForm) = overlay.update {
        val editor = it.editor as? RuleEditor.EditTrigger ?: return@update it
        it.copy(editor = RuleEditor.EditTrigger(change(editor.form)))
    }

    fun updateAlias(change: (AliasForm) -> AliasForm) = overlay.update {
        val editor = it.editor as? RuleEditor.EditAlias ?: return@update it
        it.copy(editor = RuleEditor.EditAlias(change(editor.form)))
    }

    fun save() {
        val editor = overlay.value.editor ?: return
        when (editor) {
            is RuleEditor.EditTrigger -> if (!editor.form.canSave) return
            is RuleEditor.EditAlias -> if (!editor.form.canSave) return
        }
        overlay.update { it.copy(editor = null) }
        viewModelScope.launch {
            when (editor) {
                is RuleEditor.EditTrigger -> rules.save(editor.form.toTrigger())
                is RuleEditor.EditAlias -> rules.save(editor.form.toAlias())
            }
        }
    }

    fun dismissEditor() = overlay.update { it.copy(editor = null) }

    fun showActions(row: RuleRow) = overlay.update { it.copy(actions = RuleActions(row, it.tab)) }

    fun dismissActions() = overlay.update { it.copy(actions = null) }

    fun startTransfer() = overlay.update {
        val actions = it.actions ?: return@update it
        it.copy(actions = actions.copy(transfer = actions.transfer ?: TransferState()))
    }

    fun setTransferMode(mode: TransferMode) = updateTransfer { it.copy(mode = mode) }

    fun toggleDestination(scope: Scope) = updateTransfer {
        if (scope == overlay.value.actions?.row?.scope) it
        else it.copy(selected = if (scope in it.selected) it.selected - scope else it.selected + scope)
    }

    /** Applies the copy or move, first asking if any destination already has an alias with this name. */
    fun applyTransfer() {
        val actions = overlay.value.actions ?: return
        val t = actions.transfer ?: return
        if (t.selected.isEmpty() || t.working) return
        updateTransfer { it.copy(working = true) }
        viewModelScope.launch {
            val clashes = transfer.conflicts(actions.row.id, t.selected)
            if (clashes.isEmpty()) {
                finishTransfer(actions.row.id, t, OnConflict.Replace)
            } else {
                updateTransfer { it.copy(conflicts = clashes.map(::scopeName), working = false) }
            }
        }
    }

    /** The answer to the name clash prompt. Null cancels and goes back to the checklist. */
    fun resolveConflicts(onConflict: OnConflict?) {
        val actions = overlay.value.actions ?: return
        val t = actions.transfer ?: return
        if (onConflict == null) {
            updateTransfer { it.copy(conflicts = null) }
            return
        }
        updateTransfer { it.copy(conflicts = null, working = true) }
        viewModelScope.launch { finishTransfer(actions.row.id, t, onConflict) }
    }

    private suspend fun finishTransfer(id: Long, t: TransferState, onConflict: OnConflict) {
        val result = when (t.mode) {
            TransferMode.Copy -> transfer.copy(id, t.selected, onConflict)
            TransferMode.Move -> transfer.move(id, t.selected, onConflict)
        }
        val verb = if (t.mode == TransferMode.Copy) "Copied" else "Moved"
        val message = when {
            result == null -> null
            result.copiedTo.isEmpty() -> "Nothing ${verb.lowercase()}"
            else -> "$verb to ${places(result.copiedTo)}" +
                if (result.skipped.isEmpty()) "" else " · ${result.skipped.size} skipped"
        }
        overlay.update { it.copy(actions = null, message = message) }
    }

    fun requestDelete() = overlay.update { it.copy(confirmDelete = it.actions?.row) }

    fun cancelDelete() = overlay.update { it.copy(confirmDelete = null) }

    fun confirmDelete() {
        val row = overlay.value.confirmDelete ?: return
        overlay.update { it.copy(confirmDelete = null, actions = null) }
        viewModelScope.launch { rules.delete(row.id) }
    }

    fun messageShown() = overlay.update { it.copy(message = null) }

    private fun updateTransfer(change: (TransferState) -> TransferState) = overlay.update {
        val actions = it.actions ?: return@update it
        val t = actions.transfer ?: return@update it
        it.copy(actions = actions.copy(transfer = change(t)))
    }

    private fun editorFor(row: RuleRow, tab: RuleTab): RuleEditor? = when (tab) {
        RuleTab.Triggers -> (lists.worldTriggers + lists.globalTriggers).firstOrNull { it.id == row.id }
            ?.let { RuleEditor.EditTrigger(TriggerForm.of(it)) }
        RuleTab.Aliases -> (lists.worldAliases + lists.globalAliases).firstOrNull { it.id == row.id }
            ?.let { RuleEditor.EditAlias(AliasForm.of(it, takenNames(excluding = it.id))) }
    }

    private fun takenNames(excluding: Long): Map<Scope, Set<String>> =
        (lists.worldAliases + lists.globalAliases)
            .filter { it.id != excluding }
            .groupBy({ it.scope }, { it.name })
            .mapValues { it.value.toSet() }

    private fun scopeName(scope: Scope) = when (scope) {
        Scope.Global -> "All worlds"
        is Scope.World -> allWorlds.firstOrNull { it.id == scope.worldId }?.name ?: "Deleted world"
    }

    private fun places(scopes: List<Scope>) =
        if (scopes.size == 1) scopeName(scopes.single()) else "${scopes.size} worlds"
}

private fun Trigger.toRow() = RuleRow(id, scope, pattern, summary(), group, enabled)

private fun Alias.toRow() = RuleRow(id, scope, name, summary(), group, enabled)
