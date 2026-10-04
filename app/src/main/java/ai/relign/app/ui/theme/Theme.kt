package ai.relign.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = EmeraldPrimary,
    onPrimary = ObsidianBase,
    primaryContainer = EmeraldDark,
    onPrimaryContainer = EmeraldTertiary,
    secondary = EmeraldSecondary,
    onSecondary = ObsidianBase,
    background = ObsidianBase,
    onBackground = TextPrimary,
    surface = SurfaceContainerLow,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceContainer,
    onSurfaceVariant = TextSecondary,
    outline = HairlineBorder,
    outlineVariant = HairlineBorderLight
)

@Composable
fun RelignTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
