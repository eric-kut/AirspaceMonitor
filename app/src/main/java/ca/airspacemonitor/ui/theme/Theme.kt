package ca.airspacemonitor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF0055C8),
    secondary = Color(0xFF0B57D0),
    tertiary = Color(0xFF00695C),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9AC1FF),
    secondary = Color(0xFF8FB8FF),
    tertiary = Color(0xFF4FDAC5),
)

@Composable
fun AirspaceMonitorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}