package io.github.adkimsm.neteasedownloader.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import io.github.adkimsm.neteasedownloader.R
import io.github.adkimsm.neteasedownloader.net.unlock.ProviderId
import io.github.adkimsm.neteasedownloader.data.SongEntity
import io.github.adkimsm.neteasedownloader.data.SongState
import io.github.adkimsm.neteasedownloader.ui.components.ErrorBanner
import io.github.adkimsm.neteasedownloader.ui.components.ListRow
import io.github.adkimsm.neteasedownloader.ui.components.ScreenScaffold
import io.github.adkimsm.neteasedownloader.ui.components.StateBadge
import io.github.adkimsm.neteasedownloader.ui.components.TrackSkeletonList
import io.github.adkimsm.neteasedownloader.ui.theme.LocalWindowSizing
import io.github.adkimsm.neteasedownloader.ui.theme.Motion
import io.github.adkimsm.neteasedownloader.ui.theme.Spacing
import io.github.adkimsm.neteasedownloader.ui.theme.StateInfo
import io.github.adkimsm.neteasedownloader.ui.theme.StateOk
import io.github.adkimsm.neteasedownloader.ui.theme.StateWarn
import io.github.adkimsm.neteasedownloader.ui.theme.TextDisabled
import io.github.adkimsm.neteasedownloader.ui.theme.TextPrimary
import io.github.adkimsm.neteasedownloader.ui.theme.TextSecondary

/**
 * 歌单曲目列表。
 *
 * 曲目行**无封面**(D13):本地文件没有专辑图,给每首歌显示封面就得逐首拉远端专辑图,
 * 与"简洁 + 省电"直接冲突。行内用状态徽标把"已下载 / 在线 / 无版权"说清楚。
 *
 * 扁平行:纯背景 + 1dp 分隔线,与首页列表同一语言。
 */
@Composable
fun PlaylistDetailScreen(
    title: String,
    tracks: List<SongEntity>,
    loading: Boolean,
    error: String?,
    pendingSongIds: Set<Long>,
    /** 已排队待删(远端那一半还没执行)的歌:行上标出来,但行仍可点 */
    pendingRemovalIds: Set<Long> = emptySet(),
    /** 在线播放开关:开着时 MISSING_URL 的歌仍可点(播放时去匹配第三方音源) */
    streamFallback: Boolean = false,
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

            // 骨架屏 -> 真实列表/空态:三种形态之间淡入淡出,不再硬切。
            // 骨架屏本身仍由 rememberDelayedVisibility 延迟出现(SkeletonDelayMs),
            // 这里只负责「已经显示之后」的切换,不影响快操作不闪屏的约定。
            Crossfade(
                targetState = listContentState(loading, tracks.isEmpty()),
                modifier = Modifier.weight(1f),
                animationSpec = tween(Motion.ContentDurationMs),
                label = "detailContent",
            ) { contentState ->
                when (contentState) {
                    // 骨架屏优先于空态:否则首次进入会先闪一下"没有歌曲"
                    ListContentState.Loading ->
                        TrackSkeletonList(modifier = Modifier.fillMaxSize())

                    ListContentState.Empty -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.detail_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }

                    ListContentState.Content -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(tracks, key = { _, song -> song.songId }) { index, song ->
                            TrackRow(
                                // 删歌后剩余行平滑上移,不再"啪"地跳一格。
                                // key 已是 songId,位置动画才有稳定的身份可依。
                                modifier = Modifier.animateItem(),
                                song = song,
                                streamFallback = streamFallback,
                                playable = isPlayable(song, streamFallback),
                                pending = song.songId in pendingSongIds,
                                pendingRemoval = song.songId in pendingRemovalIds,
                                // 最后一行不画分隔线,避免列表尾部多出一条线
                                showDivider = index < tracks.lastIndex,
                                onClick = { onPlayTrack(index) },
                                onActions = { onTrackActions(song.songId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 无网且未下载的歌:置灰且点不动,而不是点了才报错 */
/**
 * 曲目行能不能点。
 *
 * [streamFallback] 是「在线播放时替换音源」开关:下载开关关着时,灰歌会被同步标成
 * MISSING_URL,若这里不看播放开关,曲目行就会置灰且点不动 —— 播放开关等于白设。
 * 打开时行可点、徽标改为「第三方音源」;真匹配不到再由播放层给出错误文案。
 */
internal fun isPlayable(song: SongEntity, streamFallback: Boolean): Boolean =
    song.hasLocalFile || song.state != SongState.MISSING_URL.name || streamFallback

/**
 * 列表区的三种互斥形态。
 *
 * 歌单列表与曲目列表共用这一套(两者的加载反馈都是「骨架屏 → 空态 / 内容」):
 * 抽成枚举而不是在 Crossfade 里直接塞几个布尔量 —— 枚举让过渡规则一眼可读,
 * 也能被单测钉住(见 NavTransitionTest)。
 */
internal enum class ListContentState { Loading, Empty, Content }

/** 由加载态与数据量推导当前该显示哪个形态。 */
internal fun listContentState(loading: Boolean, isEmpty: Boolean): ListContentState = when {
    // 骨架屏优先于空态:否则首次进入会先闪一下"没有歌曲"
    loading && isEmpty -> ListContentState.Loading
    isEmpty -> ListContentState.Empty
    else -> ListContentState.Content
}

@Composable
private fun TrackRow(
    modifier: Modifier = Modifier,
    song: SongEntity,
    playable: Boolean,
    streamFallback: Boolean,
    pending: Boolean,
    pendingRemoval: Boolean,
    showDivider: Boolean,
    onClick: () -> Unit,
    onActions: () -> Unit,
) {
    val sizing = LocalWindowSizing.current
    ListRow(
        modifier = modifier,
        minHeight = sizing.menuRowHeight,
        enabled = playable && !pending,
        onClick = onClick,
        showDivider = showDivider,
        contentPadding = PaddingValues(start = sizing.gapSm),
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
        TrackBadge(song, streamFallback)
        if (pendingRemoval) {
            Spacer(Modifier.size(Spacing.xs))
            StateBadge(
                text = stringResource(R.string.detail_badge_pending_removal),
                color = StateWarn,
            )
        }
        sourceLabel(song.source)?.let { label ->
            Spacer(Modifier.size(Spacing.xs))
            StateBadge(text = label, color = StateInfo)
        }
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

/**
 * 状态徽标:色 + 文案双重编码,不单靠颜色。
 *
 * MISSING_URL 有两种呈现 —— 播放开关开着时它仍可播(去匹配第三方音源),
 * 此时写「第三方音源」而不是「无版权」,免得与「行可以点」自相矛盾。
 */
@Composable
private fun TrackBadge(song: SongEntity, streamFallback: Boolean) {
    when {
        song.hasLocalFile -> StateBadge(text = stringResource(R.string.detail_badge_local), color = StateOk)
        song.state == SongState.MISSING_URL.name && streamFallback -> StateBadge(
            text = stringResource(R.string.detail_badge_fallback),
            color = StateInfo,
        )
        song.state == SongState.MISSING_URL.name -> StateBadge(
            text = stringResource(R.string.detail_badge_missing),
            color = StateWarn,
        )
        else -> StateBadge(text = stringResource(R.string.detail_badge_online), color = StateInfo)
    }
}

/** 下载来源徽标:未知来源(含网易云原音源)不显示,不猜也不崩 */
@Composable
private fun sourceLabel(source: String?): String? = when (source) {
    ProviderId.KUWO -> stringResource(R.string.detail_source_kuwo)
    ProviderId.KUGOU -> stringResource(R.string.detail_source_kugou)
    else -> null
}
