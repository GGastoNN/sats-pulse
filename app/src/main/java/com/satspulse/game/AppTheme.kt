package com.satspulse.game

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SatsColors = darkColorScheme(
    primary = Color(0xFF20E3B2),
    secondary = Color(0xFF7C5CFF),
    tertiary = Color(0xFFFF4D8D),
    background = Color(0xFF050713),
    surface = Color(0xFF0C1022),
    onBackground = Color(0xFFF7F8FF),
    onSurface = Color(0xFFF7F8FF)
)

@Composable
fun SatsPulseTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SatsColors, content = content)
}
