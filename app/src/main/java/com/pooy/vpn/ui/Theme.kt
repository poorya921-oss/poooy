package com.pooy.vpn.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Navy = Color(0xFF0B1220)
private val Surface = Color(0xFF121B2E)
private val SurfaceHigh = Color(0xFF1A2540)
private val Blue = Color(0xFF3B82F6)
private val TextMain = Color(0xFFE6EBF5)
private val TextMuted = Color(0xFF8A97B0)
private val Outline = Color(0xFF2B3856)

val LatencyGood = Color(0xFF34D399)
val LatencyWarn = Color(0xFFF59E0B)
val LatencyBad = Color(0xFFEF4444)

private val PooyColors = darkColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1D3A7A),
    onPrimaryContainer = TextMain,
    secondary = Blue,
    background = Navy,
    onBackground = TextMain,
    surface = Surface,
    onSurface = TextMain,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = TextMuted,
    surfaceContainerLowest = Navy,
    surfaceContainerLow = Surface,
    surfaceContainer = Surface,
    surfaceContainerHigh = SurfaceHigh,
    surfaceContainerHighest = SurfaceHigh,
    outline = Outline,
    outlineVariant = Outline,
    error = LatencyBad,
)

private val PooyShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

/** Dark-only by design (deep navy + blue accent). */
@Composable
fun PooyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PooyColors, shapes = PooyShapes, content = content)
}
