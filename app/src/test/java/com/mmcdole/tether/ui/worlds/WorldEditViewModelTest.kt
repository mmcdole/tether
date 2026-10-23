package com.mmcdole.tether.ui.worlds

import com.mmcdole.tether.data.world.FakeWorldDao
import com.mmcdole.tether.data.world.PasswordEdit
import com.mmcdole.tether.data.world.World
import com.mmcdole.tether.data.world.WorldRepository
import com.mmcdole.tether.data.world.testSecrets
import java.time.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorldEditViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val dao = FakeWorldDao()
    private val repository = WorldRepository(dao, testSecrets(), Clock.systemUTC(), dispatcher)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(id: Long?) = WorldEditViewModel(id, repository).also { vm ->
        backgroundScope.launch { vm.state.collect {} }
    }

    @Test
    fun invalidFormShowsErrorsAndSavesNothing() = runTest(dispatcher) {
        val vm = viewModel(null)
        vm.onHost("bad host")
        vm.onPort("70000")
        vm.save()

        val state = vm.state.value
        assertEquals("Required", state.nameError)
        assertEquals("Invalid host", state.hostError)
        assertEquals("1–65535", state.portError)
        assertFalse(state.done)
        assertTrue(dao.rows.value.isEmpty())

        vm.onName("Test MUD")
        assertNull(vm.state.value.nameError)
    }

    @Test
    fun portAcceptsDigitsOnly() = runTest(dispatcher) {
        val vm = viewModel(null)
        vm.onPort("40a")
        vm.onPort("4000")
        vm.onPort("400000")
        assertEquals("4000", vm.state.value.port)
    }

    @Test
    fun savesNewWorldTrimmed() = runTest(dispatcher) {
        val vm = viewModel(null)
        vm.onName(" Test MUD ")
        vm.onHost(" localhost ")
        vm.onPort("7680")
        vm.onTls(true)
        vm.onUsername("  ")
        vm.save()

        assertTrue(vm.state.value.done)
        val world = repository.worlds.first().single()
        assertEquals(World(id = world.id, name = "Test MUD", host = "localhost", port = 7680, tls = true), world)
    }

    @Test
    fun editLoadsWorldAndKeepsSavedPasswordWhenLeftEmpty() = runTest(dispatcher) {
        val id = repository.save(World(name = "Viking", host = "host.example", port = 2001, username = "thorin"), PasswordEdit.Set("hunter2"))
        val vm = viewModel(id)

        val loaded = vm.state.value
        assertEquals("Viking", loaded.name)
        assertEquals("2001", loaded.port)
        assertEquals("thorin", loaded.username)
        assertTrue(loaded.hasSavedPassword)
        assertEquals("", loaded.password)

        vm.onPort("2002")
        vm.save()
        assertEquals(2002, repository.get(id)!!.port)
        assertEquals("hunter2", repository.password(id))
    }

    @Test
    fun clearingPasswordRemovesIt() = runTest(dispatcher) {
        val id = repository.save(World(name = "Viking", host = "host.example", port = 2001), PasswordEdit.Set("hunter2"))
        val vm = viewModel(id)

        vm.onClearPassword()
        vm.save()
        assertNull(repository.password(id))
    }

    @Test
    fun deleteRemovesWorld() = runTest(dispatcher) {
        val id = repository.save(World(name = "Viking", host = "host.example", port = 2001))
        val vm = viewModel(id)

        vm.delete()
        assertTrue(vm.state.value.done)
        assertNull(repository.get(id))
    }
}
