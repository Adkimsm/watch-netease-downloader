package io.github.adkimsm.neteasedownloader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * 仅深色方案:手表上深色更省电,且无跟随系统亮色的需求。
 *
 * 风格:高级深色 + 圆角毛玻璃。配色偏暗红黑,控件统一走 [AppShapes] 圆角。
 */
private val MaterialShapes = Shapes(
    extraSmall = AppShapes.Row,
    small = AppShapes.Row,
    medium = AppShapes.Card,
    large = AppShapes.Control,
    extraLarge = AppShapes.Control,
)

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

    surfaceContainer = SurfaceLevel2,
    surfaceContainerHigh = SurfaceLevel3,
    surfaceContainerHighest = SurfaceLevel4,

    outline = Divider,
    outlineVariant = GlassHighlight,

    error = StateError,
    onError = TextPrimary,
    errorContainer = StateError.copy(alpha = 0.18f),
    onErrorContainer = StateError,

    secondary = TextSecondary,
    onSecondary = SurfaceLevel0,

    scrim = SurfaceLevel0,
)

@Composable
fun NeteaseDownloaderTheme(content: @Composable () -> Unit) {
    // 按窗口短边推导尺寸令牌并下发:所有页面取 LocalWindowSizing.current,
    // 保证 320px 小表与 372px 主力机型得到各自的合适密度。
    val sizing = rememberWindowSizing()
    CompositionLocalProvider(LocalWindowSizing provides sizing) {
        MaterialTheme(
            colorScheme = DarkColors,
            typography = AppTypography,
            shapes = MaterialShapes,
            content = content,
        )
    }
}
