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
