package io.github.adkimsm.neteasedownloader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NeteaseRed = Color(0xFFC20C0C)

private val DarkColors = darkColorScheme(
    primary = NeteaseRed,
    onPrimary = Color.White,
    background = Color.Black,
    onBackground = Color(0xFFE6E1E5),
    surface = Color.Black,
    onSurface = Color(0xFFE6E1E5),
)

@Composable
fun NeteaseDownloaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
