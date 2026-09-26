package io.github.adkimsm.neteasedownloader.ui

import io.github.adkimsm.neteasedownloader.sync.SyncEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * 导航目的地。
 *
 * 取代原来"一个 Screen 枚举 + settingsOpen/diagnosticsOpen 两个布尔量"的写法:
 * 播放页、歌单详情、二级菜单都是可入栈的页面,布尔量表达不了返回栈。
 */
sealed interface Dest {
    data object Login : Dest
    data object Playlists : Dest

    /** 同步预览(模态,不压栈) */
    data object Preview : Dest

    /** 同步进度(模态,不压栈) */
    data object Syncing : Dest

    data object Settings : Dest
    data object Diagnostics : Dest
    data class PlaylistDetail(val playlistId: Long) : Dest
    data object NowPlaying : Dest
    data object Queue : Dest

    /** 单曲二级菜单(播放页与曲目行共用) */
    data class SongActions(val songId: Long) : Dest

    /** 删除面板(仅"每次都询问"模式进入) */
    data class RemoveSong(val songId: Long) : Dest

    /** 我喜欢的音乐 */
    data object LikedSongs : Dest

    /** 歌单级操作菜单(重命名 / 删除歌单) */
    data class PlaylistMenu(val playlistId: Long) : Dest

    /** 新建(playlistId == null)或重命名歌单 */
    data class PlaylistEdit(val playlistId: Long?) : Dest

    /** 把这首歌加入某些歌单 */
    data class AddToPlaylist(val songId: Long) : Dest
}

/**
 * 把「登录态 / 进度 / 差量 / 导航栈 / 播放态」合成一次路由结果。
 *
 * 差量与导航栈、播放态都必须是 combine 的**输入**:
 * 引擎是"先发 READY、再发布差量",两者不在同一个事件里;若只按其余流重算,
 * READY 那一刻读到的差量还是 null,页面会停在歌单页且之后没有任何事件再触发重算 ——
 * 用户看到的就是「同步完成后不弹下载预览,得先点一下设置再返回才出现」。
 * 播放态同理:做成输入流后,媒体通知里切歌也会让 mini 播放条自己更新。
 *
 * 抽成顶层纯函数是为了可单测(见 ScreenRoutingTest)。
 */
internal fun routeUiState(
    loggedIn: Flow<String>,
    progress: Flow<SyncEngine.Progress>,
    lastDiff: Flow<SyncEngine.Diff?>,
    stack: Flow<List<Dest>>,
    playerActive: Flow<Boolean>,
): Flow<MainViewModel.UiState> = combine(
    loggedIn,
    progress,
    lastDiff,
    stack,
    playerActive,
) { musicU, p, diff, nav, hasPlayer ->
    MainViewModel.UiState(
        dest = routeDest(musicU, p, diff, nav),
        diff = diff,
        showMiniPlayer = hasPlayer && musicU.isNotEmpty() && nav.lastOrNull() != Dest.NowPlaying,
    )
}

/**
 * 路由规则。
 *
 * 优先级:**登录 → 同步(模态) → 预览(模态) → 栈顶 → 歌单列表**。
 *
 * 同步/预览刻意做成模态(不压栈):它们是用户主动发起、结束即离开的流程,
 * 结束后回到发起前所在的页面。音频播放不受影响。
 */
internal fun routeDest(
    musicU: String,
    p: SyncEngine.Progress,
    diff: SyncEngine.Diff?,
    stack: List<Dest>,
): Dest = when {
    musicU.isEmpty() -> Dest.Login

    p.stage in MainViewModel.ACTIVE_STAGES -> Dest.Syncing

    // 预览页要求「READY + 差量非空」:没有变更就不该让用户面对一个空页面
    p.stage == SyncEngine.Stage.READY &&
        (diff?.toDownload?.isNotEmpty() == true || diff?.toDelete?.isNotEmpty() == true) -> Dest.Preview

    else -> stack.lastOrNull() ?: Dest.Playlists
}

/**
 * 页面的视觉层级。
 *
 * 只区分「根 / 入栈」两层,而不是精确的栈深度:过渡动画要的是**方向**(推进还是退回),
 * 而 `Dest` 本身算不出方向 —— `PlaylistDetail(1) -> PlaylistDetail(2)` 这类同类型不同
 * 参数的跳转,靠比较 dest 是否相等会误判成「没变」。用层级 + 入栈顺序判断才稳。
 */
internal enum class DestLayer {
    /** 根页面(歌单列表 / 登录)与模态(同步预览 / 进度):不参与横向推进 */
    Root,

    /** 压在栈上的页面:详情、播放、二级菜单、设置、诊断等 */
    Stacked,
}

/**
 * 页面的视觉层级。抽成纯函数是为了可单测(见 NavTransitionTest)。
 *
 * 模态页([Dest.Preview] / [Dest.Syncing])刻意归到 [DestLayer.Root]:它们由同步流程
 * 触发而非用户压栈,结束后回到发起前页面,横滑会让人误以为「进了新页面且回不去了」。
 */
internal fun layerOf(dest: Dest): DestLayer = when (dest) {
    Dest.Login, Dest.Playlists, Dest.Preview, Dest.Syncing -> DestLayer.Root
    else -> DestLayer.Stacked
}

/**
 * 过渡形态。决定 [androidx.compose.animation.AnimatedContent] 用哪套 spec,
 * 抽出来是为了让「哪类页面用什么动画」这条规则可以被测试钉住。
 */
internal enum class NavTransition {
    /** 无方向感的淡入淡出:根页面之间、以及模态的出现与消失 */
    Fade,

    /** 带方向感的横向推进:入栈页面进出 */
    Slide,
}

/**
 * 根据「上一个页面」与「当前页面」决定过渡形态。
 *
 * 规则:两端都不在栈上(根层面互切/模态显隐)就纯淡入淡出;只要涉及入栈页面,
 * 就走横向滑动 —— 这样 `PlaylistDetail -> SongActions -> PlaylistEdit` 的连续入栈
 * 都表现为「向右推进」,返回时整条链反向退回。
 */
internal fun navTransitionFor(from: Dest?, to: Dest): NavTransition = when {
    from == null -> NavTransition.Fade
    layerOf(from) == DestLayer.Root && layerOf(to) == DestLayer.Root -> NavTransition.Fade
    else -> NavTransition.Slide
}

/**
 * 滑动方向:`true` 表示新页面从右侧进入(推进),`false` 表示从左侧进入(退回)。
 *
 * 判据是「新页面是否在栈上」:上一个是根层面、现在进了栈页面,就是推进;
 * 反过来从栈页面退回根层面就是退回。同为栈页面时(如详情 → 二级菜单)仍是推进。
 */
internal fun slidesForward(from: Dest?, to: Dest): Boolean = when {
    from == null -> true
    layerOf(from) == DestLayer.Root && layerOf(to) == DestLayer.Stacked -> true
    layerOf(from) == DestLayer.Stacked && layerOf(to) == DestLayer.Root -> false
    else -> true
}
