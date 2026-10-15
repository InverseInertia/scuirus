package pantry.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Dark wood: walnut backgrounds, oak and amber accents, cream text. */
private val WoodColors = darkColorScheme(
    primary = Color(0xFFD9A066),
    onPrimary = Color(0xFF2B1A0C),
    primaryContainer = Color(0xFF6B4423),
    onPrimaryContainer = Color(0xFFF6DDBF),
    secondary = Color(0xFFB88A5E),
    onSecondary = Color(0xFF2B1A0C),
    secondaryContainer = Color(0xFF4A3220),
    onSecondaryContainer = Color(0xFFEBD3B5),
    tertiary = Color(0xFF9DB07A),
    onTertiary = Color(0xFF1F2A10),
    background = Color(0xFF1C130C),
    onBackground = Color(0xFFEFE2D0),
    surface = Color(0xFF261A11),
    onSurface = Color(0xFFEFE2D0),
    surfaceVariant = Color(0xFF3A2A1D),
    onSurfaceVariant = Color(0xFFD2BFA8),
    surfaceContainer = Color(0xFF2E2016),
    surfaceContainerHigh = Color(0xFF38281C),
    surfaceContainerHighest = Color(0xFF433123),
    outline = Color(0xFF8F7458),
    outlineVariant = Color(0xFF55402E),
    error = Color(0xFFFF8A75),
    onError = Color(0xFF3A0A02),
)

@Composable
fun ScuirusTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = WoodColors, content = content)
