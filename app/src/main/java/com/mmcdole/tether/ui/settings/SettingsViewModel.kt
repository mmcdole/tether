package com.mmcdole.tether.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mmcdole.tether.data.rules.ImportSummary
import com.mmcdole.tether.data.rules.NewerRulesFileException
import com.mmcdole.tether.data.rules.RuleJson
import com.mmcdole.tether.data.settings.AppSettings
import com.mmcdole.tether.data.settings.SettingsRepository
import com.mmcdole.tether.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val ruleJson: RuleJson,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val clock: Clock,
) : ViewModel() {
    /** Null until loaded. */
    val state: StateFlow<AppSettings?> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<String?>(null)

    /** The result of the last export or import, until shown. */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun setTextSize(sp: Int) = save { settings.setTextSize(sp) }

    fun setHideStatusBar(on: Boolean) = save { settings.setHideStatusBar(on) }

    fun setAutocorrect(on: Boolean) = save { settings.setAutocorrect(on) }

    fun setKeepKeyboardOpen(on: Boolean) = save { settings.setKeepKeyboardOpen(on) }

    fun setAutoReconnect(on: Boolean) = save { settings.setAutoReconnect(on) }

    fun setWifiLock(on: Boolean) = save { settings.setWifiLock(on) }

    fun setCpuWakeLock(on: Boolean) = save { settings.setCpuWakeLock(on) }

    fun setScrollbackLines(lines: Int) = save { settings.setScrollbackLines(lines) }

    fun setSaveLogs(on: Boolean) = save { settings.setSaveLogs(on) }

    fun exportFileName() = rulesFileName(LocalDate.now(clock))

    fun exportRules(uri: Uri) {
        viewModelScope.launch {
            _message.value = try {
                val json = ruleJson.export()
                withContext(io) {
                    // "wt" truncates when the picker hands back an existing file.
                    val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No stream")
                    out.bufferedWriter().use { it.write(json) }
                }
                "Rules exported"
            } catch (e: IOException) {
                "Couldn't save file"
            } catch (e: SecurityException) {
                "Couldn't save file"
            }
        }
    }

    fun importRules(uri: Uri) {
        viewModelScope.launch {
            _message.value = try {
                val json = withContext(io) {
                    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("No stream")
                    input.bufferedReader().use { it.readText() }
                }
                importMessage(ruleJson.import(json))
            } catch (e: IOException) {
                "Couldn't read file"
            } catch (e: SecurityException) {
                "Couldn't read file"
            } catch (e: NewerRulesFileException) {
                "Made by a newer Tether"
            } catch (e: IllegalArgumentException) {
                "Not a Tether rules file"
            }
        }
    }

    fun messageShown() {
        _message.value = null
    }

    private fun save(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

internal fun rulesFileName(date: LocalDate) = "tether-rules-$date.json"

/** "Imported 12 · 3 already present · 2 skipped · No world: Bard" */
internal fun importMessage(summary: ImportSummary): String = buildList {
    add("Imported ${summary.imported}")
    if (summary.alreadyPresent > 0) add("${summary.alreadyPresent} already present")
    if (summary.skipped > 0) add("${summary.skipped} skipped")
    if (summary.unknownWorlds.isNotEmpty()) add("No world: ${summary.unknownWorlds.joinToString()}")
}.joinToString(" · ")
