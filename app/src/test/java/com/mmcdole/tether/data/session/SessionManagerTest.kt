package com.mmcdole.tether.data.session

import com.mmcdole.tether.data.settings.SessionOptions
import com.mmcdole.tether.data.world.FakeWorldDao
import com.mmcdole.tether.data.world.PasswordEdit
import com.mmcdole.tether.data.world.World
import com.mmcdole.tether.data.world.WorldRepository
import com.mmcdole.tether.data.world.testSecrets
import com.mmcdole.tether.mud.text.LineKind
import com.mmcdole.tether.mud.text.StyledLine
import java.time.Clock
import java.util.zip.Deflater
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Real dispatchers: the read loop blocks on a stream, so these wait on real time with timeouts.
class SessionManagerTest {
    private val server = FakeMudServer()
    private val dao = FakeWorldDao()
    private val repository = WorldRepository(dao, testSecrets(), Clock.systemUTC(), Dispatchers.IO)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val plainWorld = World(id = 1, name = "Test MUD", host = "localhost", port = 4000)

    private val network = FakeNetworkMonitor()
    private val clock = AdvancingClock()

    private fun manager(
        factory: MudSocketFactory = server.factory,
        retryDelay: Duration = 20.milliseconds,
        options: SessionOptions = SessionOptions(),
    ) = SessionManager(
        repository, factory, network, ReconnectPolicy(listOf(retryDelay)), FixedSessionOptions(options),
        scope, Dispatchers.IO, Dispatchers.Default, clock,
    )

    private suspend fun SessionManager.awaitState(predicate: (SessionState) -> Boolean): SessionState =
        withTimeout(2_000) { state.first(predicate) }

    @After
    fun tearDown() = scope.cancel()

    private suspend fun SessionManager.awaitLines(predicate: (List<StyledLine>) -> Boolean): List<StyledLine> =
        withTimeout(2_000) { scrollback.first { predicate(it.lines) }.lines }

    private suspend fun SessionManager.awaitTexts(vararg texts: String): List<StyledLine> =
        awaitLines { lines -> lines.map { it.text } == texts.toList() }

    private suspend fun SessionManager.connected(world: World = plainWorld) = also {
        connect(world)
        withTimeout(2_000) { state.first { it is SessionState.Connected } }
    }

    @Test
    fun partialPromptUpdatesInPlaceAndGaFinalizesIt() = runBlocking<Unit> {
        val manager = manager().connected()
        server.send("Welcome\r\nHP:10")
        val partial = manager.awaitTexts("Welcome", "HP:10").last()
        assertEquals(LineKind.Output, partial.kind)

        server.send(" SP:5 >")
        assertEquals(partial.id, manager.awaitTexts("Welcome", "HP:10 SP:5 >").last().id)

        server.send(GA)
        val prompt = manager.awaitLines { it.last().kind == LineKind.Prompt }.last()
        assertEquals(partial.id, prompt.id)
        assertEquals("HP:10 SP:5 >", prompt.text)
    }

    @Test
    fun commandAfterPromptIsAppendedToIt() = runBlocking<Unit> {
        val manager = manager().connected()
        server.send("HP:10 > ")
        manager.awaitTexts("HP:10 > ")

        manager.send("look")
        assertEquals("look", server.awaitLine())
        val line = manager.awaitTexts("HP:10 > look").single()
        assertEquals(LineKind.Prompt, line.kind)
        assertEquals(8, line.echoStart)

        // The server's answer starts on its own line.
        server.send("A dark room.\r\n")
        manager.awaitTexts("HP:10 > look", "A dark room.")
    }

    @Test
    fun splitCommandsAttachFirstToGaPromptAndEchoTheRest() = runBlocking<Unit> {
        val manager = manager().connected()
        server.send("HP > ".toByteArray() + GA)
        manager.awaitLines { it.lastOrNull()?.kind == LineKind.Prompt }

        manager.send("n;e")
        assertEquals("n", server.awaitLine())
        assertEquals("e", server.awaitLine())
        val lines = manager.awaitTexts("HP > n", "e")
        assertEquals(listOf(LineKind.Prompt, LineKind.Echo), lines.map { it.kind })
        assertEquals(listOf("n;e"), manager.history.value)
    }

    @Test
    fun blankLineRightAfterGaPromptIsDropped() = runBlocking<Unit> {
        val manager = manager().connected()
        server.send("HP > ".toByteArray() + GA + "\r\nHagbard tells you: hi\r\n".toByteArray())
        manager.awaitTexts("HP > ", "Hagbard tells you: hi")
    }

    @Test
    fun passwordTypedWhileServerEchoesIsNeverShownOrRemembered() = runBlocking<Unit> {
        val manager = manager().connected()
        server.send("Password: ".toByteArray() + WILL_ECHO)
        withTimeout(2_000) { manager.serverEcho.first { it } }

        manager.send("hunter2")
        assertEquals("hunter2", server.awaitLine())
        server.send(WONT_ECHO + "\r\nWelcome back.\r\n".toByteArray())
        withTimeout(2_000) { manager.serverEcho.first { !it } }

        val lines = manager.awaitTexts("Password: ", "Welcome back.")
        assertFalse(lines.any { "hunter2" in it.text })
        assertTrue(manager.history.value.isEmpty())
    }

    @Test
    fun autoLoginSendsUsernameAfterOutputThenPasswordAfterNextOutput() = runBlocking<Unit> {
        val id = repository.save(plainWorld.copy(id = 0, username = "drake"), PasswordEdit.Set("hunter2"))
        val world = repository.get(id)!!
        val manager = manager().connected(world)
        assertTrue("nothing before the MUD speaks", server.noMoreLines())

        server.send("By what name do you wish to be known? ")
        assertEquals("drake", server.awaitLine())
        manager.awaitTexts("By what name do you wish to be known? drake")

        server.send("Password: ".toByteArray() + WILL_ECHO)
        assertEquals("hunter2", server.awaitLine())
        server.send(WONT_ECHO + "\r\nWelcome!\r\n".toByteArray())

        val lines = manager.awaitTexts("By what name do you wish to be known? drake", "Password: ", "Welcome!")
        assertFalse(lines.any { "hunter2" in it.text })
        assertTrue(manager.history.value.isEmpty())
        assertNotNull(dao.get(id)!!.lastConnectedAt)
    }

    @Test
    fun refusedConnectionShowsSystemLine() = runBlocking<Unit> {
        val manager = manager(server.refusing)
        manager.connect(plainWorld)
        val line = manager.awaitLines { it.isNotEmpty() }.single()
        assertEquals("Couldn't connect: Connection refused", line.text)
        assertEquals(LineKind.System, line.kind)
        assertEquals(SessionState.Idle, manager.state.value)
    }

    @Test
    fun serverClosingEndsTheSessionWhenAutoReconnectIsOff() = runBlocking<Unit> {
        val manager = manager(options = SessionOptions(autoReconnect = false)).connected()
        server.send("HP > ")
        manager.awaitTexts("HP > ")
        server.close()
        val lines = manager.awaitTexts("HP > ", "Disconnected")
        assertEquals(LineKind.System, lines.last().kind)
        assertEquals(SessionState.Idle, manager.state.value)
    }

    @Test
    fun userDisconnectEndsTheSessionOnce() = runBlocking<Unit> {
        val manager = manager().connected()
        server.send("Hello\r\n")
        manager.awaitTexts("Hello")
        manager.disconnect()
        manager.disconnect()
        manager.awaitTexts("Hello", "Disconnected")
        assertEquals(SessionState.Idle, manager.state.value)
        assertTrue(server.noMoreLines())
        assertEquals(listOf("Hello", "Disconnected"), manager.scrollback.value.lines.map { it.text })
    }

    @Test
    fun serverCloseReconnectsReplaysAutoLoginAndKeepsScrollbackAndHistory() = runBlocking<Unit> {
        val id = repository.save(plainWorld.copy(id = 0, username = "drake"), PasswordEdit.Set("hunter2"))
        val world = repository.get(id)!!
        val manager = manager().connected(world)
        login(manager)
        manager.send("look")
        assertEquals("look", server.awaitLine())
        server.send("A dark room.\r\n")
        manager.awaitLines { lines -> lines.any { it.text == "A dark room." } }
        clock.advance(10.seconds)

        server.close()
        assertEquals(SessionState.Reconnecting(world, 1), manager.awaitState { it is SessionState.Reconnecting })
        manager.awaitState { it is SessionState.Connected }
        assertEquals(2, server.opened)
        login(manager)

        val lines = manager.scrollback.value.lines
        val texts = lines.map { it.text }
        val dropped = texts.indexOf("Disconnected")
        assertTrue("old lines kept", texts.indexOf("A dark room.") in 0 until dropped)
        assertEquals("Reconnected", texts[dropped + 1])
        assertEquals(LineKind.System, lines[dropped + 1].kind)
        assertEquals(listOf("look"), manager.history.value)
    }

    @Test
    fun serverClosingRightAfterACommandIsTakenAsQuit() = runBlocking<Unit> {
        val manager = manager().connected()
        manager.send("quit")
        assertEquals("quit", server.awaitLine())
        server.close()
        manager.awaitTexts("quit", "Disconnected")
        assertEquals(SessionState.Idle, manager.state.value)
        delay(100)
        assertEquals(1, server.opened)
    }

    @Test
    fun failedAttemptsKeepRetryingUntilTheServerIsBack() = runBlocking<Unit> {
        val manager = manager().connected()
        server.down = true
        server.close()
        manager.awaitState { it is SessionState.Reconnecting && it.attempt >= 3 }
        server.down = false
        manager.awaitState { it is SessionState.Connected }
        assertEquals(listOf("Disconnected", "Reconnected"), manager.scrollback.value.lines.map { it.text })
    }

    @Test
    fun userDisconnectDuringBackoffCancelsReconnecting() = runBlocking<Unit> {
        val manager = manager(retryDelay = 1.seconds).connected()
        server.close()
        manager.awaitState { it is SessionState.Reconnecting }
        manager.disconnect()
        manager.awaitState { it == SessionState.Idle }
        delay(1_500)
        assertEquals(SessionState.Idle, manager.state.value)
        assertEquals(1, server.opened)
        assertEquals(listOf("Disconnected", "Disconnected"), manager.scrollback.value.lines.map { it.text })
    }

    @Test
    fun losingTheNetworkDropsAndItsReturnCutsTheWaitShort() = runBlocking<Unit> {
        val manager = manager(retryDelay = 60.seconds).connected()
        network.emit(NetworkEvent.Lost(2))
        delay(100)
        assertTrue("another network going away is ignored", manager.state.value is SessionState.Connected)

        network.current = null
        network.emit(NetworkEvent.Lost(1))
        manager.awaitState { it is SessionState.Reconnecting }
        manager.awaitTexts("Disconnected: Network lost")

        network.current = 2
        network.emit(NetworkEvent.Available(2))
        manager.awaitState { it is SessionState.Connected }
        manager.awaitTexts("Disconnected: Network lost", "Reconnected")
    }

    @Test
    fun keepaliveSendsNopAfterSilence() = runBlocking<Unit> {
        val conn = MudConnection.open(server.factory, "localhost", 4000, false, Dispatchers.IO)
        val nop = listOf(IAC.toByte(), 241.toByte())
        try {
            kotlinx.coroutines.coroutineScope {
                val writer = launch { conn.runWriter() }
                val keepalive = launch { conn.runKeepalive(100.milliseconds) }
                withTimeout(2_000) { while (nop !in server.writtenBytes().toList().windowed(2)) delay(10) }
                keepalive.cancel()
                writer.cancel()
            }
        } finally {
            conn.close()
        }
    }

    private suspend fun login(manager: SessionManager) {
        server.send("By what name do you wish to be known? ")
        assertEquals("drake", server.awaitLine())
        server.send("Password: ".toByteArray() + WILL_ECHO)
        assertEquals("hunter2", server.awaitLine())
        server.send(WONT_ECHO + "\r\nWelcome!\r\n".toByteArray())
        withTimeout(2_000) { manager.serverEcho.first { !it } }
    }

    @Test
    fun mccp2StreamIsInflatedAndEndsCleanly() = runBlocking<Unit> {
        val manager = manager().connected()
        server.send(bytes(IAC, WILL, MCCP2))
        val start = bytes(IAC, SB, MCCP2, IAC, SE)
        val deflater = Deflater()
        deflater.setInput("Compressed hello\r\n".toByteArray())
        deflater.finish()
        val buffer = ByteArray(256)
        val compressed = buffer.copyOf(deflater.deflate(buffer))
        server.send(start + compressed + "Plain again\r\n".toByteArray())

        manager.awaitTexts("Compressed hello", "Plain again")
        val reply = bytes(IAC, DO, MCCP2).toList()
        withTimeout(2_000) { while (reply !in server.writtenBytes().toList().windowed(3)) delay(10) }
    }

    @Test
    fun mccp2StartingAtTheEndOfAReadInflatesTheNextOnes() = runBlocking<Unit> {
        val manager = manager().connected()
        server.send(bytes(IAC, WILL, MCCP2))
        server.send(bytes(IAC, SB, MCCP2, IAC, SE))
        val deflater = Deflater()
        val buffer = ByteArray(256)
        for (text in listOf("One\r\n", "Two\r\n")) {
            deflater.setInput(text.toByteArray())
            server.send(buffer.copyOf(deflater.deflate(buffer, 0, buffer.size, Deflater.SYNC_FLUSH)))
        }
        manager.awaitTexts("One", "Two")
    }

    private companion object {
        const val IAC = 255
        const val WILL = 251
        const val WONT = 252
        const val DO = 253
        const val SB = 250
        const val SE = 240
        const val MCCP2 = 86
        fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
        val GA = bytes(IAC, 249)
        val WILL_ECHO = bytes(IAC, WILL, 1)
        val WONT_ECHO = bytes(IAC, WONT, 1)
    }
}
