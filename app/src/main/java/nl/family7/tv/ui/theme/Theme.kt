package nl.family7.tv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Family7Red,
    onPrimary = Color.White,
    primaryContainer = Family7Blue,
    onPrimaryContainer = Color.White,
    secondary = Family7RedLight,
    onSecondary = Color.White,
    tertiary = Family7Green,
    onTertiary = Color.White,
    background = Family7BlueDark,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary
)

@Composable
fun Family7TVTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
