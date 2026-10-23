package com.mmcdole.tether.ui.worlds

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mmcdole.tether.ui.theme.MudFont
import com.mmcdole.tether.ui.theme.TetherColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldEditScreen(onDone: () -> Unit, viewModel: WorldEditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val username = remember { FocusRequester() }

    LaunchedEffect(state.done) { if (state.done) onDone() }

    Scaffold(
        containerColor = TetherColors.Background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "New world" else "Edit world", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::save, enabled = !state.loading && !state.saving) {
                        Text("Save", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TetherColors.AccentText)
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
        if (state.loading) return@Scaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Field("Name", state.name, viewModel::onName, error = state.nameError, mono = false, capitalize = true)
            Field("Host", state.host, viewModel::onHost, error = state.hostError, keyboardType = KeyboardType.Uri)
            Field(
                "Port",
                state.port,
                viewModel::onPort,
                error = state.portError,
                keyboardType = KeyboardType.Number,
                // Skip the TLS switch so Next keeps the keyboard up.
                onImeNext = { username.requestFocus() },
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("TLS", fontSize = 16.sp)
                Switch(
                    checked = state.tls,
                    onCheckedChange = viewModel::onTls,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TetherColors.Text,
                        checkedTrackColor = TetherColors.Accent,
                        uncheckedThumbColor = TetherColors.TextDim,
                        uncheckedTrackColor = TetherColors.Surface,
                        uncheckedBorderColor = TetherColors.Line,
                    ),
                )
            }

            GroupLabel("Login")
            Field("Username", state.username, viewModel::onUsername, modifier = Modifier.focusRequester(username))
            Field(
                "Password",
                state.password,
                viewModel::onPassword,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
                onImeDone = viewModel::save,
                hidden = true,
                placeholder = if (state.hasSavedPassword) "Saved" else null,
                trailing = if (state.hasSavedPassword || state.password.isNotEmpty()) {
                    { TextButton(onClick = viewModel::onClearPassword) { Text("Clear", color = TetherColors.AccentText) } }
                } else {
                    null
                },
            )

            if (!state.isNew) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    border = BorderStroke(1.dp, TetherColors.DangerBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TetherColors.Danger),
                ) {
                    Text("Delete world", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete world?") },
            text = { Text(state.name, color = TetherColors.TextDim) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete()
                }) { Text("Delete", color = TetherColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = TetherColors.Text) }
            },
            containerColor = TetherColors.SurfaceRaised,
            titleContentColor = TetherColors.Text,
            textContentColor = TetherColors.TextDim,
        )
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text.uppercase(),
        modifier = Modifier.padding(top = 8.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        color = TetherColors.AccentText,
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    mono: Boolean = true,
    capitalize: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeDone: () -> Unit = {},
    onImeNext: (() -> Unit)? = null,
    hidden: Boolean = false,
    placeholder: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label.uppercase(), fontSize = 12.sp, letterSpacing = 1.sp, color = TetherColors.TextDim)
            error?.let { Text(it, fontSize = 12.sp, color = TetherColors.Danger) }
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth(),
            singleLine = true,
            isError = error != null,
            textStyle = TextStyle(fontSize = 16.sp, fontFamily = if (mono) MudFont else null),
            placeholder = placeholder?.let { { Text(it, fontSize = 16.sp, color = TetherColors.TextDim) } },
            trailingIcon = trailing,
            visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                capitalization = if (capitalize) KeyboardCapitalization.Words else KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = keyboardType,
                imeAction = imeAction,
            ),
            keyboardActions = KeyboardActions(onDone = { onImeDone() }, onNext = onImeNext?.let { next -> { next() } }),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TetherColors.Text,
                unfocusedTextColor = TetherColors.Text,
                focusedContainerColor = TetherColors.Background,
                unfocusedContainerColor = TetherColors.Background,
                errorContainerColor = TetherColors.Background,
                focusedBorderColor = TetherColors.Accent,
                unfocusedBorderColor = TetherColors.Line,
                errorBorderColor = TetherColors.Danger,
                cursorColor = TetherColors.AccentText,
                errorCursorColor = TetherColors.AccentText,
            ),
        )
    }
}
