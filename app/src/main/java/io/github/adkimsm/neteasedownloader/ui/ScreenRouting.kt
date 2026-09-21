package io.github.adkimsm.neteasedownloader.ui

import io.github.adkimsm.neteasedownloader.sync.SyncEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * 把「登录态 / 进度 / 设置开 / 诊断开 / 差量」合成一次路由结果。
 *
 * 差量([lastDiff])必须是 combine 的输入之一,不能在 transform 里临时读一次快照:
 * 引擎是「先发 READY(见 SyncEngine.computeDiff),再由 refreshAndDiff 发布差量」,
 * 两者不在同一个事件里。若只按前四个流重算,READY 那一刻读到的差量很可能还是 null,
 * 页面就停在歌单页,且之后没有任何事件再触发重算 —— 用户看到的就是
 * 「同步完成后不弹下载预览,得先点一下右上角设置(改动别的 UI 状态)再返回才出现」。
 * 把差量作为输入后,它晚到也会再触发一次路由,页面自己就对齐到预览页。
 *
 * 抽成顶层纯函数是为了可单测:路由不依赖 Android,单测直接用几个 MutableStateFlow
 * 复现「READY 先到、差量后到」的时序(见 ScreenRoutingTest)。
 */
internal fun routeUiState(
    loggedIn: Flow<String>,
    progress: Flow<SyncEngine.Progress>,
    settingsOpen: Flow<Boolean>,
    diagnosticsOpen: Flow<Boolean>,
    lastDiff: Flow<SyncEngine.Diff?>,
): Flow<MainViewModel.UiState> = combine(
    loggedIn,
    progress,
    settingsOpen,
    diagnosticsOpen,
    lastDiff,
) { musicU, p, settings, diag, diff ->
    MainViewModel.UiState(routeScreen(musicU, p, settings, diag, diff), diff)
}

/**
 * 路由规则。预览页要求「READY + 差量非空」;页面与差量在同一个 [MainViewModel.UiState]
 * 里产出,预览页因此不会在差量还没读到时就先渲染出来(白屏)。
 */
internal fun routeScreen(
    musicU: String,
    p: SyncEngine.Progress,
    settingsOpen: Boolean,
    diagnosticsOpen: Boolean,
    diff: SyncEngine.Diff?,
): MainViewModel.Screen = when {
    musicU.isEmpty() -> MainViewModel.Screen.LOGIN
    diagnosticsOpen -> MainViewModel.Screen.DIAGNOSTICS
    settingsOpen -> MainViewModel.Screen.SETTINGS
    // 拉取+差量阶段也进进度屏:大歌单(3742 首)可能持续数分钟,
    // 原先留在歌单页只有底部一个小转圈,提示粒度过粗。
    p.stage in MainViewModel.ACTIVE_STAGES -> MainViewModel.Screen.SYNCING

    p.stage == SyncEngine.Stage.READY &&
        (diff?.toDownload?.isNotEmpty() == true ||
            diff?.toDelete?.isNotEmpty() == true) -> MainViewModel.Screen.PREVIEW

    else -> MainViewModel.Screen.PLAYLISTS
}
