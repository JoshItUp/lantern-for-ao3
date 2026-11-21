package app.ao3reader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val NearBlackBackground = Color(0xFF0B0B0C)
val NearBlackSurface = Color(0xFF141416)
/** Warm orange for AO3's archive warnings and failed downloads. */
val WarningColor = Color(0xFFE8A27C)

/** Dark-only Material 3 theme: near-black base with dynamic-colour accents. */
@Composable
fun Ao3Theme(content: @Composable () -> Unit) {
    val dynamic = dynamicDarkColorScheme(LocalContext.current)
    val colors = darkColorScheme(
        background = NearBlackBackground,
        surface = NearBlackSurface,
        primary = dynamic.primary,
        onPrimary = dynamic.onPrimary,
        primaryContainer = dynamic.primaryContainer,
        onPrimaryContainer = dynamic.onPrimaryContainer,
        secondary = dynamic.secondary,
        onSecondary = dynamic.onSecondary,
        secondaryContainer = dynamic.secondaryContainer,
        onSecondaryContainer = dynamic.onSecondaryContainer,
    )
    MaterialTheme(colorScheme = colors, content = content)
}
