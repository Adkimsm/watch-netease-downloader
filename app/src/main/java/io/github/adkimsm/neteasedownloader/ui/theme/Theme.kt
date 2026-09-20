package io.github.adkimsm.neteasedownloader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * 仅深色方案:手表上深色更省电,且无跟随系统亮色的需求。
 */
private val DarkColors = darkColorScheme(
    primary = BrandRed,
    onPrimary = TextPrimary,
    primaryContainer = BrandRedMuted,
    onPrimaryContainer = TextPrimary,

    background = SurfaceLevel0,
    onBackground = TextPrimary,

    surface = SurfaceLevel1,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceLevel2,
    onSurfaceVariant = TextSecondary,

    outline = Divider,

    error = StateError,
    onError = TextPrimary,
)

@Composable
fun NeteaseDownloaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        content = content,
    )
}
