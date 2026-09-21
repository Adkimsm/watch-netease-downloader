package io.github.adkimsm.neteasedownloader.ui

import io.github.adkimsm.neteasedownloader.data.SongEntity
import io.github.adkimsm.neteasedownloader.sync.SyncEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面路由的时序测试。
 *
 * 起因是一个真实缺陷:引擎「先发 READY,再由 refreshAndDiff 发布差量」,
 * 而旧实现只按「登录态/进度/设置/诊断」四个流重算路由、在 transform 里临时读一次差量快照,
 * 于是 READY 那一刻读到的差量还是 null,页面停在歌单页且再也没有事件触发重算 ——
 * 用户看到的现象是「同步完成后不弹下载预览,得先点右上角设置再返回主界面才出现」。
 *
 * 这些用例把差量钉成 combine 的输入之一:只要有人把它从 combine 里拿掉,
 * [lateDiffAfterReady_triggersAnotherRouteAndShowsPreview] 就会挂。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenRoutingTest {

    private fun song(id: Long = 1L) = SongEntity(
        songId = id,
        name = "歌名",
        artist = "歌手",
        album = null,
        duration = 0L,
        md5 = null,
        size = 0L,
        br = 0L,
        type = "mp3",
        state = "PENDING",
        updatedAt = 0L,
    )

    private fun diffOfOneSong() = SyncEngine.Diff(
        toDownload = listOf(song()),
        toDelete = emptyList(),
        missingUrlCount = 0,
        estimatedBytes = 0L,
        availableBytes = 0L,
    )

    private fun emptyDiff() = SyncEngine.Diff(
        toDownload = emptyList(),
        toDelete = emptyList(),
        missingUrlCount = 0,
        estimatedBytes = 0L,
        availableBytes = 0L,
    )

    /** 收集路由结果,返回不断追加的列表(用 backgroundScope,测试结束自动取消) */
    private fun TestScope.routesOf(
        progress: MutableStateFlow<SyncEngine.Progress>,
        diff: MutableStateFlow<SyncEngine.Diff?>,
        settingsOpen: MutableStateFlow<Boolean> = MutableStateFlow(false),
    ): List<MainViewModel.UiState> {
        val states = mutableListOf<MainViewModel.UiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            routeUiState(
                loggedIn = flowOf("music-u"),
                progress = progress,
                settingsOpen = settingsOpen,
                diagnosticsOpen = flowOf(false),
                lastDiff = diff,
            ).collect { states += it }
        }
        return states
    }

    @Test
    fun lateDiffAfterReady_triggersAnotherRouteAndShowsPreview() = runTest {
        val progress = MutableStateFlow(SyncEngine.Progress(SyncEngine.Stage.REFRESHING, "拉取中"))
        val diff = MutableStateFlow<SyncEngine.Diff?>(null)
        val states = routesOf(progress, diff)
        advanceUntilIdle()
        assertEquals(MainViewModel.Screen.SYNCING, states.last().screen)

        // READY 先到,差量这一瞬间还没发布 —— 旧实现就是在这里卡住的
        progress.value = SyncEngine.Progress(SyncEngine.Stage.READY, "比对完成")
        advanceUntilIdle()
        assertEquals(MainViewModel.Screen.PLAYLISTS, states.last().screen)
        val sizeAfterReady = states.size

        // 差量后到:必须再触发一次路由,页面自己对齐到预览页(用户不必去点设置)
        diff.value = diffOfOneSong()
        advanceUntilIdle()
        assertTrue("差量到达后必须再触发一次路由", states.size > sizeAfterReady)
        assertEquals(MainViewModel.Screen.PREVIEW, states.last().screen)
        assertNotNull(states.last().diff)
    }

    @Test
    fun readyWithEmptyDiff_staysOnPlaylists() = runTest {
        val progress = MutableStateFlow(SyncEngine.Progress(SyncEngine.Stage.READY, "比对完成"))
        val diff = MutableStateFlow<SyncEngine.Diff?>(emptyDiff())
        val states = routesOf(progress, diff)
        advanceUntilIdle()
        // 没有待下载/待删除:不该进预览页让用户面对一个空页面
        assertEquals(MainViewModel.Screen.PLAYLISTS, states.last().screen)
    }

    @Test
    fun newRoundClearingDiff_leavesPreview() = runTest {
        val progress = MutableStateFlow(SyncEngine.Progress(SyncEngine.Stage.READY, "比对完成"))
        val diff = MutableStateFlow<SyncEngine.Diff?>(diffOfOneSong())
        val states = routesOf(progress, diff)
        advanceUntilIdle()
        assertEquals(MainViewModel.Screen.PREVIEW, states.last().screen)

        // 开始新一轮同步:清差量 + 回到 IDLE,应立即退出预览页
        diff.value = null
        progress.value = SyncEngine.Progress(SyncEngine.Stage.IDLE, "")
        advanceUntilIdle()
        assertEquals(MainViewModel.Screen.PLAYLISTS, states.last().screen)
    }

    @Test
    fun settingsOverridesPreviewAndSyncing_likeBefore() = runTest {
        val progress = MutableStateFlow(SyncEngine.Progress(SyncEngine.Stage.DOWNLOADING, "下载中"))
        val diff = MutableStateFlow<SyncEngine.Diff?>(diffOfOneSong())
        val settingsOpen = MutableStateFlow(false)
        val states = routesOf(progress, diff, settingsOpen)
        advanceUntilIdle()
        assertEquals(MainViewModel.Screen.SYNCING, states.last().screen)

        settingsOpen.value = true
        advanceUntilIdle()
        assertEquals(MainViewModel.Screen.SETTINGS, states.last().screen)

        settingsOpen.value = false
        advanceUntilIdle()
        assertEquals(MainViewModel.Screen.SYNCING, states.last().screen)
    }
}
