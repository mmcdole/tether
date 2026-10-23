package com.mmcdole.tether.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private val TetherColorScheme = darkColorScheme(
    primary = TetherColors.Accent,
    onPrimary = TetherColors.Text,
    secondary = TetherColors.AccentText,
    background = TetherColors.Background,
    onBackground = TetherColors.Text,
    surface = TetherColors.Background,
    onSurface = TetherColors.Text,
    surfaceVariant = TetherColors.Surface,
    onSurfaceVariant = TetherColors.TextDim,
    surfaceContainer = TetherColors.Surface,
    surfaceContainerLow = TetherColors.Chrome,
    surfaceContainerHigh = TetherColors.SurfaceRaised,
    outline = TetherColors.Line,
    outlineVariant = TetherColors.Line,
    error = TetherColors.Danger,
)

/** Tether has one look: black background, light text. */
@Composable
fun TetherTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TetherColorScheme) {
        CompositionLocalProvider(LocalContentColor provides TetherColors.Text, content = content)
    }
}
