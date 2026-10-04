package nl.family7.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Hetzelfde donkerblauw met Family7-rood als de TV-app. */
private val Family7Colors = darkColorScheme(
    primary = Family7Red,
    onPrimary = Color.White,
    primaryContainer = Family7Blue,
    onPrimaryContainer = Color.White,
    secondary = Family7RedLight,
    onSecondary = Color.White,
    secondaryContainer = DarkSurfaceVariant,
    onSecondaryContainer = Color.White,
    tertiary = Family7Green,
    onTertiary = Color.White,
    background = Family7BlueDark,
    onBackground = TextPrimary,
    surface = Family7BlueDark,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkSurfaceVariant,
    surfaceContainerLow = DarkSurface,
    outline = TextMuted
)

@Composable
fun Family7MobileTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Family7Colors, content = content)
}
