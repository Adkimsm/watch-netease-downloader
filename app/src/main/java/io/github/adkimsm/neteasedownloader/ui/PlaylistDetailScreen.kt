package io.github.adkimsm.neteasedownloader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import io.github.adkimsm.neteasedownloader.R
import io.github.adkimsm.neteasedownloader.data.SongEntity
import io.github.adkimsm.neteasedownloader.data.SongState
import io.github.adkimsm.neteasedownloader.ui.components.ErrorBanner
import io.github.adkimsm.neteasedownloader.ui.components.ScreenScaffold
import io.github.adkimsm.neteasedownloader.ui.components.StateBadge
import io.github.adkimsm.neteasedownloader.ui.components.TrackSkeletonList
import io.github.adkimsm.neteasedownloader.ui.theme.Dimens
import io.github.adkimsm.neteasedownloader.ui.theme.GlassHighlight
import io.github.adkimsm.neteasedownloader.ui.theme.LocalWindowSizing
import io.github.adkimsm.neteasedownloader.ui.theme.AppShapes
import io.github.adkimsm.neteasedownloader.ui.theme.Spacing
import io.github.adkimsm.neteasedownloader.ui.theme.StateInfo
import io.github.adkimsm.neteasedownloader.ui.theme.StateOk
import io.github.adkimsm.neteasedownloader.ui.theme.StateWarn
import io.github.adkimsm.neteasedownloader.ui.theme.SurfaceLevel2
import io.github.adkimsm.neteasedownloader.ui.theme.TextDisabled
import io.github.adkimsm.neteasedownloader.ui.theme.TextPrimary
import io.github.adkimsm.neteasedownloader.ui.theme.TextSecondary

/**
 * 歌单曲目列表。
 *
 * 曲目行**无封面**(D13):本地文件没有专辑图,给每首歌显示封面就得逐首拉远端专辑图,
 * 与"简洁 + 省电"直接冲突。行内用状态徽标把"已下载 / 在线 / 无版权"说清楚。
 *
 * 毛玻璃卡片行:圆角 + 1dp 高光描边,与首页列表同一语言。
 */
@Composable
fun PlaylistDetailScreen(
    title: String,
    tracks: List<SongEntity>,
    loading: Boolean,
    error: String?,
    pendingSongIds: Set<Long>,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onPlayTrack: (Int) -> Unit,
    onTrackActions: (Long) -> Unit,
    action: (@Composable () -> Unit)? = null,
) {
    val sizing = LocalWindowSizing.current

    ScreenScaffold(title = title, onBack = onBack, action = action) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (error != null) {
                ErrorBanner(message = error, onRetry = onRetry, onDismiss = onDismissError)
                Spacer(Modifier.height(sizing.gapSm))
            }

            when {
                // 骨架屏优先于空态:否则首次进入会先闪一下"没有歌曲"
                loading && tracks.isEmpty() -> TrackSkeletonList(modifier = Modifier.weight(1f))

                tracks.isEmpty() -> Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.detail_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Dimens.CardSpacing),
                ) {
                    itemsIndexed(tracks, key = { _, song -> song.songId }) { index, song ->
                        TrackRow(
                            song = song,
                            playable = isPlayable(song),
                            pending = song.songId in pendingSongIds,
                            onClick = { onPlayTrack(index) },
                            onActions = { onTrackActions(song.songId) },
                        )
                    }
                }
            }
        }
    }
}

/** 无网且未下载的歌:置灰且点不动,而不是点了才报错 */
internal fun isPlayable(song: SongEntity): Boolean =
    song.hasLocalFile || song.state != SongState.MISSING_URL.name

@Composable
private fun TrackRow(
    song: SongEntity,
    playable: Boolean,
    pending: Boolean,
    onClick: () -> Unit,
    onActions: () -> Unit,
) {
    val sizing = LocalWindowSizing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = sizing.menuRowHeight)
            .clip(AppShapes.Row)
            .background(SurfaceLevel2)
            .border(Dimens.GlassBorder, GlassHighlight, AppShapes.Row)
            .clickable(enabled = playable && !pending, onClick = onClick)
            .padding(start = sizing.gapSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (playable) TextPrimary else TextDisabled,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(song.artist, formatDuration(song.duration))
                    .filter { it.isNotBlank() && it != "--:--" }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.size(Spacing.xs))
        TrackBadge(song)
        IconButton(onClick = onActions, modifier = Modifier.size(sizing.touchTarget)) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.detail_track_actions),
                tint = TextSecondary,
                modifier = Modifier.size(sizing.iconSize),
            )
        }
    }
}

/** 状态徽标:色 + 文案双重编码,不单靠颜色 */
@Composable
private fun TrackBadge(song: SongEntity) {
    when {
        song.hasLocalFile -> StateBadge(text = stringResource(R.string.detail_badge_local), color = StateOk)
        song.state == SongState.MISSING_URL.name -> StateBadge(
            text = stringResource(R.string.detail_badge_missing),
            color = StateWarn,
        )
        else -> StateBadge(text = stringResource(R.string.detail_badge_online), color = StateInfo)
    }
}
