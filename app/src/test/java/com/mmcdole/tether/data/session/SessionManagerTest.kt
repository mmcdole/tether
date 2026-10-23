package com.mmcdole.tether.data.session

import com.mmcdole.tether.data.rules.FakeRuleDao
import com.mmcdole.tether.data.rules.RuleRepository
import com.mmcdole.tether.data.settings.SessionOptions
import com.mmcdole.tether.data.world.FakeWorldDao
import com.mmcdole.tether.data.world.PasswordEdit
import com.mmcdole.tether.data.world.World
import com.mmcdole.tether.data.world.WorldRepository
import com.mmcdole.tether.data.world.testSecrets
import com.mmcdole.tether.mud.rules.Alias
import com.mmcdole.tether.mud.rules.Scope
import com.mmcdole.tether.mud.rules.Trigger
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Real dispatchers: the read loop blocks on a stream, so these wait on real time with timeouts.
class SessionManagerTest {
    private val server = FakeMudServer()
    private val dao = FakeWorldDao()
    private val repository = WorldRepository(dao, testSecrets(), Clock.systemUTC(), Dispatchers.IO)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val plainWorld = World(id = 1, name = "Test MUD", host = "localhost", port = 4000)

    private val rules = RuleRepository(FakeRuleDao(), Clock.systemUTC(), Dispatchers.IO)
    private val alerts = FakeTriggerAlerts()
    private val thisWorld = Scope.World(plainWorld.id)
    private val network = FakeNetworkMonitor()
    private val clock = AdvancingClock()

    private fun manager(
        factory: MudSocketFactory = server.factory,
        retryDelay: Duration = 20.milliseconds,
        options: SessionOptions = SessionOptions(),
        maxAttempts: Int = ReconnectPolicy.MAX_ATTEMPTS,
        source: FixedSessionOptions = FixedSessionOptions(options),
    ) = SessionManager(
        repository, rules, factory, network, ReconnectPolicy(listOf(retryDelay), maxAttempts), source,
        alerts, scope, Dispatchers.IO, Dispatchers.Default, clock,
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
    fun serverClosingCleanlyEndsTheSessionWithoutReconnecting() = runBlocking<Unit> {
        val manager = manager().connected()
        server.send("HP > ")
        manager.awaitTexts("HP > ")
        server.close()
        val lines = manager.awaitTexts("HP > ", "Disconnected")
        assertEquals(LineKind.System, lines.last().kind)
        assertEquals(SessionState.Idle, manager.state.value)
        delay(100)
        assertEquals(SessionState.Idle, manager.state.value)
        assertEquals(1, server.attempts)
    }

    @Test
    fun resetEndsTheSessionWhenAutoReconnectIsOff() = runBlocking<Unit> {
        val manager = manager(options = SessionOptions(autoReconnect = false)).connected()
        server.reset()
        manager.awaitTexts("Disconnected: Connection reset")
        assertEquals(SessionState.Idle, manager.state.value)
        delay(100)
        assertEquals(1, server.attempts)
    }

    @Test
    fun scrollbackSizeChangesApplyToTheLiveSession() = runBlocking<Unit> {
        val source = FixedSessionOptions(SessionOptions(scrollbackLines = 5))
        val manager = manager(source = source).connected()
        server.send((1..5).joinToString("") { "line $it\r\n" })
        manager.awaitLines { it.size == 5 }

        source.options.value = SessionOptions(scrollbackLines = 2)
        manager.awaitTexts("line 4", "line 5")
        source.options.value = SessionOptions(scrollbackLines = 3)
        server.send("line 6\r\nline 7\r\n")
        manager.awaitTexts("line 5", "line 6", "line 7")
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
    fun resetReconnectsReplaysAutoLoginAndKeepsScrollbackAndHistory() = runBlocking<Unit> {
        val id = repository.save(plainWorld.copy(id = 0, username = "drake"), PasswordEdit.Set("hunter2"))
        val world = repository.get(id)!!
        val manager = manager().connected(world)
        login(manager)
        manager.send("look")
        assertEquals("look", server.awaitLine())
        server.send("A dark room.\r\n")
        manager.awaitLines { lines -> lines.any { it.text == "A dark room." } }
        clock.advance(10.seconds)

        server.reset()
        assertEquals(SessionState.Reconnecting(world, 1), manager.awaitState { it is SessionState.Reconnecting })
        manager.awaitState { it is SessionState.Connected }
        assertEquals(2, server.opened)
        login(manager)

        val lines = manager.scrollback.value.lines
        val texts = lines.map { it.text }
        val dropped = texts.indexOf("Disconnected: Connection reset")
        assertTrue("old lines kept", texts.indexOf("A dark room.") in 0 until dropped)
        assertEquals("Reconnected", texts[dropped + 1])
        assertEquals(LineKind.System, lines[dropped + 1].kind)
        assertEquals(listOf("look"), manager.history.value)
    }

    @Test
    fun failedAttemptsKeepRetryingUntilTheServerIsBack() = runBlocking<Unit> {
        val manager = manager().connected()
        server.down = true
        server.reset()
        manager.awaitState { it is SessionState.Reconnecting && it.attempt >= 3 }
        server.down = false
        manager.awaitState { it is SessionState.Connected }
        assertEquals(listOf("Disconnected: Connection reset", "Reconnected"), manager.scrollback.value.lines.map { it.text })
    }

    @Test
    fun givesUpAfterTenFailedAttemptsAndASuccessResetsTheCount() = runBlocking<Unit> {
        val manager = manager(retryDelay = 10.milliseconds).connected()
        server.down = true
        server.reset()
        manager.awaitState { it is SessionState.Reconnecting && it.attempt >= 5 }
        server.down = false
        manager.awaitState { it is SessionState.Connected }

        val before = server.attempts
        server.down = true
        server.reset()
        manager.awaitLines { it.lastOrNull()?.text == "Couldn't reconnect" }
        assertEquals(SessionState.Idle, manager.state.value)
        assertEquals(10, server.attempts - before)
        delay(100)
        assertEquals(SessionState.Idle, manager.state.value)
    }

    @Test
    fun userDisconnectDuringBackoffCancelsReconnecting() = runBlocking<Unit> {
        val manager = manager(retryDelay = 1.seconds).connected()
        server.reset()
        manager.awaitState { it is SessionState.Reconnecting }
        manager.disconnect()
        manager.awaitState { it == SessionState.Idle }
        delay(1_500)
        assertEquals(SessionState.Idle, manager.state.value)
        assertEquals(1, server.opened)
        assertEquals(listOf("Disconnected: Connection reset", "Disconnected"), manager.scrollback.value.lines.map { it.text })
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

    private fun List<StyledLine>.systemTexts() = filter { it.kind == LineKind.System }.map { it.text }

    @Test
    fun gagDropsTheLine() = runBlocking<Unit> {
        rules.save(Trigger(0, thisWorld, "The fountain gurgles.", gag = true))
        val manager = manager().connected()
        server.send("North.\r\nThe fountain gurgles.\r\nSouth.\r\n")
        manager.awaitTexts("North.", "South.")
    }

    @Test
    fun highlightIsSetAndWorldAndGlobalTriggersBothFire() = runBlocking<Unit> {
        rules.save(Trigger(0, Scope.Global, "%1 tells you: %2", highlight = 0xE2A6F5))
        rules.save(Trigger(0, thisWorld, "tells you", vibrate = true))
        val manager = manager().connected()
        server.send("Hagbard tells you: hi\r\nThe rat dies.\r\n")
        val lines = manager.awaitTexts("Hagbard tells you: hi", "The rat dies.")
        assertEquals(0xE2A6F5, lines[0].highlight)
        assertNull(lines[1].highlight)
        assertEquals(1, alerts.vibrations.get())
    }

    @Test
    fun triggerSendGoesOutThroughAliasExpansionAndShowsDim() = runBlocking<Unit> {
        rules.save(Alias(0, thisWorld, "k", "kill %1"))
        rules.save(Trigger(0, thisWorld, "The %1 attacks you.", send = "k %1;grin"))
        val manager = manager().connected()
        server.send("The rat attacks you.\r\n")
        assertEquals("kill rat", server.awaitLine())
        assertEquals("grin", server.awaitLine())
        val lines = manager.awaitTexts("The rat attacks you.", "k rat", "grin")
        assertEquals(listOf(LineKind.Output, LineKind.Echo, LineKind.Echo), lines.map { it.kind })
    }

    @Test
    fun worldAliasBeatsGlobalAlias() = runBlocking<Unit> {
        rules.save(Alias(0, Scope.Global, "k", "kick %1"))
        rules.save(Alias(0, thisWorld, "k", "kill %1"))
        val manager = manager().connected()
        manager.send("k rat")
        assertEquals("kill rat", server.awaitLine())
        // The echo is what was typed.
        manager.awaitTexts("k rat")
        assertEquals(listOf("k rat"), manager.history.value)
    }

    @Test
    fun aliasTypedMidSessionAppliesToTheNextCommand() = runBlocking<Unit> {
        val manager = manager().connected()
        manager.send("#alias k {say %1}")
        manager.send("k hi")
        assertEquals("say hi", server.awaitLine())
        val lines = manager.awaitTexts("#alias k {say %1}", "#alias k = say %1", "k hi")
        assertEquals(listOf(LineKind.Echo, LineKind.System, LineKind.Echo), lines.map { it.kind })
        assertTrue("the # command itself never reaches the MUD", server.noMoreLines())

        // Later commands on the same line see it too.
        manager.send("#alias g {grin};g")
        assertEquals("grin", server.awaitLine())
    }

    @Test
    fun hashCommandsShowOneSystemLineEach() = runBlocking<Unit> {
        val manager = manager().connected()
        manager.send("#galias l look")
        manager.send("#action {The %1 slashes you.} {flee}")
        manager.send("#gag {The fountain gurgles.}")
        manager.send("#ggag spam")
        manager.send("#alias")
        manager.send("#action")
        manager.send("#alias k")
        manager.send("#bogus")
        val expected = listOf(
            "#alias l = look (all worlds)",
            "#action {The %1 slashes you.} = flee",
            "#gag {The fountain gurgles.}",
            "#gag {spam} (all worlds)",
            "#alias l = look (all worlds)",
            "{The %1 slashes you.} = flee",
            "{The fountain gurgles.} = gag",
            "{spam} = gag (all worlds)",
            "Usage: #alias {name} {expansion}",
            "Unknown command: #bogus",
        )
        withTimeout(2_000) { manager.scrollback.first { it.lines.systemTexts() == expected } }
        assertTrue(server.noMoreLines())
    }

    @Test
    fun hashCommandsWorkWhileDisconnected() = runBlocking<Unit> {
        val manager = manager().connected()
        manager.disconnect()
        manager.awaitState { it == SessionState.Idle }
        manager.send("look")
        manager.send("#alias k kill %1")
        manager.awaitTexts("Disconnected", "#alias k kill %1", "#alias k = kill %1")
        assertEquals("kill %1", manager.ruleSet.value.aliases.getValue("k").expansion)
    }

    @Test
    fun unaliasRemovesTheWorldAliasFirstThenTheGlobalOne() = runBlocking<Unit> {
        val manager = manager().connected()
        manager.send("#galias k kick %1")
        manager.send("#alias k kill %1")
        manager.send("k rat")
        assertEquals("kill rat", server.awaitLine())
        manager.send("#unalias k")
        manager.send("k rat")
        assertEquals("kick rat", server.awaitLine())
        manager.send("#unalias k")
        manager.send("k rat")
        assertEquals("k rat", server.awaitLine())
        manager.send("#unalias k")
        val expected = listOf(
            "#alias k = kick %1 (all worlds)",
            "#alias k = kill %1",
            "Removed alias k",
            "Removed alias k (all worlds)",
            "No alias k",
        )
        withTimeout(2_000) { manager.scrollback.first { it.lines.systemTexts() == expected } }
    }

    @Test
    fun ruleEditFromTheRulesScreenAppliesLive() = runBlocking<Unit> {
        val manager = manager().connected()
        server.send("The fountain gurgles.\r\n")
        manager.awaitTexts("The fountain gurgles.")

        val id = rules.save(Trigger(0, thisWorld, "The fountain gurgles.", gag = true))
        withTimeout(2_000) { manager.ruleSet.first { it.triggers.isNotEmpty() } }
        server.send("The fountain gurgles.\r\nNorth.\r\n")
        manager.awaitTexts("The fountain gurgles.", "North.")

        rules.setEnabled(id, false)
        withTimeout(2_000) { manager.ruleSet.first { it.triggers.isEmpty() } }
        server.send("The fountain gurgles.\r\n")
        manager.awaitTexts("The fountain gurgles.", "North.", "The fountain gurgles.")
    }

    @Test
    fun gaPromptRunsTriggersButThePartialLineDoesNot() = runBlocking<Unit> {
        rules.save(Trigger(0, thisWorld, "HP:", vibrate = true))
        val manager = manager().connected()
        server.send("HP:10 > ")
        manager.awaitTexts("HP:10 > ")
        delay(100)
        assertEquals(0, alerts.vibrations.get())
        server.send(GA)
        manager.awaitLines { it.lastOrNull()?.kind == LineKind.Prompt }
        withTimeout(2_000) { while (alerts.vibrations.get() == 0) delay(10) }
    }

    @Test
    fun notifyGoesToTriggerAlerts() = runBlocking<Unit> {
        rules.save(Trigger(0, Scope.Global, "%1 tells you: %2", notify = true))
        rules.save(Trigger(0, thisWorld, "shouts", notify = true, vibrate = true))
        val manager = manager().connected()
        server.send("Hagbard tells you: wanna group?\r\nGrog shouts hello\r\n")
        manager.awaitTexts("Hagbard tells you: wanna group?", "Grog shouts hello")
        withTimeout(2_000) { while (alerts.notifications.size < 2) delay(10) }
        val (first, second) = alerts.notifications.map { it.second }
        assertEquals("Hagbard" to "wanna group?", first.title to first.message)
        assertEquals("Test MUD" to "Grog shouts hello", second.title to second.message)
        assertEquals(plainWorld, alerts.notifications[0].first)
        assertEquals(1, alerts.vibrations.get())
    }

    @Test
    fun passwordIsSentAsTypedWithoutParsing() = runBlocking<Unit> {
        rules.save(Alias(0, thisWorld, "hunter2", "nope"))
        val manager = manager().connected()
        server.send("Password: ".toByteArray() + WILL_ECHO)
        withTimeout(2_000) { manager.serverEcho.first { it } }
        manager.send("hunter2;#x")
        assertEquals("hunter2;#x", server.awaitLine())
        delay(100)
        assertEquals(listOf("Password: "), manager.scrollback.value.lines.map { it.text })
    }

    @Test
    fun replyGoesOutAsOneCommandAndShowsDim() = runBlocking<Unit> {
        rules.save(Alias(0, thisWorld, "tell", "nope"))
        val manager = manager().connected()
        assertEquals(ReplyResult.Sent, manager.reply(plainWorld.id, "tell Hagbard sure; omw"))
        assertEquals("tell Hagbard sure; omw", server.awaitLine())
        assertTrue(server.noMoreLines())
        val line = manager.awaitTexts("[reply] tell Hagbard sure; omw").single()
        assertEquals(LineKind.Echo, line.kind)
        assertTrue(manager.history.value.isEmpty())
    }

    @Test
    fun replyWhileIdleOrForAnotherWorldIsNotSent() = runBlocking<Unit> {
        val manager = manager()
        assertEquals(ReplyResult.NotConnected, manager.reply(plainWorld.id, "tell Hagbard hi"))
        manager.connected()
        assertEquals(ReplyResult.NotConnected, manager.reply(plainWorld.id + 1, "tell Hagbard hi"))
        manager.disconnect()
        manager.awaitState { it == SessionState.Idle }
        assertEquals(ReplyResult.NotConnected, manager.reply(plainWorld.id, "tell Hagbard hi"))
        assertTrue(server.noMoreLines())
        assertFalse(manager.scrollback.value.lines.any { "[reply]" in it.text })
    }

    @Test
    fun replyWhileReconnectingWaitsForAutoLogin() = runBlocking<Unit> {
        val id = repository.save(plainWorld.copy(id = 0, username = "drake"), PasswordEdit.Set("hunter2"))
        val world = repository.get(id)!!
        val manager = manager(retryDelay = 300.milliseconds).connected(world)
        login(manager)
        server.reset()
        manager.awaitState { it is SessionState.Reconnecting }

        assertEquals(ReplyResult.Queued, manager.reply(id, "tell Hagbard sure, omw"))
        manager.awaitState { it is SessionState.Connected }
        login(manager)
        assertEquals("tell Hagbard sure, omw", server.awaitLine())
        val texts = manager.awaitLines { lines -> lines.any { it.text == "[reply] tell Hagbard sure, omw" } }.map { it.text }
        assertTrue(texts.lastIndexOf("Welcome!") < texts.indexOf("[reply] tell Hagbard sure, omw"))
        assertTrue(texts.indexOf("Reconnected") < texts.lastIndexOf("Welcome!"))
    }

    @Test
    fun queuedReplyIsDroppedWithoutAutoLogin() = runBlocking<Unit> {
        val manager = manager(retryDelay = 300.milliseconds).connected()
        server.reset()
        manager.awaitState { it is SessionState.Reconnecting }
        assertEquals(ReplyResult.Queued, manager.reply(plainWorld.id, "tell Hagbard hi"))
        manager.awaitState { it is SessionState.Connected }
        manager.awaitLines { lines -> lines.any { it.text == "Reply not sent: tell Hagbard hi" } }
        assertTrue(server.noMoreLines())
    }

    @Test
    fun queuedReplyIsDroppedWhenTheSessionEnds() = runBlocking<Unit> {
        val manager = manager(retryDelay = 5.seconds).connected()
        server.reset()
        manager.awaitState { it is SessionState.Reconnecting }
        assertEquals(ReplyResult.Queued, manager.reply(plainWorld.id, "tell Hagbard hi"))
        manager.disconnect()
        manager.awaitTexts("Disconnected: Connection reset", "Disconnected", "Reply not sent: tell Hagbard hi")
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
