package com.mmcdole.tether.ui.session

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mmcdole.tether.data.session.SessionManager
import com.mmcdole.tether.data.session.SessionState
import com.mmcdole.tether.data.settings.SettingsRepository
import com.mmcdole.tether.service.SessionNotifications
import com.mmcdole.tether.ui.theme.TetherColors
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FirstRunAsked(val notifications: Boolean, val battery: Boolean)

@HiltViewModel
class FirstRunViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val session: SessionManager,
    private val notifications: SessionNotifications,
) : ViewModel() {
    /** Null until loaded. */
    val asked: StateFlow<FirstRunAsked?> = combine(settings.askedNotifications, settings.askedBattery, ::FirstRunAsked)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun notificationsAsked(granted: Boolean = false) {
        // The ongoing notification was posted before the grant, and Android dropped it.
        val state = session.state.value
        if (granted && state != SessionState.Idle) notifications.update(state)
        viewModelScope.launch { settings.setAskedNotifications() }
    }

    fun batteryAsked() {
        viewModelScope.launch { settings.setAskedBattery() }
    }
}

/** On the first session: the notification permission (Android 13+), then battery optimization. Each is asked once. */
@Composable
fun FirstRunPrompts(viewModel: FirstRunViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.notificationsAsked(granted)
    }
    val asked by viewModel.asked.collectAsStateWithLifecycle()
    val current = asked ?: return
    when {
        !current.notifications -> LaunchedEffect(Unit) {
            if (needsNotificationPermission(context)) {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.notificationsAsked()
            }
        }
        !current.battery -> if (ignoresBatteryOptimization(context)) {
            LaunchedEffect(Unit) { viewModel.batteryAsked() }
        } else {
            AlertDialog(
                onDismissRequest = viewModel::batteryAsked,
                title = { Text("Keep Tether connected?") },
                text = { Text("Exempt it from battery optimization.") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.batteryAsked()
                        requestIgnoreBatteryOptimization(context)
                    }) { Text("Allow", color = TetherColors.AccentText) }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::batteryAsked) { Text("Not now", color = TetherColors.Text) }
                },
                containerColor = TetherColors.SurfaceRaised,
                titleContentColor = TetherColors.Text,
                textContentColor = TetherColors.TextDim,
            )
        }
    }
}

private fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

internal fun ignoresBatteryOptimization(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

// A persistent connection is this app's whole purpose, which is what this request is for.
@SuppressLint("BatteryLife")
internal fun requestIgnoreBatteryOptimization(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
    runCatching { context.startActivity(intent) }
}
