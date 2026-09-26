package io.github.adkimsm.neteasedownloader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
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
import io.github.adkimsm.neteasedownloader.data.PlaylistEntity
import io.github.adkimsm.neteasedownloader.ui.components.ErrorBanner
import io.github.adkimsm.neteasedownloader.ui.components.ListRow
import io.github.adkimsm.neteasedownloader.ui.components.PlaylistRow
import io.github.adkimsm.neteasedownloader.ui.components.PlaylistSkeletonList
import io.github.adkimsm.neteasedownloader.ui.components.ScreenScaffold
import io.github.adkimsm.neteasedownloader.ui.theme.BrandRed
import io.github.adkimsm.neteasedownloader.ui.theme.LocalWindowSizing
import io.github.adkimsm.neteasedownloader.ui.theme.AppShapes
import io.github.adkimsm.neteasedownloader.ui.theme.Spacing
import io.github.adkimsm.neteasedownloader.ui.theme.StateWarn
import io.github.adkimsm.neteasedownloader.ui.theme.SurfaceLevel3
import io.github.adkimsm.neteasedownloader.ui.theme.TextPrimary
import io.github.adkimsm.neteasedownloader.ui.theme.TextSecondary

/**
 * 歌单列表页(首页)。
 *
 * 加载态分三级,避免"静止画面":
 *  - 首次/刷新拉取中 → 骨架屏(原实现只有一行居中文字)
 *  - 某行写库中      → 该行内联 loading
 *  - 引擎失败        → 顶部错误条 + 重试(原实现里失败原因完全无处显示)
 *
 * 播放器定位的首页:页头只留「同步 + 设置」两个图标;「新建歌单」移到列表尾部,
 * 底部不再有通栏同步按钮 —— 垂直空间全部让给列表与 mini 播放条。
 * 同步入口(右上角图标 / 设置页)与旧底部按钮行为一致:开始 → 预览 → 执行(模态)。
 */
@Composable
fun PlaylistScreen(
    playlists: List<PlaylistEntity>,
    syncing: Boolean,
    onToggle: (PlaylistEntity, Boolean) -> Unit,
    onSyncClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    likedEntry: LikedEntry?,
    onOpenLiked: () -> Unit,
    onCreatePlaylist: () -> Unit,
    /** 离线删除排队中的曲目数:> 0 时列表首部多一行入口 */
    pendingRemovalCount: Int = 0,
    pendingFlushInFlight: Boolean = false,
    onFlushPending: () -> Unit = {},
    loading: Boolean = false,
    pendingToggleIds: Set<Long> = emptySet(),
    errorMessage: String? = null,
    onDismissError: () -> Unit = {},
) {
    val sizing = LocalWindowSizing.current

    ScreenScaffold(
        title = stringResource(R.string.playlist_title),
        action = {
            IconButton(
                onClick = onSyncClick,
                modifier = Modifier.size(sizing.touchTarget),
            ) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = stringResource(R.string.playlist_sync_now),
                    tint = TextPrimary,
                    modifier = Modifier.size(sizing.iconSize),
                )
            }
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier.size(sizing.touchTarget),
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.playlist_settings),
                    tint = TextPrimary,
                    modifier = Modifier.size(sizing.iconSize),
                )
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (errorMessage != null) {
                ErrorBanner(
                    message = errorMessage,
                    onRetry = onSyncClick,
                    onDismiss = onDismissError,
                )
                Spacer(Modifier.height(sizing.gapSm))
            }

            when {
                // 加载骨架屏优先于空态:否则首次进入会先闪一下"还没有歌单"
                loading && playlists.isEmpty() -> {
                    PlaylistSkeletonList(modifier = Modifier.weight(1f))
                }

                playlists.isEmpty() -> {
                    EmptyState(
                        syncing = syncing,
                        modifier = Modifier.weight(1f),
                    )
                }

                else -> {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        if (likedEntry != null) {
                            item(key = "liked") { LikedEntryRow(likedEntry, onOpen = onOpenLiked) }
                        }
                        // 离线删过的歌:给一个可见的交代,也给一个"现在就来"的按钮
                        if (pendingRemovalCount > 0) {
                            item(key = "pending-removal") {
                                PendingRemovalRow(
                                    count = pendingRemovalCount,
                                    flushing = pendingFlushInFlight,
                                    onFlush = onFlushPending,
                                )
                            }
                        }
                        items(playlists, key = { it.id }) { playlist ->
                            PlaylistRow(
                                playlist = playlist,
                                checked = playlist.enabled,
                                onCheckedChange = { onToggle(playlist, it) },
                                onOpen = { onOpenPlaylist(playlist.id) },
                                toggleLoading = playlist.id in pendingToggleIds,
                            )
                        }
                        // 新建入口放列表尾部,不占页头(播放器惯例;页头留给同步与设置)
                        item(key = "create") { CreatePlaylistRow(onCreate = onCreatePlaylist) }
                    }
                }
            }
        }
    }
}

/**
 * 空态。
 * 同步入口在右上角图标(与设置页),空态文案指向它。
 */
@Composable
private fun EmptyState(syncing: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        if (syncing) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.playlist_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.playlist_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = stringResource(R.string.playlist_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 歌单列表首行的「我喜欢的音乐」入口 */
data class LikedEntry(val count: Int)

@Composable
private fun LikedEntryRow(entry: LikedEntry, onOpen: () -> Unit) {
    val sizing = LocalWindowSizing.current
    ListRow(
        minHeight = sizing.touchTarget,
        onClick = onOpen,
        contentPadding = PaddingValues(start = sizing.gapSm, top = Spacing.xs, bottom = Spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(sizing.iconSize * 1.8f)
                .clip(AppShapes.Circle)
                .background(BrandRed),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = null,
                tint = TextPrimary,
                modifier = Modifier.size(sizing.iconSize),
            )
        }
        Spacer(Modifier.width(sizing.gapSm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.playlist_liked_title),
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.playlist_track_count, entry.count),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 列表尾部的「新建歌单」入口(播放器惯例:新建与浏览同列表,不占页头) */
@Composable
private fun CreatePlaylistRow(onCreate: () -> Unit) {
    val sizing = LocalWindowSizing.current
    // 新建入口固定排在列表最末,故不画自身分隔线
    ListRow(
        minHeight = sizing.touchTarget,
        onClick = onCreate,
        showDivider = false,
        contentPadding = PaddingValues(start = sizing.gapSm, top = Spacing.xs, bottom = Spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(sizing.iconSize * 1.8f)
                .clip(AppShapes.Circle)
                .background(SurfaceLevel3),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(sizing.iconSize),
            )
        }
        Spacer(Modifier.width(sizing.gapSm))
        Text(
            text = stringResource(R.string.playlist_create),
            style = MaterialTheme.typography.titleMedium,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 待删除队列入口。
 *
 * 只在队列非空时出现。联网后它会自己执行,这一行是给「离线时删了歌」的用户一个可见的
 * 交代 —— 那个删除动作确实记下了,而且现在就能立刻执行。
 */
@Composable
private fun PendingRemovalRow(count: Int, flushing: Boolean, onFlush: () -> Unit) {
    val sizing = LocalWindowSizing.current
    ListRow(
        minHeight = sizing.touchTarget,
        enabled = !flushing,
        onClick = onFlush,
        contentPadding = PaddingValues(start = sizing.gapSm, top = Spacing.xs, bottom = Spacing.xs),
    ) {
        Icon(
            imageVector = Icons.Filled.DeleteOutline,
            contentDescription = null,
            tint = StateWarn,
            modifier = Modifier.size(sizing.iconSize),
        )
        Spacer(Modifier.width(sizing.gapSm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.pending_removal_row, count),
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.pending_removal_row_hint),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!flushing) {
            Spacer(Modifier.width(sizing.gapSm))
            Text(
                text = stringResource(R.string.pending_removal_flush),
                style = MaterialTheme.typography.bodySmall,
                color = BrandRed,
                maxLines = 1,
            )
        }
    }
}
