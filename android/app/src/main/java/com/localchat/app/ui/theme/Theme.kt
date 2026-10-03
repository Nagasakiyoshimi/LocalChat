package com.localchat.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF4F7CFF)

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Color(0xFFF6F7F9),
    surface = Color.White,
    surfaceVariant = Color(0xFFECEEF2),
    onBackground = Color(0xFF161A21),
    onSurface = Color(0xFF161A21),
    onSurfaceVariant = Color(0xFF6B7280),
    outline = Color(0xFFDDE1E7),
)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Color(0xFF111318),
    surface = Color(0xFF1E222B),
    surfaceVariant = Color(0xFF171A21),
    onBackground = Color(0xFFE8EAF0),
    onSurface = Color(0xFFE8EAF0),
    onSurfaceVariant = Color(0xFF8B93A4),
    outline = Color(0xFF2B303B),
)

@Composable
fun LocalChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
