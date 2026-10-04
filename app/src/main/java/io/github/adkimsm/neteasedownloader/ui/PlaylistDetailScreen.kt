package io.github.adkimsm.neteasedownloader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
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
    /** 播放:带上当前**筛选后**的曲目与起点 —— 搜索状态下组队的就是看得见的这些歌 */
    onPlayTrack: (List<SongEntity>, Int) -> Unit,
    onTrackActions: (Long) -> Unit,
    action: (@Composable () -> Unit)? = null,
) {
    val sizing = LocalWindowSizing.current
    // 歌单内搜索:纯本地筛选已加载的曲目,不打任何网络请求。
    // 搜索框默认收起,只在页头点图标后展开 —— 手表竖屏空间金贵,常驻输入框会
    // 一直吃掉一行多的列表高度。
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val visibleTracks = filterTracks(tracks, query)

    ScreenScaffold(
        title = title,
        onBack = onBack,
        action = {
            // 搜索入口与调用方自带的动作并存:有曲目才给入口,空歌单搜不出东西
            if (tracks.isNotEmpty()) {
                IconButton(
                    // 关闭时一并清空关键词:否则输入框收起了、筛选却还在生效,
                    // 列表看起来就是"歌单莫名少了一半",而用户看不见原因
                    onClick = {
                        searchOpen = !searchOpen
                        if (!searchOpen) query = ""
                    },
                    modifier = Modifier.size(sizing.touchTarget),
                ) {
                    Icon(
                        imageVector = if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                        contentDescription = stringResource(
                            if (searchOpen) R.string.detail_search_close else R.string.detail_search_open,
                        ),
                        tint = TextPrimary,
                        modifier = Modifier.size(sizing.iconSize),
                    )
                }
            }
            action?.invoke()
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = searchOpen,
                enter = expandVertically(tween(Motion.ContentDurationMs)) +
                    fadeIn(tween(Motion.ContentDurationMs)),
                exit = shrinkVertically(tween(Motion.ContentDurationMs)) +
                    fadeOut(tween(Motion.ContentDurationMs)),
            ) {
                Column {
                    TrackSearchField(
                        query = query,
                        onQueryChange = { query = it },
                    )
                    Spacer(Modifier.height(sizing.gapSm))
                }
            }

            if (error != null) {
                ErrorBanner(message = error, onRetry = onRetry, onDismiss = onDismissError)
                Spacer(Modifier.height(sizing.gapSm))
            }

            // 骨架屏 -> 真实列表/空态:三种形态之间淡入淡出,不再硬切。
            // 骨架屏本身仍由 rememberDelayedVisibility 延迟出现(SkeletonDelayMs),
            // 这里只负责「已经显示之后」的切换,不影响快操作不闪屏的约定。
            Crossfade(
                targetState = listContentState(loading, visibleTracks.isEmpty()),
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
                            // 歌单本来就是空的 / 筛选没命中,是两件事,不该共用一句话
                            text = stringResource(
                                if (tracks.isEmpty()) R.string.detail_empty else R.string.detail_search_empty,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }

                    ListContentState.Content -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(visibleTracks, key = { _, song -> song.songId }) { index, song ->
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
                                showDivider = index < visibleTracks.lastIndex,
                                onClick = { onPlayTrack(visibleTracks, index) },
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
 * 歌单内搜索的匹配规则。
 *
 * 纯本地筛选:曲目列表已经整份加载在内存里,再打一次网络既慢又费电,断网还会搜不了。
 * 关键词按空白切词,**每个词都要命中**歌名或歌手(忽略大小写)—— 这样"周杰伦 晴天"
 * 这类"歌手 + 歌名"的输入能正确收敛,而不是把任意一个词命中的几百首都倒出来。
 * 同样只按歌名/歌手匹配,不引入专辑名(与列表行展示的字段一致)。
 */
internal fun filterTracks(tracks: List<SongEntity>, query: String): List<SongEntity> {
    val terms = query.trim().split(" ", "\u3000").filter { it.isNotEmpty() }
    if (terms.isEmpty()) return tracks
    return tracks.filter { song ->
        val haystack = "${song.name} ${song.artist}".lowercase()
        terms.all { haystack.contains(it.lowercase()) }
    }
}

/**
 * 歌单内搜索框。
 *
 * 展开即自动聚焦并弹键盘:既然用户特意点了搜索图标,再让他点一次输入框是多余的一步。
 * 带清除键;按 Done 收起键盘,筛选结果此时已经生效(本地过滤是同步的)。
 */
@Composable
private fun TrackSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val sizing = LocalWindowSizing.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // 组件只在展开时进入组合,所以这一次请求聚焦就等价于"展开时自动聚焦"
        runCatching { focusRequester.requestFocus() }
    }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.detail_search_hint)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(sizing.iconSize),
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(sizing.touchTarget)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.detail_search_clear),
                        tint = TextSecondary,
                        modifier = Modifier.size(sizing.iconSize),
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
    )
}

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
