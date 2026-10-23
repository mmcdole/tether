package com.mmcdole.tether.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mmcdole.tether.data.settings.AppSettings
import com.mmcdole.tether.ui.session.ignoresBatteryOptimization
import com.mmcdole.tether.ui.session.mudTextStyle
import com.mmcdole.tether.ui.session.requestIgnoreBatteryOptimization
import com.mmcdole.tether.ui.theme.AnsiColors
import com.mmcdole.tether.ui.theme.TetherColors
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = TetherColors.Background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TetherColors.Background,
                    titleContentColor = TetherColors.Text,
                    navigationIconContentColor = TetherColors.Text,
                ),
            )
        },
    ) { padding ->
        val settings = state ?: return@Scaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
        ) {
            Section("Display")
            TextSize(settings.textSize, viewModel::setTextSize)
            SwitchRow("Hide status bar", settings.hideStatusBar, viewModel::setHideStatusBar)

            Section("Keyboard")
            SwitchRow("Autocorrect", settings.autocorrect, viewModel::setAutocorrect)
            SwitchRow("Keep open after send", settings.keepKeyboardOpen, viewModel::setKeepKeyboardOpen)

            Section("Connection")
            SwitchRow("Reconnect automatically", settings.autoReconnect, viewModel::setAutoReconnect)
            SwitchRow("Keep Wi-Fi awake", settings.wifiLock, viewModel::setWifiLock)
            SwitchRow("Keep CPU awake", settings.cpuWakeLock, viewModel::setCpuWakeLock)
            BatteryOptimization()

            Section("Data")
            Scrollback(settings.scrollbackLines, viewModel::setScrollbackLines)
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.08.em,
        color = TetherColors.AccentText,
    )
}

@Composable
private fun SettingRow(label: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 16.sp)
        trailing()
    }
}

@Composable
private fun Value(text: String) = Text(text, fontSize = 15.sp, color = TetherColors.TextDim)

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(
        label,
        Modifier.selectable(selected = checked, role = Role.Switch, onClick = { onChange(!checked) }),
    ) {
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TetherColors.Text,
                checkedTrackColor = TetherColors.Accent,
                uncheckedThumbColor = TetherColors.TextDim,
                uncheckedTrackColor = TetherColors.Surface,
                uncheckedBorderColor = TetherColors.Line,
            ),
        )
    }
}

@Composable
private fun TextSize(saved: Int, onChange: (Int) -> Unit) {
    // The thumb follows the finger; each whole step is saved as it's reached.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val size = dragging?.roundToInt() ?: saved
    SettingRow("Text size") { Value("$size") }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("${AppSettings.MIN_TEXT_SIZE}", fontSize = 13.sp, color = TetherColors.TextDim)
        Slider(
            value = dragging ?: saved.toFloat(),
            onValueChange = {
                dragging = it
                if (it.roundToInt() != size) onChange(it.roundToInt())
            },
            onValueChangeFinished = { dragging = null },
            valueRange = AppSettings.MIN_TEXT_SIZE.toFloat()..AppSettings.MAX_TEXT_SIZE.toFloat(),
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = TetherColors.AccentText,
                activeTrackColor = TetherColors.AccentText,
                inactiveTrackColor = TetherColors.Line,
            ),
        )
        Text("${AppSettings.MAX_TEXT_SIZE}", fontSize = 13.sp, color = TetherColors.TextDim)
    }
    val shape = RoundedCornerShape(8.dp)
    Text(
        PreviewText,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .border(1.dp, TetherColors.Line, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        style = mudTextStyle(size),
        softWrap = false,
    )
}

private val PreviewText = buildAnnotatedString {
    withStyle(SpanStyle(color = AnsiColors.palette[11], fontWeight = FontWeight.Bold)) { append("The North Gate") }
    append("\n")
    withStyle(SpanStyle(color = AnsiColors.palette[2])) { append("    Obvious exits: north, south") }
}

@Composable
private fun BatteryOptimization() {
    val context = LocalContext.current
    var unrestricted by remember { mutableStateOf(ignoresBatteryOptimization(context)) }
    // The exemption is granted in a system dialog, so check again on the way back.
    LifecycleResumeEffect(Unit) {
        unrestricted = ignoresBatteryOptimization(context)
        onPauseOrDispose {}
    }
    SettingRow(
        "Battery optimization",
        if (unrestricted) Modifier else Modifier.clickable { requestIgnoreBatteryOptimization(context) },
    ) { Value(if (unrestricted) "Unrestricted" else "Optimized") }
}

@Composable
private fun Scrollback(lines: Int, onChange: (Int) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    SettingRow("Scrollback", Modifier.clickable { choosing = true }) { Value(linesLabel(lines)) }
    if (!choosing) return
    AlertDialog(
        onDismissRequest = { choosing = false },
        title = { Text("Scrollback") },
        text = {
            Column {
                for (choice in AppSettings.SCROLLBACK_CHOICES) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = choice == lines, role = Role.RadioButton) {
                                choosing = false
                                onChange(choice)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = choice == lines,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = TetherColors.AccentText),
                        )
                        Text(linesLabel(choice), modifier = Modifier.padding(start = 12.dp), fontSize = 16.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { choosing = false }) { Text("Cancel", color = TetherColors.Text) }
        },
        containerColor = TetherColors.SurfaceRaised,
        titleContentColor = TetherColors.Text,
        textContentColor = TetherColors.Text,
    )
}

private fun linesLabel(lines: Int) = "%,d lines".format(lines)
