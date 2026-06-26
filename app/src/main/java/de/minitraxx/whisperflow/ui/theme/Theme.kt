package de.minitraxx.whisperflow.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val darkColors = darkColorScheme(
    primary = Color(0xFF0A84FF),
    secondary = Color(0xFF30D158),
    background = Color(0xFF000000),
    surface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFF2C2C2E),
    onBackground = Color.White,
    onSurface = Color.White,
    error = Color(0xFFFF453A)
)

@Composable
fun WhisperFlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColors, content = content)
}
