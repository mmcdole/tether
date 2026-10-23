package com.mmcdole.tether.ui.worlds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mmcdole.tether.data.session.SessionManager
import com.mmcdole.tether.data.session.SessionState
import com.mmcdole.tether.data.session.activeWorld
import com.mmcdole.tether.data.world.World
import com.mmcdole.tether.data.world.WorldRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class WorldsUiState(
    val loading: Boolean = true,
    /** The world with a live session, if any. */
    val active: ActiveWorld? = null,
    /** Every other world. */
    val worlds: List<WorldRow> = emptyList(),
)

enum class ActiveStatus { Connecting, Connected, Reconnecting }

data class ActiveWorld(val world: WorldRow, val status: ActiveStatus, val since: Instant?)

data class WorldRow(
    val id: Long,
    val name: String,
    val address: String,
    val lastPlayed: String?,
)

@HiltViewModel
class WorldsViewModel @Inject constructor(
    repository: WorldRepository,
    private val session: SessionManager,
    private val clock: Clock,
) : ViewModel() {
    private var worlds = emptyList<World>()

    val state: StateFlow<WorldsUiState> = combine(repository.worlds, session.state) { worlds, state ->
        this.worlds = worlds
        val active = state.activeWorld
        WorldsUiState(
            loading = false,
            active = active?.let { world ->
                ActiveWorld(
                    world = (worlds.firstOrNull { it.id == world.id } ?: world).toRow(),
                    status = when (state) {
                        is SessionState.Connected -> ActiveStatus.Connected
                        is SessionState.Reconnecting -> ActiveStatus.Reconnecting
                        else -> ActiveStatus.Connecting
                    },
                    since = (state as? SessionState.Connected)?.since,
                )
            },
            worlds = worlds.filter { it.id != active?.id }.map { it.toRow() },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorldsUiState())

    /** Starts a session, ending any other one. */
    fun connect(id: Long) {
        worlds.firstOrNull { it.id == id }?.let(session::connect)
    }

    fun disconnect() = session.disconnect()

    private fun World.toRow() = WorldRow(
        id = id,
        name = name,
        address = "$host : $port" + if (tls) " · TLS" else "",
        lastPlayed = lastPlayedLabel(lastConnectedAt, clock),
    )
}
