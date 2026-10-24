package com.mmcdole.tether.data.session

import com.mmcdole.tether.data.rules.Removal
import com.mmcdole.tether.data.rules.RuleRepository
import com.mmcdole.tether.data.settings.SessionOptionsSource
import com.mmcdole.tether.data.world.World
import com.mmcdole.tether.data.world.WorldRepository
import com.mmcdole.tether.di.ApplicationScope
import com.mmcdole.tether.di.DefaultDispatcher
import com.mmcdole.tether.di.IoDispatcher
import com.mmcdole.tether.mud.command.CommandParser
import com.mmcdole.tether.mud.command.CommandSplitter
import com.mmcdole.tether.mud.command.Step
import com.mmcdole.tether.mud.rules.RuleSet
import com.mmcdole.tether.mud.rules.Scope
import com.mmcdole.tether.mud.rules.Trigger
import com.mmcdole.tether.mud.rules.TriggerEngine
import com.mmcdole.tether.mud.text.LineContent
import com.mmcdole.tether.mud.text.LineKind
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the one live MUD session: the connection, its scrollback, auto-login, rules, and
 * reconnecting after a drop. Lives as long as the app process. Every change runs on
 * [session], one at a time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SessionManager @Inject constructor(
    private val worlds: WorldRepository,
    private val rules: RuleRepository,
    private val socketFactory: MudSocketFactory,
    private val network: NetworkMonitor,
    private val policy: ReconnectPolicy,
    private val options: SessionOptionsSource,
    private val alerts: TriggerAlerts,
    private val log: SessionLog,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
    @DefaultDispatcher default: CoroutineDispatcher,
    private val clock: Clock,
) {
    private val session = default.limitedParallelism(1)

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _world = MutableStateFlow<World?>(null)

    /** The world of the current session, or of the last one once it has ended. */
    val world: StateFlow<World?> = _world.asStateFlow()

    private val _scrollback = MutableStateFlow(ScrollbackSnapshot.Empty)
    val scrollback: StateFlow<ScrollbackSnapshot> = _scrollback.asStateFlow()

    private val _serverEcho = MutableStateFlow(false)
    val serverEcho: StateFlow<Boolean> = _serverEcho.asStateFlow()

    private val _history = MutableStateFlow<List<String>>(emptyList())

    /** Commands typed this session, oldest first. Passwords are never kept. */
    val history: StateFlow<List<String>> = _history.asStateFlow()

    private val _ruleSet = MutableStateFlow(RuleSet.Empty)

    /** The rules for [world]: its own plus the global ones. Edits show up here before the next line. */
    val ruleSet: StateFlow<RuleSet> = _ruleSet.asStateFlow()

    private val lines = Scrollback(options.options.value.scrollbackLines).also { it.listener = log }

    // `#` commands suspend on the database. This keeps typed input and incoming lines in order across that.
    private val commands = Mutex()
    private var partial = LineContent.Empty
    private var connection: MudConnection? = null
    private var job: Job? = null
    private var login: Login? = null
    // Notification replies waiting for the connection to come back and log in.
    private val pendingReplies = ArrayList<String>()

    // `#ticker`s by name. They last until the session ends, across reconnects.
    private val tickers = LinkedHashMap<String, Ticker>()

    // False from connecting until auto-login is through; replies wait until then.
    private var loggedIn = false
    private var columns = 80
    private var rows = 24

    // The network the connection went out on; losing it means the socket is dead.
    private var boundNetwork: Long? = null
    private val networkBack = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(session) { network.events.collect(::onNetwork) }
        scope.launch(session) {
            _world.map { it?.id }
                .distinctUntilChanged()
                .flatMapLatest { id -> if (id == null) flowOf(RuleSet.Empty) else rules.observeRuleSet(id) }
                .collect { _ruleSet.value = it }
        }
        scope.launch(session) {
            options.options.map { it.scrollbackLines }.distinctUntilChanged().collect {
                lines.capacity = it
                publish()
            }
        }
        // Turning logs on mid-session starts a file from there; turning them off closes it.
        scope.launch(session) {
            options.options.map { it.saveLogs }.distinctUntilChanged().collect { on ->
                val world = _state.value.activeWorld
                when {
                    !on -> log.close()
                    world != null && !log.isOpen -> log.start(world.name)
                }
            }
        }
        scope.launch(session) {
            while (true) {
                delay(LOG_FLUSH_INTERVAL)
                log.flush()
            }
        }
    }

    fun connect(world: World) = onSession {
        stop()
        val newWorld = _world.value?.id != world.id
        if (newWorld) {
            lines.clear()
            _history.value = emptyList()
            pendingReplies.clear()
            stopTickers()
        }
        _world.value = world
        log.close()
        if (options.options.value.saveLogs) log.start(world.name)
        // Loaded before connecting, so the first line already has this world's triggers.
        if (newWorld) _ruleSet.value = rules.observeRuleSet(world.id).first()
        _state.value = SessionState.Connecting(world)
        publish()
        job = scope.launch(session) { run(world) }
    }

    /** Connects to the last world again. */
    fun reconnect() = onSession {
        val world = _world.value
        if (world != null && _state.value == SessionState.Idle) connect(world)
    }

    fun disconnect() = onSession {
        if (job == null) return@onSession
        stop()
        ended("Disconnected")
    }

    /**
     * Sends typed input: split on `;`, aliases expanded, `#` commands run here. Each command is
     * parsed after the one before it ran, so `#alias k {kill %1};k rat` uses the new alias. While
     * disconnected only the `#` commands do anything.
     */
    fun send(input: String) = onSession { commands.withLock { sendNow(input) } }

    private suspend fun sendNow(input: String) {
        val world = _world.value ?: return
        val conn = connection
        // While the server echoes, the input is a password: sent as typed, not echoed, not remembered.
        if (conn != null && conn.serverEcho) {
            conn.sendLine(input)
            return
        }
        if (input.isNotBlank() && _history.value.lastOrNull() != input) {
            _history.value = (_history.value + input).takeLast(HISTORY_SIZE)
        }
        for (command in CommandSplitter.split(input)) {
            val steps = CommandParser.parseCommand(command, _ruleSet.value.aliases)
            when {
                conn != null -> echo(conn, command)
                steps.all { it is Step.Send } -> continue
                else -> lines.add(LineContent(command, emptyList()), LineKind.Echo)
            }
            perform(steps, conn, world)
        }
        publish()
    }

    /**
     * Sends a reply from a notification, already composed by [replyCommand]. It goes to the MUD
     * as one command: not split on `;`, no aliases, since it's chat. Queued while reconnecting.
     */
    suspend fun reply(worldId: Long, command: String): ReplyResult = withContext(session) {
        commands.withLock {
            val state = _state.value
            val conn = connection
            when {
                state.activeWorld?.id != worldId -> ReplyResult.NotConnected
                conn != null && loggedIn && pendingReplies.isEmpty() -> {
                    sendReply(conn, command)
                    publish()
                    ReplyResult.Sent
                }
                else -> {
                    pendingReplies += command
                    ReplyResult.Queued
                }
            }
        }
    }

    private fun sendReply(conn: MudConnection, command: String) {
        conn.sendLine(command)
        lines.add(LineContent("[reply] $command", emptyList()), LineKind.Echo)
    }

    /** Sends the replies that waited for the connection. */
    private fun sendPendingReplies(conn: MudConnection) {
        if (pendingReplies.isEmpty()) return
        for (command in pendingReplies) sendReply(conn, command)
        pendingReplies.clear()
        publish()
    }

    /** Drops the waiting replies, with a line for each so you know. */
    private fun dropPendingReplies() {
        for (command in pendingReplies) lines.add(LineContent("Reply not sent: $command", emptyList()), LineKind.System)
        pendingReplies.clear()
    }

    /** The newest session log for the world on screen, written out so far, or null if there is none. */
    suspend fun logToShare(): File? = withContext(session) {
        log.flush()
        _world.value?.let { log.latest(it.name) }
    }

    fun setWindowSize(columns: Int, rows: Int) = onSession {
        this.columns = columns
        this.rows = rows
        connection?.setWindowSize(columns, rows)
    }

    private fun onSession(block: suspend () -> Unit) {
        scope.launch(session) { block() }
    }

    private fun onNetwork(event: NetworkEvent) {
        when (event) {
            is NetworkEvent.Lost -> if (event.network == boundNetwork) connection?.abort()
            is NetworkEvent.Available -> if (_state.value is SessionState.Reconnecting) networkBack.trySend(Unit)
        }
    }

    /**
     * Connects, plays until the connection ends, and reconnects after network failures until
     * told to stop. A server closing the connection is final: that's quit, a kick, or a reboot.
     */
    private suspend fun run(world: World) {
        val self = currentCoroutineContext()[Job]
        // Failed attempts since the connection was last up; 0 for the first connect.
        var attempt = 0
        while (true) {
            val conn = try {
                MudConnection.open(socketFactory, world.host, world.port, world.tls, io)
            } catch (e: IOException) {
                if (job !== self) return
                when {
                    attempt == 0 -> return stopped("Couldn't connect: ${e.reason()}")
                    attempt >= policy.maxAttempts -> return stopped("Couldn't reconnect")
                }
                attempt = retry(world, attempt + 1)
                continue
            }
            val end = play(world, conn, reconnected = attempt > 0)
            // disconnect() and connect() cancel this job and clean up themselves.
            if (job !== self) return
            closeConnection()
            val message = when (end) {
                ConnectionEnd.Closed -> "Disconnected"
                is ConnectionEnd.Failed -> "Disconnected: ${end.error.reason()}"
            }
            if (end == ConnectionEnd.Closed || !options.options.value.autoReconnect) return stopped(message)
            lines.add(LineContent(message, emptyList()), LineKind.System)
            log.flush()
            publish()
            attempt = retry(world, 1)
        }
    }

    private fun stopped(message: String) {
        job = null
        ended(message)
    }

    private suspend fun retry(world: World, attempt: Int): Int {
        _state.value = SessionState.Reconnecting(world, attempt)
        policy.await(attempt, networkBack)
        return attempt
    }

    private suspend fun play(world: World, conn: MudConnection, reconnected: Boolean): ConnectionEnd {
        connection = conn
        boundNetwork = network.defaultNetwork()
        login = Login(world)
        conn.setWindowSize(columns, rows)
        if (reconnected) {
            lines.add(LineContent("Reconnected", emptyList()), LineKind.System)
            publish()
        }
        _state.value = SessionState.Connected(world, clock.instant())
        scope.launch { worlds.markConnected(world.id) }
        // Without auto-login a fresh connection sits at the login prompt, where a queued tell
        // would be taken as a name. Replies only wait for worlds that log in by themselves.
        if (login?.step == Login.Step.Done) {
            commands.withLock {
                dropPendingReplies()
                loggedIn = true
            }
            publish()
        }

        return try {
            coroutineScope {
                launch { conn.runWriter() }
                launch { conn.runKeepalive() }
                readUntilEnd(world, conn).also { coroutineContext.cancelChildren() }
            }
        } catch (e: IOException) {
            // The writer failing, as in a broken pipe.
            ConnectionEnd.Failed(e)
        } finally {
            conn.close()
        }
    }

    private suspend fun readUntilEnd(world: World, conn: MudConnection): ConnectionEnd {
        while (true) {
            when (val result = conn.read()) {
                is Received -> {
                    val echoWasOn = _serverEcho.value
                    // The first output after the password went in: the character is in the game.
                    val loginDone = !loggedIn && login?.step == Login.Step.Done && result.hasOutput
                    commands.withLock {
                        apply(result, conn, world)
                        if (loginDone) {
                            loggedIn = true
                            sendPendingReplies(conn)
                        }
                    }
                    autoLogin(conn, result, echoTurnedOn = !echoWasOn && conn.serverEcho)
                }
                is ConnectionEnd -> return result
            }
        }
    }

    /** Adds finished lines, running triggers on each. The partial line is left alone until it's finished. */
    private suspend fun apply(received: Received, conn: MudConnection, world: World) {
        for (line in received.lines) {
            val result = TriggerEngine.run(_ruleSet.value, line.content.text, world.name)
            if (!result.gag) {
                lines.add(line.content, if (line.prompt) LineKind.Prompt else LineKind.Output, highlight = result.highlight)
            }
            if (result.isEmpty) continue
            if (result.vibrate) alerts.vibrate()
            for (request in result.notifications) alerts.notify(world, request)
            // Already split by the engine. Shown dim, like typed commands, so you can see what fired.
            for (command in result.commands) {
                if (!conn.serverEcho) lines.add(LineContent(command, emptyList()), LineKind.Echo)
                perform(CommandParser.parseCommand(command, _ruleSet.value.aliases), conn, world)
            }
        }
        partial = received.partial
        _serverEcho.value = conn.serverEcho
        publish()
    }

    private suspend fun autoLogin(conn: MudConnection, received: Received, echoTurnedOn: Boolean) {
        val login = login ?: return
        when (login.step) {
            Login.Step.Username -> if (received.hasOutput) {
                val username = login.world.username.orEmpty()
                conn.sendLine(username)
                if (!conn.serverEcho) echo(conn, username)
                publish()
                login.step = if (login.world.hasPassword) Login.Step.Password else Login.Step.Done
            }
            Login.Step.Password -> if (received.hasOutput || echoTurnedOn) {
                login.step = Login.Step.Done
                val password = worlds.password(login.world.id)
                if (connection !== conn) return
                if (password != null) {
                    conn.sendLine(password)
                } else {
                    lines.add(LineContent("Couldn't read the saved password", emptyList()), LineKind.System)
                    publish()
                }
            }
            Login.Step.Done -> Unit
        }
    }

    /** Sends the [Step.Send]s through [conn], if connected, and runs the rest here. */
    private suspend fun perform(steps: List<Step>, conn: MudConnection?, world: World) {
        for (step in steps) {
            if (step is Step.Send) {
                conn?.sendLine(step.command)
                continue
            }
            for (text in local(step, world)) lines.add(LineContent(text, emptyList()), LineKind.System)
        }
    }

    /** Runs a `#` command and returns the lines to show for it. */
    private suspend fun local(step: Step, world: World): List<String> {
        fun scope(global: Boolean) = if (global) Scope.Global else Scope.World(world.id)
        fun allWorlds(global: Boolean) = if (global) ALL_WORLDS else ""
        fun removed(what: String, removal: Removal) = when (removal) {
            is Removal.Removed -> "Removed $what" + allWorlds(removal.scope == Scope.Global)
            Removal.NotFound -> "No $what"
        }
        val lines = when (step) {
            is Step.AddAlias -> {
                rules.addAlias(scope(step.global), step.name, step.expansion)
                listOf("#alias ${step.name} = ${step.expansion}" + allWorlds(step.global))
            }
            is Step.AddAction -> {
                rules.addAction(scope(step.global), step.pattern, step.command)
                listOf("#action {${step.pattern}} = ${step.command}" + allWorlds(step.global))
            }
            is Step.AddGag -> {
                rules.addGag(scope(step.global), step.pattern)
                listOf("#gag {${step.pattern}}" + allWorlds(step.global))
            }
            is Step.RemoveAlias -> listOf(removed("alias ${step.name}", rules.removeAlias(world.id, step.name)))
            is Step.RemoveAction -> listOf(removed("action {${step.pattern}}", rules.removeAction(world.id, step.pattern)))
            is Step.RemoveGag -> listOf(removed("gag {${step.pattern}}", rules.removeGag(world.id, step.pattern)))
            Step.ListAliases -> {
                val lists = rules.observeRules(world.id).first()
                val all = lists.worldAliases.map { it to false } + lists.globalAliases.map { it to true }
                all.filter { it.first.enabled }
                    .map { (alias, global) -> "#alias ${alias.name} = ${alias.expansion}" + allWorlds(global) }
                    .ifEmpty { listOf("No aliases") }
            }
            Step.ListTriggers -> {
                val lists = rules.observeRules(world.id).first()
                val all = lists.worldTriggers.map { it to false } + lists.globalTriggers.map { it to true }
                all.filter { it.first.enabled }
                    .map { (trigger, global) -> trigger.describe() + allWorlds(global) }
                    .ifEmpty { listOf("No triggers") }
            }
            is Step.AddTicker -> {
                startTicker(step, world)
                listOf("#ticker ${step.name} = ${step.command} every ${seconds(step.intervalMillis)}")
            }
            is Step.RemoveTicker -> {
                val ticker = tickers.remove(step.name)
                ticker?.job?.cancel()
                listOf(if (ticker != null) "Removed ticker ${step.name}" else "No ticker ${step.name}")
            }
            Step.ListTickers -> tickers.map { (name, t) -> "#ticker $name = ${t.command} every ${seconds(t.intervalMillis)}" }
                .ifEmpty { listOf("No tickers") }
            is Step.Error -> listOf(step.message)
            is Step.Send -> emptyList()
        }
        // Read back now rather than waiting on the database flow, so the very next command or line sees it.
        if (step is Step.AddAlias || step is Step.AddAction || step is Step.AddGag ||
            step is Step.RemoveAlias || step is Step.RemoveAction || step is Step.RemoveGag
        ) {
            _ruleSet.value = rules.observeRuleSet(world.id).first()
        }
        return lines
    }

    /** Starts [step]'s ticker, replacing one with the same name. It skips beats while disconnected. */
    private fun startTicker(step: Step.AddTicker, world: World) {
        tickers.remove(step.name)?.job?.cancel()
        val job = scope.launch(session) {
            while (true) {
                delay(step.intervalMillis)
                commands.withLock {
                    val conn = connection ?: return@withLock
                    if (conn.serverEcho) return@withLock
                    lines.add(LineContent(step.command, emptyList()), LineKind.Echo)
                    perform(CommandParser.parse(step.command, _ruleSet.value.aliases), conn, world)
                    publish()
                }
            }
        }
        tickers[step.name] = Ticker(step.command, step.intervalMillis, job)
    }

    private fun stopTickers() {
        for (ticker in tickers.values) ticker.job.cancel()
        tickers.clear()
    }

    /** Shows a sent command after the prompt it answers, or on its own line. */
    private fun echo(conn: MudConnection, command: String) {
        val prompt = conn.takePartial()
        val last = lines.last
        when {
            prompt != null -> {
                lines.add(LineContent(prompt.text + command, prompt.spans), LineKind.Prompt, echoStart = prompt.text.length)
                partial = LineContent.Empty
            }
            last != null && last.kind == LineKind.Prompt && last.echoStart < 0 ->
                lines.replaceLast(last.copy(text = last.text + command, echoStart = last.text.length))
            command.isNotEmpty() -> lines.add(LineContent(command, emptyList()), LineKind.Echo)
        }
    }

    private fun stop() {
        job?.cancel()
        job = null
        closeConnection()
    }

    private fun closeConnection() {
        connection?.close()
        connection = null
        login = null
        loggedIn = false
        boundNetwork = null
        _serverEcho.value = false
        // Keep an unfinished prompt in the scrollback instead of losing it.
        if (partial.text.isNotEmpty()) lines.add(partial, LineKind.Prompt)
        partial = LineContent.Empty
    }

    private fun ended(message: String) {
        lines.add(LineContent(message, emptyList()), LineKind.System)
        dropPendingReplies()
        stopTickers()
        log.close()
        _state.value = SessionState.Idle
        publish()
    }

    private fun publish() {
        _scrollback.value = lines.snapshot(partial)
    }

    private class Ticker(val command: String, val intervalMillis: Long, val job: Job)

    private class Login(val world: World) {
        enum class Step { Username, Password, Done }

        var step = when {
            world.username != null -> Step.Username
            world.hasPassword -> Step.Password
            else -> Step.Done
        }
    }

    private companion object {
        const val HISTORY_SIZE = 100
        val LOG_FLUSH_INTERVAL = 5.seconds
        const val ALL_WORLDS = " (all worlds)"
    }
}

/** An interval as `1s` or `2.5s`. */
private fun seconds(millis: Long): String = if (millis % 1000 == 0L) "${millis / 1000}s" else "${millis / 1000.0}s"

private val Received.hasOutput: Boolean get() = lines.isNotEmpty() || partial.text.isNotEmpty()

/** A trigger as one line for `#action` with no arguments, like `{The %1 slashes you.} = flee`. */
private fun Trigger.describe(): String {
    val actions = listOfNotNull(
        "gag".takeIf { gag },
        highlight?.let { "highlight #%06X".format(it) },
        send?.takeIf { it.isNotBlank() },
        "vibrate".takeIf { vibrate },
        "notify".takeIf { notify },
    )
    val regex = if (isRegex) " (regex)" else ""
    return "{$pattern}$regex = ${actions.joinToString(" · ")}"
}

private fun IOException.reason(): String = when (this) {
    is UnknownHostException -> "Unknown host"
    is SocketTimeoutException -> "Timed out"
    is NoRouteToHostException -> "No route to host"
    is NetworkLostException -> "Network lost"
    is SSLException -> "TLS error"
    is ConnectException -> if (message?.contains("refused", ignoreCase = true) != false) "Connection refused" else "Network unreachable"
    else -> message?.substringAfterLast("(")?.removeSuffix(")")?.takeIf { it.isNotBlank() } ?: "Connection lost"
}
