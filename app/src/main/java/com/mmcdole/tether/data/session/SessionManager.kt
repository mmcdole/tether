package com.mmcdole.tether.data.session

import com.mmcdole.tether.data.settings.SessionOptionsSource
import com.mmcdole.tether.data.world.World
import com.mmcdole.tether.data.world.WorldRepository
import com.mmcdole.tether.di.ApplicationScope
import com.mmcdole.tether.di.DefaultDispatcher
import com.mmcdole.tether.di.IoDispatcher
import com.mmcdole.tether.mud.command.CommandSplitter
import com.mmcdole.tether.mud.text.LineContent
import com.mmcdole.tether.mud.text.LineKind
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the one live MUD session: the connection, its scrollback, auto-login, and
 * reconnecting after a drop. Lives as long as the app process. Every change runs on
 * [session], one at a time.
 */
@Singleton
class SessionManager @Inject constructor(
    private val worlds: WorldRepository,
    private val socketFactory: MudSocketFactory,
    private val network: NetworkMonitor,
    private val policy: ReconnectPolicy,
    private val options: SessionOptionsSource,
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

    private val lines = Scrollback()
    private var partial = LineContent.Empty
    private var connection: MudConnection? = null
    private var job: Job? = null
    private var login: Login? = null
    private var columns = 80
    private var rows = 24

    // The network the connection went out on; losing it means the socket is dead.
    private var boundNetwork: Long? = null
    private val networkBack = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(session) { network.events.collect(::onNetwork) }
    }

    fun connect(world: World) = onSession {
        stop()
        if (_world.value?.id != world.id) {
            lines.clear()
            _history.value = emptyList()
        }
        _world.value = world
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

    fun send(input: String) = onSession {
        val conn = connection ?: return@onSession
        // While the server echoes, the input is a password: not echoed, not remembered.
        val echo = !conn.serverEcho
        if (echo && input.isNotBlank() && _history.value.lastOrNull() != input) {
            _history.value = (_history.value + input).takeLast(HISTORY_SIZE)
        }
        for (command in CommandSplitter.split(input)) {
            conn.sendLine(command)
            if (echo) echo(conn, command)
        }
        publish()
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

        return try {
            coroutineScope {
                launch { conn.runWriter() }
                launch { conn.runKeepalive() }
                readUntilEnd(conn).also { coroutineContext.cancelChildren() }
            }
        } catch (e: IOException) {
            // The writer failing, as in a broken pipe.
            ConnectionEnd.Failed(e)
        } finally {
            conn.close()
        }
    }

    private suspend fun readUntilEnd(conn: MudConnection): ConnectionEnd {
        while (true) {
            when (val result = conn.read()) {
                is Received -> {
                    val echoWasOn = _serverEcho.value
                    apply(result, conn)
                    autoLogin(conn, result, echoTurnedOn = !echoWasOn && conn.serverEcho)
                }
                is ConnectionEnd -> return result
            }
        }
    }

    private fun apply(received: Received, conn: MudConnection) {
        for (line in received.lines) lines.add(line.content, if (line.prompt) LineKind.Prompt else LineKind.Output)
        partial = received.partial
        _serverEcho.value = conn.serverEcho
        publish()
    }

    private suspend fun autoLogin(conn: MudConnection, received: Received, echoTurnedOn: Boolean) {
        val login = login ?: return
        val output = received.lines.isNotEmpty() || received.partial.text.isNotEmpty()
        when (login.step) {
            Login.Step.Username -> if (output) {
                val username = login.world.username.orEmpty()
                conn.sendLine(username)
                if (!conn.serverEcho) echo(conn, username)
                publish()
                login.step = if (login.world.hasPassword) Login.Step.Password else Login.Step.Done
            }
            Login.Step.Password -> if (output || echoTurnedOn) {
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
        boundNetwork = null
        _serverEcho.value = false
        // Keep an unfinished prompt in the scrollback instead of losing it.
        if (partial.text.isNotEmpty()) lines.add(partial, LineKind.Prompt)
        partial = LineContent.Empty
    }

    private fun ended(message: String) {
        lines.add(LineContent(message, emptyList()), LineKind.System)
        _state.value = SessionState.Idle
        publish()
    }

    private fun publish() {
        _scrollback.value = lines.snapshot(partial)
    }

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
    }
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
