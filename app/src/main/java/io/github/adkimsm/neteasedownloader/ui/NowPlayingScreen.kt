package io.github.adkimsm.neteasedownloader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import io.github.adkimsm.neteasedownloader.R
import io.github.adkimsm.neteasedownloader.data.SongEntity
import io.github.adkimsm.neteasedownloader.player.PlaybackError
import io.github.adkimsm.neteasedownloader.player.PlaybackState
import io.github.adkimsm.neteasedownloader.player.canSkipNext
import io.github.adkimsm.neteasedownloader.player.canSkipPrevious
import io.github.adkimsm.neteasedownloader.ui.components.ErrorBanner
import io.github.adkimsm.neteasedownloader.ui.components.ScreenScaffold
import io.github.adkimsm.neteasedownloader.ui.theme.BrandRed
import io.github.adkimsm.neteasedownloader.ui.theme.Dimens
import io.github.adkimsm.neteasedownloader.ui.theme.GlassHighlight
import io.github.adkimsm.neteasedownloader.ui.theme.LocalWindowSizing
import io.github.adkimsm.neteasedownloader.ui.theme.AppShapes
import io.github.adkimsm.neteasedownloader.ui.theme.Spacing
import io.github.adkimsm.neteasedownloader.ui.theme.SurfaceLevel2
import io.github.adkimsm.neteasedownloader.ui.theme.TextDisabled
import io.github.adkimsm.neteasedownloader.ui.theme.TextPrimary
import io.github.adkimsm.neteasedownloader.ui.theme.TextSecondary

/**
 * 极简播放页。
 *
 * 只有四块:页头(返回 + 更多)、歌名、歌手与来源角标、进度与三键。
 * 播放模式、随机、删除、红心、加入歌单、队列全部收进二级菜单 ——
 * 手表屏幕上每多一个控件,主控键就小一圈。
 *
 * **不显示封面**:下载回来的文件没有专辑图,为此去拉远端封面只会显示一张对不上的图。
 */
@Composable
fun NowPlayingScreen(
    state: PlaybackState,
    song: SongEntity?,
    onBack: () -> Unit,
    onMore: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onDismissError: () -> Unit = {},
) {
    val sizing = LocalWindowSizing.current

    ScreenScaffold(
        onBack = onBack,
        action = {
            IconButton(onClick = onMore, modifier = Modifier.size(sizing.touchTarget)) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.player_more),
                    tint = TextPrimary,
                    modifier = Modifier.size(sizing.iconSize),
                )
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            state.error?.let { error ->
                ErrorBanner(
                    message = playbackErrorText(error),
                    onDismiss = onDismissError,
                )
                Spacer(Modifier.height(sizing.gapSm))
            }

            Spacer(Modifier.height(sizing.gapMd))

            // 歌名/歌手整块可点,进二级菜单 —— 小屏上比页头那个图标好点得多
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(AppShapes.Card)
                    .background(SurfaceLevel2)
                    .border(Dimens.GlassBorder, GlassHighlight, AppShapes.Card)
                    .clickable(onClick = onMore)
                    .padding(vertical = sizing.gapSm, horizontal = sizing.gapSm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = song?.name ?: stringResource(R.string.player_nothing),
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = listOfNotNull(
                        song?.artist?.takeIf { it.isNotBlank() },
                        song?.let {
                            stringResource(
                                if (it.hasLocalFile) R.string.player_origin_local else R.string.player_origin_stream,
                            )
                        },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(sizing.gapMd))
            SeekBar(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                enabled = song != null,
                onSeek = onSeek,
            )
            Spacer(Modifier.height(sizing.gapMd))

            TransportRow(
                state = state,
                onTogglePlayPause = onTogglePlayPause,
                onSkipNext = onSkipNext,
                onSkipPrevious = onSkipPrevious,
            )
        }
    }
}

/** 进度条 + 两端时间。拖动时用本地值,松手才 seek,避免被 500ms 轮询拽回去 */
@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
) {
    val sizing = LocalWindowSizing.current
    var dragging by remember { mutableFloatStateOf(-1f) }
    val fraction = when {
        dragging >= 0f -> dragging
        durationMs > 0L -> (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        else -> 0f
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                if (durationMs > 0L) onSeek((durationMs * dragging).toLong())
                dragging = -1f
            },
            enabled = enabled && durationMs > 0L,
            colors = SliderDefaults.colors(
                thumbColor = BrandRed,
                activeTrackColor = BrandRed,
                inactiveTrackColor = TextDisabled,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = sizing.seekBarHeight),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = formatDuration(if (dragging >= 0f) (durationMs * dragging).toLong() else positionMs),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatDuration(durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}

/** 三键。主控键固定可见,不放进任何可滚动区(矮屏上被顶出屏外就再也点不到了) */
@Composable
private fun TransportRow(
    state: PlaybackState,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
) {
    val sizing = LocalWindowSizing.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportButton(
            icon = Icons.Filled.SkipPrevious,
            description = stringResource(R.string.player_previous),
            enabled = canSkipPrevious(state.queueSize),
            onClick = onSkipPrevious,
        )
        Box(
            modifier = Modifier
                .size(sizing.playerControl)
                .clip(AppShapes.Circle)
                .background(BrandRed)
                .border(2.dp, GlassHighlight, AppShapes.Circle)
                .clickable(onClick = onTogglePlayPause),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (state.isPlaying) R.string.player_pause else R.string.player_play,
                ),
                tint = TextPrimary,
                modifier = Modifier.size(sizing.iconSize * 1.4f),
            )
        }
        TransportButton(
            icon = Icons.Filled.SkipNext,
            description = stringResource(R.string.player_next),
            enabled = canSkipNext(state.queueIndex, state.queueSize, state.repeat),
            onClick = onSkipNext,
        )
    }
}

@Composable
private fun TransportButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val sizing = LocalWindowSizing.current
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(sizing.touchTarget),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) TextPrimary else TextDisabled,
            modifier = Modifier.size(sizing.iconSize),
        )
    }
}

/** 播放失败的中文短句。文案集中在 UI 层,方便按档位压缩 */
@Composable
internal fun playbackErrorText(error: PlaybackError): String = when (error) {
    PlaybackError.NO_URL -> stringResource(R.string.player_error_no_url)
    PlaybackError.FILE_GONE -> stringResource(R.string.player_error_file_gone)
    PlaybackError.NETWORK -> stringResource(R.string.player_error_network)
    PlaybackError.UNKNOWN -> stringResource(R.string.player_error_unknown)
}

