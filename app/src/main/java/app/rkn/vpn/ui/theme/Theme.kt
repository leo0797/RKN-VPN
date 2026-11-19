package app.rkn.vpn.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9C8CFF),
    onPrimary = Color(0xFF160B3A),
    primaryContainer = Color(0xFF33265F),
    onPrimaryContainer = Color(0xFFE9E3FF),
    secondary = Color(0xFF4ADE80),
    onSecondary = Color(0xFF05240F),
    secondaryContainer = Color(0xFF14401F),
    onSecondaryContainer = Color(0xFFC7F9D6),
    tertiary = Color(0xFF56C8F5),
    onTertiary = Color(0xFF00202B),
    background = Color(0xFF080B12),
    onBackground = Color(0xFFE7EAF3),
    surface = Color(0xFF111725),
    onSurface = Color(0xFFE7EAF3),
    surfaceVariant = Color(0xFF1B2334),
    onSurfaceVariant = Color(0xFFA7B0C4),
    surfaceContainer = Color(0xFF161E2E),
    surfaceContainerHigh = Color(0xFF1C2537),
    outline = Color(0xFF3A4459),
    outlineVariant = Color(0xFF232C3E),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0505),
    errorContainer = Color(0xFF4A1414),
    onErrorContainer = Color(0xFFFFD9D9)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B4BE0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6E1FF),
    onPrimaryContainer = Color(0xFF1B1147),
    secondary = Color(0xFF15803D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7F5DF),
    onSecondaryContainer = Color(0xFF05240F),
    tertiary = Color(0xFF0369A1),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF5F6FB),
    onBackground = Color(0xFF11151F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF11151F),
    surfaceVariant = Color(0xFFEDEFF7),
    onSurfaceVariant = Color(0xFF5A6377),
    surfaceContainer = Color(0xFFF0F2F9),
    surfaceContainerHigh = Color(0xFFE8EBF4),
    outline = Color(0xFFC3C9D8),
    outlineVariant = Color(0xFFDDE1EC),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B)
)

/** "system" | "light" | "dark" */
@Composable
fun RknTheme(theme: String, content: @Composable () -> Unit) {
    val dark = when (theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content
    )
}
