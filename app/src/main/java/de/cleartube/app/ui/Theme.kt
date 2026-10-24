package de.cleartube.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B4BDB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4DFFF),
    onPrimaryContainer = Color(0xFF16005F),
    secondary = Color(0xFFFF5C7A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9DF),
    onSecondaryContainer = Color(0xFF40000F),
    tertiary = Color(0xFF00A7A0),
    background = Color(0xFFFAF9FF),
    onBackground = Color(0xFF1B1B24),
    surface = Color(0xFFFAF9FF),
    onSurface = Color(0xFF1B1B24),
    surfaceVariant = Color(0xFFE6E3F2),
    onSurfaceVariant = Color(0xFF47464F),
    outline = Color(0xFF787680),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F1FC),
    surfaceContainer = Color(0xFFEDEBF7),
    surfaceContainerHigh = Color(0xFFE7E5F1),
    surfaceContainerHighest = Color(0xFFE1DFEC)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9B0FF),
    onPrimary = Color(0xFF2A1A94),
    primaryContainer = Color(0xFF4130BB),
    onPrimaryContainer = Color(0xFFE4DFFF),
    secondary = Color(0xFFFF8CA0),
    onSecondary = Color(0xFF670020),
    secondaryContainer = Color(0xFF8B1536),
    onSecondaryContainer = Color(0xFFFFD9DF),
    tertiary = Color(0xFF4DDAD2),
    background = Color(0xFF0F0F17),
    onBackground = Color(0xFFE5E1EC),
    surface = Color(0xFF0F0F17),
    onSurface = Color(0xFFE5E1EC),
    surfaceVariant = Color(0xFF47464F),
    onSurfaceVariant = Color(0xFFC8C5D1),
    outline = Color(0xFF928F9A),
    surfaceContainerLowest = Color(0xFF0A0A11),
    surfaceContainerLow = Color(0xFF16161F),
    surfaceContainer = Color(0xFF1B1B25),
    surfaceContainerHigh = Color(0xFF252530),
    surfaceContainerHighest = Color(0xFF30303B)
)

@Composable
fun ClearTubeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors
    ) {
        // Surface setzt die Standard-Textfarbe (sonst ist Text im Dunkelmodus schwarz auf dunkel)
        androidx.compose.material3.Surface(
            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            content = content
        )
    }
}
