package com.mmcdole.tether.ui.worlds

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.mmcdole.tether.data.world.PasswordEdit
import com.mmcdole.tether.data.world.World
import com.mmcdole.tether.data.world.WorldRepository
import com.mmcdole.tether.ui.WorldEditRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WorldEditUiState(
    val isNew: Boolean,
    val loading: Boolean = false,
    val name: String = "",
    val host: String = "",
    val port: String = "",
    val tls: Boolean = false,
    val username: String = "",
    /** A new password to save. Empty keeps the saved one. */
    val password: String = "",
    val hasSavedPassword: Boolean = false,
    val nameError: String? = null,
    val hostError: String? = null,
    val portError: String? = null,
    val saving: Boolean = false,
    val done: Boolean = false,
)

@HiltViewModel
class WorldEditViewModel internal constructor(
    private val id: Long?,
    private val repository: WorldRepository,
) : ViewModel() {
    // Route decoding needs a real Bundle, so JVM tests pass the id directly.
    @Inject constructor(savedStateHandle: SavedStateHandle, repository: WorldRepository) :
        this(savedStateHandle.toRoute<WorldEditRoute>().id, repository)

    private val form = MutableStateFlow(WorldEditUiState(isNew = id == null, loading = id != null))
    private var loaded = id == null
    private var hadPassword = false

    val state: StateFlow<WorldEditUiState> = form
        .onStart { load() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), form.value)

    // Runs once; a later resubscription must not overwrite edits.
    private suspend fun load() {
        if (loaded || id == null) return
        loaded = true
        val world = repository.get(id)
        if (world == null) {
            form.update { it.copy(loading = false, done = true) }
            return
        }
        hadPassword = world.hasPassword
        form.update {
            it.copy(
                loading = false,
                name = world.name,
                host = world.host,
                port = world.port.toString(),
                tls = world.tls,
                username = world.username.orEmpty(),
                hasSavedPassword = world.hasPassword,
            )
        }
    }

    fun onName(value: String) = form.update { it.copy(name = value, nameError = null) }
    fun onHost(value: String) = form.update { it.copy(host = value, hostError = null) }
    fun onPort(value: String) {
        if (value.length <= 5 && value.all(Char::isDigit)) form.update { it.copy(port = value, portError = null) }
    }
    fun onTls(value: Boolean) = form.update { it.copy(tls = value) }
    fun onUsername(value: String) = form.update { it.copy(username = value) }
    fun onPassword(value: String) = form.update { it.copy(password = value) }
    fun onClearPassword() = form.update { it.copy(password = "", hasSavedPassword = false) }

    fun save() {
        val current = form.value
        if (current.loading || current.saving) return
        val checked = current.validated()
        if (checked.nameError != null || checked.hostError != null || checked.portError != null) {
            form.value = checked
            return
        }
        form.update { it.copy(saving = true) }
        val password = when {
            current.password.isNotEmpty() -> PasswordEdit.Set(current.password)
            hadPassword && !current.hasSavedPassword -> PasswordEdit.Clear
            else -> PasswordEdit.Keep
        }
        val world = World(
            id = id ?: 0,
            name = current.name.trim(),
            host = current.host.trim(),
            port = current.port.toInt(),
            tls = current.tls,
            username = current.username.trim().ifEmpty { null },
        )
        viewModelScope.launch {
            repository.save(world, password)
            form.update { it.copy(saving = false, done = true) }
        }
    }

    fun delete() {
        if (id == null) return
        viewModelScope.launch {
            repository.delete(id)
            form.update { it.copy(done = true) }
        }
    }
}

internal fun WorldEditUiState.validated(): WorldEditUiState {
    val host = host.trim()
    val port = port.toIntOrNull()
    return copy(
        nameError = if (name.isBlank()) "Required" else null,
        hostError = when {
            host.isEmpty() -> "Required"
            host.any { it.isWhitespace() || it == '/' } -> "Invalid host"
            else -> null
        },
        portError = when {
            this.port.isEmpty() -> "Required"
            port == null || port !in 1..65535 -> "1–65535"
            else -> null
        },
    )
}
