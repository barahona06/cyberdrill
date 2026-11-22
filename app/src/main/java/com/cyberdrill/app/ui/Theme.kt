package com.cyberdrill.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CyberScheme = lightColorScheme(
    primary = Color(0xFF0B3D2E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E8DF),
    onPrimaryContainer = Color(0xFF0B3D2E),
    secondary = Color(0xFFB7791F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF7E7C3),
    onSecondaryContainer = Color(0xFF4A3200),
    surface = Color(0xFFFAFAF7),
    onSurface = Color(0xFF1A1C1A),
    surfaceVariant = Color(0xFFE8EAE6),
    onSurfaceVariant = Color(0xFF454A46)
)

@Composable
fun CyberDrillTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CyberScheme, content = content)
}
