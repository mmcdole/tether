package com.mmcdole.tether.ui.rules

import com.mmcdole.tether.data.rules.FakeRuleDao
import com.mmcdole.tether.data.rules.OnConflict
import com.mmcdole.tether.data.rules.RuleRepository
import com.mmcdole.tether.data.rules.RuleTransfer
import com.mmcdole.tether.data.world.FakeWorldDao
import com.mmcdole.tether.data.world.World
import com.mmcdole.tether.data.world.WorldRepository
import com.mmcdole.tether.data.world.testSecrets
import com.mmcdole.tether.mud.rules.Alias
import com.mmcdole.tether.mud.rules.Scope
import com.mmcdole.tether.mud.rules.Trigger
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RulesViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC)
    private val ruleDao = FakeRuleDao()
    private val rules = RuleRepository(ruleDao, clock, dispatcher)
    private val worlds = WorldRepository(FakeWorldDao(), testSecrets(), clock, dispatcher)
    private var thorin = 0L
    private var alt = 0L
    private var bard = 0L

    @Before
    fun setUp() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        thorin = worlds.save(World(name = "Thorin", host = "h", port = 1))
        alt = worlds.save(World(name = "Alt", host = "h", port = 1))
        bard = worlds.save(World(name = "Bard", host = "h", port = 1))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(worldId: Long?) = RulesViewModel(worldId, rules, RuleTransfer(ruleDao, clock), worlds).also { vm ->
        backgroundScope.launch { vm.state.collect {} }
    }

    private fun aliasesIn(worldId: Long?) =
        ruleDao.rows.value.values.filter { it.worldId == worldId && it.pattern == "k" }.map { it.body }

    @Test
    fun boxesSplitWorldAndGlobalRulesWithCounts() = runTest(dispatcher) {
        rules.save(Trigger(0, Scope.World(thorin), "The %1 slashes you.", group = "Combat", highlight = HighlightColor.Red.rgb, vibrate = true))
        rules.save(Trigger(0, Scope.World(alt), "elsewhere", gag = true))
        rules.save(Trigger(0, Scope.Global, "You are hungry.", send = "eat bread"))
        rules.save(Alias(0, Scope.Global, "k", "kill %1"))

        val vm = viewModel(thorin)
        val state = vm.state.value
        assertEquals("Thorin", state.world?.name)
        assertEquals(2, state.triggerCount)
        assertEquals(1, state.aliasCount)
        assertEquals(listOf("Highlight red · vibrate"), state.worldRows.map { it.summary })
        assertEquals(listOf("eat bread"), state.globalRows.map { it.summary })

        vm.selectTab(RuleTab.Aliases)
        assertEquals(emptyList<RuleRow>(), vm.state.value.worldRows)
        assertEquals(listOf("k"), vm.state.value.globalRows.map { it.title })
    }

    @Test
    fun globalScreenAndWorldSwitcher() = runTest(dispatcher) {
        rules.save(Trigger(0, Scope.World(alt), "alt only", gag = true))
        rules.save(Trigger(0, Scope.Global, "everywhere", gag = true))

        val vm = viewModel(null)
        assertNull(vm.state.value.world)
        assertEquals(1, vm.state.value.triggerCount)
        assertEquals(listOf("Thorin", "Alt", "Bard"), vm.state.value.worlds.map { it.name })

        vm.selectWorld(alt)
        assertEquals("Alt", vm.state.value.world?.name)
        assertEquals(listOf("alt only"), vm.state.value.worldRows.map { it.title })
        assertEquals(2, vm.state.value.triggerCount)
    }

    @Test
    fun plusStartsInThatBoxsScopeAndSaveApplies() = runTest(dispatcher) {
        val vm = viewModel(thorin)
        vm.add(Scope.Global)
        assertEquals(Scope.Global, (vm.state.value.editor as RuleEditor.EditTrigger).form.scope)
        vm.dismissEditor()

        vm.add(Scope.World(thorin))
        assertEquals(Scope.World(thorin), (vm.state.value.editor as RuleEditor.EditTrigger).form.scope)
        vm.updateTrigger { it.copy(pattern = "The fountain gurgles.") }
        vm.save()
        // Nothing to do yet, so it stays open and unsaved.
        assertTrue(vm.state.value.editor != null)
        vm.updateTrigger { it.copy(gag = true, highlight = HighlightColor.Yellow.rgb) }
        vm.save()

        assertNull(vm.state.value.editor)
        val row = vm.state.value.worldRows.single()
        assertEquals("Gag · highlight yellow", row.summary)

        vm.selectTab(RuleTab.Aliases)
        vm.add(Scope.World(thorin))
        assertEquals(Scope.World(thorin), (vm.state.value.editor as RuleEditor.EditAlias).form.scope)
    }

    @Test
    fun editKeepsIdAndToggleDims() = runTest(dispatcher) {
        val id = rules.save(Trigger(0, Scope.World(thorin), "x", gag = true))
        val vm = viewModel(thorin)
        vm.edit(vm.state.value.worldRows.single())
        vm.updateTrigger { it.copy(pattern = "y", scope = Scope.Global) }
        vm.save()

        assertEquals(emptyList<RuleRow>(), vm.state.value.worldRows)
        val row = vm.state.value.globalRows.single()
        assertEquals(id, row.id)
        assertEquals("y", row.title)

        vm.setEnabled(row, false)
        assertFalse(vm.state.value.globalRows.single().enabled)
    }

    @Test
    fun newAliasCannotReuseANameInTheSameScope() = runTest(dispatcher) {
        rules.save(Alias(0, Scope.World(thorin), "k", "kill %1"))
        val vm = viewModel(thorin)
        vm.selectTab(RuleTab.Aliases)
        vm.add(Scope.World(thorin))
        vm.updateAlias { it.copy(name = "k", expansion = "kick %1") }
        assertEquals("Already used", (vm.state.value.editor as RuleEditor.EditAlias).form.nameError)
        vm.updateAlias { it.copy(scope = Scope.Global) }
        assertTrue((vm.state.value.editor as RuleEditor.EditAlias).form.canSave)

        // Editing the existing one doesn't clash with itself.
        vm.dismissEditor()
        vm.edit(vm.state.value.worldRows.single())
        assertTrue((vm.state.value.editor as RuleEditor.EditAlias).form.canSave)
    }

    @Test
    fun copyTriggerToTwoWorlds() = runTest(dispatcher) {
        rules.save(Trigger(0, Scope.World(thorin), "The %1 slashes you.", vibrate = true))
        val vm = viewModel(thorin)
        val row = vm.state.value.worldRows.single()
        vm.showActions(row)
        vm.startTransfer()
        vm.toggleDestination(Scope.World(thorin)) // current scope can't be picked
        vm.toggleDestination(Scope.World(alt))
        vm.toggleDestination(Scope.World(bard))
        assertEquals(setOf(Scope.World(alt), Scope.World(bard)), vm.state.value.actions!!.transfer!!.selected)

        vm.applyTransfer()
        assertNull(vm.state.value.actions)
        assertEquals("Copied to 2 worlds", vm.state.value.message)
        assertEquals(3, ruleDao.rows.value.size)
        vm.messageShown()
        assertNull(vm.state.value.message)
    }

    @Test
    fun aliasClashAsksThenReplaceOrSkip() = runTest(dispatcher) {
        rules.save(Alias(0, Scope.World(thorin), "k", "kill %1"))
        rules.save(Alias(0, Scope.World(alt), "k", "kick %1"))
        val vm = viewModel(thorin)
        vm.selectTab(RuleTab.Aliases)
        val row = vm.state.value.worldRows.single()

        fun pick() {
            vm.showActions(row)
            vm.startTransfer()
            vm.toggleDestination(Scope.World(alt))
            vm.toggleDestination(Scope.World(bard))
            vm.applyTransfer()
        }

        pick()
        assertEquals(listOf("Alt"), vm.state.value.actions!!.transfer!!.conflicts)
        assertEquals(listOf("kick %1"), aliasesIn(alt)) // nothing applied yet

        vm.resolveConflicts(null)
        assertNull(vm.state.value.actions!!.transfer!!.conflicts)
        vm.dismissActions()

        pick()
        vm.resolveConflicts(OnConflict.Skip)
        assertEquals("Copied to Bard · 1 skipped", vm.state.value.message)
        assertEquals(listOf("kick %1"), aliasesIn(alt))
        assertEquals(listOf("kill %1"), aliasesIn(bard))

        pick()
        vm.resolveConflicts(OnConflict.Replace)
        assertEquals("Copied to 2 worlds", vm.state.value.message)
        assertEquals(listOf("kill %1"), aliasesIn(alt))
        assertEquals(listOf("kill %1"), aliasesIn(bard))
    }

    @Test
    fun moveToAllWorlds() = runTest(dispatcher) {
        rules.save(Trigger(0, Scope.World(thorin), "x", gag = true))
        val vm = viewModel(thorin)
        vm.showActions(vm.state.value.worldRows.single())
        vm.startTransfer()
        vm.setTransferMode(TransferMode.Move)
        vm.toggleDestination(Scope.Global)
        vm.applyTransfer()

        assertEquals("Moved to All worlds", vm.state.value.message)
        assertEquals(emptyList<RuleRow>(), vm.state.value.worldRows)
        assertEquals(listOf("x"), vm.state.value.globalRows.map { it.title })
    }

    @Test
    fun deleteAsksFirst() = runTest(dispatcher) {
        rules.save(Trigger(0, Scope.Global, "x", gag = true))
        val vm = viewModel(null)
        val row = vm.state.value.globalRows.single()
        vm.showActions(row)
        vm.requestDelete()
        assertEquals(row, vm.state.value.confirmDelete)
        vm.cancelDelete()
        assertEquals(1, vm.state.value.globalRows.size)

        vm.requestDelete()
        vm.confirmDelete()
        assertNull(vm.state.value.actions)
        assertEquals(emptyList<RuleRow>(), vm.state.value.globalRows)
    }
}
