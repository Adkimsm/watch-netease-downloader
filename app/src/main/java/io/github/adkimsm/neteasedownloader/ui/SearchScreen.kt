package io.github.adkimsm.neteasedownloader.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import io.github.adkimsm.neteasedownloader.R
import io.github.adkimsm.neteasedownloader.data.SongEntity
import io.github.adkimsm.neteasedownloader.ui.components.ErrorBanner
import io.github.adkimsm.neteasedownloader.ui.components.ListRow
import io.github.adkimsm.neteasedownloader.ui.components.ScreenScaffold
import io.github.adkimsm.neteasedownloader.ui.components.TrackSkeletonList
import io.github.adkimsm.neteasedownloader.ui.theme.LocalWindowSizing
import io.github.adkimsm.neteasedownloader.ui.theme.Motion
import io.github.adkimsm.neteasedownloader.ui.theme.Spacing
import io.github.adkimsm.neteasedownloader.ui.theme.TextPrimary
import io.github.adkimsm.neteasedownloader.ui.theme.TextSecondary

/**
 * 歌曲搜索页。
 *
 * 输入框 + 无封面曲目行(D13,与歌单详情同一语言)。三态互斥:
 * 加载(骨架)→ 空结果文案 / 内容,与首页列表共用 listContentState。
 * 搜索结果不区分"已下载/在线"徽标:刚入库的歌 state 一律 PENDING,
 * 播放时由播放层自己决定本地还是串流。
 */
@Composable
fun SearchScreen(
    query: String,
    results: List<SongEntity>,
    loading: Boolean,
    error: String?,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onPlayTrack: (Int) -> Unit,
    onTrackActions: (Long) -> Unit,
) {
    val sizing = LocalWindowSizing.current

    ScreenScaffold(title = stringResource(R.string.search_title), onBack = onBack) {
        Column(modifier = Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.search_hint)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = sizing.touchTarget),
            )
            Spacer(Modifier.height(sizing.gapSm))

            if (error != null) {
                ErrorBanner(message = error, onRetry = onRetry, onDismiss = onDismissError)
                Spacer(Modifier.height(sizing.gapSm))
            }

            Crossfade(
                targetState = listContentState(loading, results.isEmpty()),
                modifier = Modifier.weight(1f),
                animationSpec = tween(Motion.ContentDurationMs),
                label = "searchContent",
            ) { contentState ->
                when (contentState) {
                    ListContentState.Loading ->
                        TrackSkeletonList(modifier = Modifier.fillMaxSize())

                    ListContentState.Empty -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.search_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }

                    ListContentState.Content -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(results, key = { _, song -> song.songId }) { index, song ->
                            SearchResultRow(
                                modifier = Modifier.animateItem(),
                                song = song,
                                showDivider = index < results.lastIndex,
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

/**
 * 搜索结果行:歌名 + 歌手·时长,无封面(D13)。与歌单详情的 TrackRow 同一结构,
 * 但不显示状态徽标(见上)。
 */
@Composable
private fun SearchResultRow(
    modifier: Modifier = Modifier,
    song: SongEntity,
    showDivider: Boolean,
    onClick: () -> Unit,
    onActions: () -> Unit,
) {
    val sizing = LocalWindowSizing.current
    ListRow(
        modifier = modifier,
        minHeight = sizing.menuRowHeight,
        onClick = onClick,
        showDivider = showDivider,
        contentPadding = PaddingValues(start = sizing.gapSm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.name,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
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
