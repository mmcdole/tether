package com.mmcdole.tether.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mmcdole.tether.data.session.SessionLog
import com.mmcdole.tether.data.session.SessionManager
import com.mmcdole.tether.data.session.SessionState
import com.mmcdole.tether.data.session.TriggerAlerts
import com.mmcdole.tether.data.settings.AppSettings
import com.mmcdole.tether.data.settings.SettingsRepository
import com.mmcdole.tether.di.DefaultDispatcher
import com.mmcdole.tether.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ConnectionStatus { Idle, Connecting, Reconnecting, Connected }

data class SessionUiState(
    val worldId: Long? = null,
    val worldName: String = "",
    val status: ConnectionStatus = ConnectionStatus.Idle,
    val connectedSince: Instant? = null,
    val canReconnect: Boolean = false,
    val password: Boolean = false,
    val lines: List<OutputLine> = emptyList(),
    val history: List<String> = emptyList(),
)

@HiltViewModel
class SessionViewModel @Inject constructor(
    private val session: SessionManager,
    private val settingsRepository: SettingsRepository,
    private val alerts: TriggerAlerts,
    log: SessionLog,
    @DefaultDispatcher default: CoroutineDispatcher,
    @IoDispatcher io: CoroutineDispatcher,
) : ViewModel() {
    /** Whether there's a log to share for the world on screen. */
    val hasLog: StateFlow<Boolean> = combine(session.world, log.current) { world, _ ->
        world != null && log.latest(world.name) != null
    }.flowOn(io).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    suspend fun logToShare(): File? = session.logToShare()

    private var rendered = emptyList<OutputLine>()

    // Styling happens off the main thread, and only for lines that are new or changed.
    private val lines = session.scrollback
        .map { snapshot -> renderLines(snapshot.lines, rendered).also { rendered = it } }
        .flowOn(default)

    /** The chat tray's lines, styled. */
    val chat: StateFlow<List<ChatMessage>> = session.chat
        .map { lines -> lines.map { ChatMessage(it.line.id, it.line.toAnnotatedString(), it.time) } }
        .flowOn(default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val state: StateFlow<SessionUiState> = combine(
        session.state,
        session.world,
        lines,
        session.serverEcho,
        session.history,
    ) { state, world, lines, serverEcho, history ->
        uiState(state, world?.id, world?.name.orEmpty(), lines, serverEcho, history)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        uiState(session.state.value, session.world.value?.id, session.world.value?.name.orEmpty(), emptyList(), false, emptyList()),
    )

    // The size while pinching, until it's saved.
    private val pinchSize = MutableStateFlow<Int?>(null)

    /** Null until loaded, so the output doesn't draw at the default size first. */
    val settings: StateFlow<AppSettings?> = combine(settingsRepository.settings, pinchSize) { saved, pinch ->
        if (pinch == null) saved else saved.copy(textSize = pinch)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun pinchTextSize(size: Int) {
        pinchSize.value = size
    }

    fun endPinch() {
        val size = pinchSize.value ?: return
        viewModelScope.launch {
            settingsRepository.setTextSize(size)
            pinchSize.value = null
        }
    }

    fun setWindowSize(size: WindowSize) = session.setWindowSize(size.columns, size.rows)

    fun send(input: String) = session.send(input)

    fun disconnect() = session.disconnect()

    /** The session is on screen, so its world's message notifications have been seen. */
    fun shown() {
        val id = session.world.value?.id ?: return
        alerts.clearNotifications(id)
        alerts.setOnScreen(id)
    }

    fun hidden() = alerts.setOnScreen(null)

    fun reconnect() = session.reconnect()

    private fun uiState(
        state: SessionState,
        worldId: Long?,
        worldName: String,
        lines: List<OutputLine>,
        serverEcho: Boolean,
        history: List<String>,
    ) = SessionUiState(
        worldId = worldId,
        worldName = worldName,
        status = when (state) {
            SessionState.Idle -> ConnectionStatus.Idle
            is SessionState.Connecting -> ConnectionStatus.Connecting
            is SessionState.Reconnecting -> ConnectionStatus.Reconnecting
            is SessionState.Connected -> ConnectionStatus.Connected
        },
        connectedSince = (state as? SessionState.Connected)?.since,
        canReconnect = state == SessionState.Idle && worldName.isNotEmpty(),
        password = serverEcho,
        lines = lines,
        history = history,
    )
}
