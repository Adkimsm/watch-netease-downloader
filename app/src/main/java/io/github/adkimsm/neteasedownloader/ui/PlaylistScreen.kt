package io.github.adkimsm.neteasedownloader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.adkimsm.neteasedownloader.R
import io.github.adkimsm.neteasedownloader.data.PlaylistEntity
import io.github.adkimsm.neteasedownloader.ui.components.ErrorBanner
import io.github.adkimsm.neteasedownloader.ui.components.HDivider
import io.github.adkimsm.neteasedownloader.ui.components.PlaylistRow
import io.github.adkimsm.neteasedownloader.ui.components.PlaylistSkeletonList
import io.github.adkimsm.neteasedownloader.ui.components.PrimaryButton
import io.github.adkimsm.neteasedownloader.ui.components.ScreenScaffold
import io.github.adkimsm.neteasedownloader.ui.theme.Dimens
import io.github.adkimsm.neteasedownloader.ui.theme.Spacing
import io.github.adkimsm.neteasedownloader.ui.theme.TextDisabled
import io.github.adkimsm.neteasedownloader.ui.theme.TextPrimary
import io.github.adkimsm.neteasedownloader.ui.theme.TextSecondary

/**
 * 歌单列表页。
 *
 * 加载态分三级,避免"静止画面":
 *  - 首次/刷新拉取中 → 骨架屏(原实现只有一行居中文字)
 *  - 某行写库中      → 该行内联 loading
 *  - 引擎失败        → 顶部错误条 + 重试(原实现里失败原因完全无处显示)
 */
@Composable
fun PlaylistScreen(
    playlists: List<PlaylistEntity>,
    syncing: Boolean,
    onToggle: (PlaylistEntity, Boolean) -> Unit,
    onSyncClick: () -> Unit,
    onSettingsClick: () -> Unit,
    loading: Boolean = false,
    pendingToggleIds: Set<Long> = emptySet(),
    errorMessage: String? = null,
    onDismissError: () -> Unit = {},
) {
    ScreenScaffold(
        title = stringResource(R.string.playlist_title),
        action = {
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier.size(Dimens.TouchTarget),
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.playlist_settings),
                    tint = TextPrimary,
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
                Spacer(Modifier.height(Spacing.sm))
            }

            val selectedCount = playlists.count { it.enabled }

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
                        items(playlists, key = { it.id }) { playlist ->
                            PlaylistRow(
                                playlist = playlist,
                                checked = playlist.enabled,
                                onCheckedChange = { onToggle(playlist, it) },
                                toggleLoading = playlist.id in pendingToggleIds,
                            )
                        }
                    }
                }
            }

            // 底部操作区固定:歌单再多也不会把「立即同步」挤出屏幕
            Spacer(Modifier.height(Spacing.sm))
            HDivider()
            Spacer(Modifier.height(Spacing.sm))

            Text(
                text = if (selectedCount == 0) {
                    stringResource(R.string.playlist_selected_none)
                } else {
                    stringResource(R.string.playlist_selected_count, selectedCount)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (selectedCount == 0) TextDisabled else TextSecondary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.xs))

            PrimaryButton(
                text = if (syncing) {
                    stringResource(R.string.playlist_syncing)
                } else {
                    stringResource(R.string.playlist_sync_now)
                },
                onClick = onSyncClick,
                enabled = !syncing,
                loading = syncing,
            )
        }
    }
}

/**
 * 空态。
 * 修正原文案"点右上角同步"—— 右上角是设置,真正的同步按钮在底部。
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
