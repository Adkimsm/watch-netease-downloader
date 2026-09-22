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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面路由的时序与返回栈测试。
 *
 * 起因是一个真实缺陷:引擎「先发 READY,再由 refreshAndDiff 发布差量」,
 * 而旧实现只按其余几个流重算路由、在 transform 里临时读一次差量快照,于是 READY
 * 那一刻读到的差量还是 null,用户看到的就是「同步完成后不弹下载预览,得先点一下
 * 设置再返回才出现」。这些用例把差量钉成 combine 的输入之一。
 *
 * 加入播放后同样的坑还会再出现一次:mini 播放条的显隐依赖播放态,若把它做成
 * "别的 UI 事件顺带读一次快照",媒体通知里切歌就不会反映到界面上 ——
 * [miniPlayerAppearsWhenPlaybackStarts] 钉的就是这一点。
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
        stack: MutableStateFlow<List<Dest>> = MutableStateFlow(emptyList()),
        playerActive: MutableStateFlow<Boolean> = MutableStateFlow(false),
        musicU: String = "music-u",
    ): List<MainViewModel.UiState> {
        val states = mutableListOf<MainViewModel.UiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            routeUiState(
                loggedIn = flowOf(musicU),
                progress = progress,
                lastDiff = diff,
                stack = stack,
                playerActive = playerActive,
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
        assertEquals(Dest.Syncing, states.last().dest)

        // READY 先到,差量这一瞬间还没发布 —— 旧实现就是在这里卡住的
        progress.value = SyncEngine.Progress(SyncEngine.Stage.READY, "比对完成")
        advanceUntilIdle()
        assertEquals(Dest.Playlists, states.last().dest)
        val sizeAfterReady = states.size

        // 差量后到:必须再触发一次路由,页面自己对齐到预览页(用户不必去点设置)
        diff.value = diffOfOneSong()
        advanceUntilIdle()
        assertTrue("差量到达后必须再触发一次路由", states.size > sizeAfterReady)
        assertEquals(Dest.Preview, states.last().dest)
        assertNotNull(states.last().diff)
    }

    @Test
    fun readyWithEmptyDiff_staysOnPlaylists() = runTest {
        val progress = MutableStateFlow(SyncEngine.Progress(SyncEngine.Stage.READY, "比对完成"))
        val diff = MutableStateFlow<SyncEngine.Diff?>(emptyDiff())
        val states = routesOf(progress, diff)
        advanceUntilIdle()
        // 没有待下载/待删除:不该进预览页让用户面对一个空页面
        assertEquals(Dest.Playlists, states.last().dest)
    }

    @Test
    fun newRoundClearingDiff_leavesPreview() = runTest {
        val progress = MutableStateFlow(SyncEngine.Progress(SyncEngine.Stage.READY, "比对完成"))
        val diff = MutableStateFlow<SyncEngine.Diff?>(diffOfOneSong())
        val states = routesOf(progress, diff)
        advanceUntilIdle()
        assertEquals(Dest.Preview, states.last().dest)

        // 开始新一轮同步:清差量 + 回到 IDLE,应立即退出预览页
        diff.value = null
        progress.value = SyncEngine.Progress(SyncEngine.Stage.IDLE, "")
        advanceUntilIdle()
        assertEquals(Dest.Playlists, states.last().dest)
    }

    @Test
    fun syncingIsModal_andReturnsToWhereTheUserWas() = runTest {
        val progress = MutableStateFlow(SyncEngine.Progress(SyncEngine.Stage.DOWNLOADING, "下载中"))
        val diff = MutableStateFlow<SyncEngine.Diff?>(diffOfOneSong())
        val stack = MutableStateFlow<List<Dest>>(listOf(Dest.NowPlaying))
        val states = routesOf(progress, diff, stack)
        advanceUntilIdle()
        assertEquals(Dest.Syncing, states.last().dest)

        // 同步结束回到同步前所在的页面 —— 不是被丢回根页面
        progress.value = SyncEngine.Progress(SyncEngine.Stage.IDLE, "")
        diff.value = emptyDiff()
        advanceUntilIdle()
        assertEquals(Dest.NowPlaying, states.last().dest)
    }

    @Test
    fun pushAndPop_walksTheBackStack() = runTest {
        val progress = MutableStateFlow(SyncEngine.Progress(SyncEngine.Stage.IDLE, ""))
        val diff = MutableStateFlow<SyncEngine.Diff?>(null)
        val stack = MutableStateFlow<List<Dest>>(emptyList())
        val states = routesOf(progress, diff, stack)
        advanceUntilIdle()
        assertEquals(Dest.Playlists, states.last().dest)

        stack.value = listOf(Dest.PlaylistDetail(42L))
        advanceUntilIdle()
        assertEquals(Dest.PlaylistDetail(42L), states.last().dest)

        stack.value = listOf(Dest.PlaylistDetail(42L), Dest.SongActions(7L))
        advanceUntilIdle()
        assertEquals(Dest.SongActions(7L), states.last().dest)

        // 二级菜单返回 → 回到来源页(曲目列表),而不是根页面
        stack.value = listOf(Dest.PlaylistDetail(42L))
        advanceUntilIdle()
        assertEquals(Dest.PlaylistDetail(42L), states.last().dest)
    }

    @Test
    fun loggedOut_winsOverEverything() = runTest {
        val progress = MutableStateFlow(SyncEngine.Progress(SyncEngine.Stage.DOWNLOADING, "下载中"))
        val diff = MutableStateFlow<SyncEngine.Diff?>(diffOfOneSong())
        val stack = MutableStateFlow<List<Dest>>(listOf(Dest.Settings))
        val states = routesOf(progress, diff, stack, musicU = "")
        advanceUntilIdle()
        assertEquals(Dest.Login, states.last().dest)
    }

    @Test
    fun miniPlayerAppearsWhenPlaybackStarts() = runTest {
        val progress = MutableStateFlow(SyncEngine.Progress(SyncEngine.Stage.IDLE, ""))
        val diff = MutableStateFlow<SyncEngine.Diff?>(null)
        val playerActive = MutableStateFlow(false)
        val states = routesOf(progress, diff, playerActive = playerActive)
        advanceUntilIdle()
        assertFalse("没有播放内容时不显示 mini 播放条", states.last().showMiniPlayer)

        // 播放态是 combine 的输入:它一变就要重算,不需要别的 UI 事件来"顺带"刷新
        playerActive.value = true
        advanceUntilIdle()
        assertTrue(states.last().showMiniPlayer)
    }

    @Test
    fun miniPlayerHiddenOnNowPlayingScreen() = runTest {
        val progress = MutableStateFlow(SyncEngine.Progress(SyncEngine.Stage.IDLE, ""))
        val diff = MutableStateFlow<SyncEngine.Diff?>(null)
        val playerActive = MutableStateFlow(true)
        val stack = MutableStateFlow<List<Dest>>(listOf(Dest.NowPlaying))
        val states = routesOf(progress, diff, stack, playerActive)
        advanceUntilIdle()
        assertEquals(Dest.NowPlaying, states.last().dest)
        assertFalse("播放页本身占满屏幕,不再叠 mini 播放条", states.last().showMiniPlayer)
    }

    @Test
    fun miniPlayerHiddenAfterLogout() = runTest {
        val progress = MutableStateFlow(SyncEngine.Progress(SyncEngine.Stage.IDLE, ""))
        val diff = MutableStateFlow<SyncEngine.Diff?>(null)
        val states = routesOf(progress, diff, playerActive = MutableStateFlow(true), musicU = "")
        advanceUntilIdle()
        assertEquals(Dest.Login, states.last().dest)
        assertFalse(states.last().showMiniPlayer)
    }

    @Test
    fun settingsAndDiagnosticsAreStackEntries() = runTest {
        val progress = MutableStateFlow(SyncEngine.Progress(SyncEngine.Stage.IDLE, ""))
        val diff = MutableStateFlow<SyncEngine.Diff?>(null)
        val stack = MutableStateFlow<List<Dest>>(listOf(Dest.Settings))
        val states = routesOf(progress, diff, stack)
        advanceUntilIdle()
        assertEquals(Dest.Settings, states.last().dest)

        stack.value = listOf(Dest.Diagnostics)
        advanceUntilIdle()
        assertEquals(Dest.Diagnostics, states.last().dest)
    }
}
