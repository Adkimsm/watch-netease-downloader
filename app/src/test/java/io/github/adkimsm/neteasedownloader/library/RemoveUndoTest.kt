package io.github.adkimsm.neteasedownloader.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 撤销计划。
 *
 * 撤销必须**只恢复确实改动过的部分**:请求失败的那些歌单本来就没动,再去 add 一遍
 * 反而可能把一首用户刚删掉的歌加回两个地方。红心同理 —— 取消失败时不该再"恢复"一次。
 */
class RemoveUndoTest {

    private fun outcome(remoteTargets: List<Long> = emptyList(), unlike: Boolean = false) =
        RemoveOutcome(
            remoteTargets = remoteTargets,
            unlike = unlike,
            deleteLocal = true,
            survivingEnabled = emptyList(),
        )

    private fun report(
        remoteOk: List<Long> = emptyList(),
        unlikeOk: Boolean? = null,
        stale: List<Long> = emptyList(),
    ) = RemoveReport(
        songId = 1L,
        fileDeleted = true,
        remoteOk = remoteOk,
        remoteFailed = emptyList(),
        unlikeOk = unlikeOk,
        remoteStale = stale,
    )

    @Test
    fun undoPlan_restoresEverySuccessfullyRemovedPlaylist() {
        val plan = undoPlanFor(outcome(remoteTargets = listOf(1L, 2L)), report(remoteOk = listOf(1L, 2L)))
        assertEquals(listOf(1L, 2L), plan.playlistIds)
        assertFalse(plan.isEmpty)
    }

    @Test
    fun undoPlan_doesNotTouchPlaylistsThatNeverChanged() {
        // 歌单 3 的移除请求失败了:它本来就没被改动,不该再 add 一遍
        val plan = undoPlanFor(
            outcome(remoteTargets = listOf(1L, 2L, 3L)),
            report(remoteOk = listOf(1L, 2L)),
        )
        assertEquals(listOf(1L, 2L), plan.playlistIds)
    }

    @Test
    fun undoPlan_ignoresStaleWrites() {
        // 写后读回发现服务端还留着:它本来就在,不需要恢复
        val plan = undoPlanFor(
            outcome(remoteTargets = listOf(1L, 2L)),
            report(remoteOk = listOf(1L), stale = listOf(2L)),
        )
        assertEquals(listOf(1L), plan.playlistIds)
    }

    @Test
    fun undoPlan_restoresHeartOnlyWhenItWasActuallyRemoved() {
        val plan = undoPlanFor(outcome(unlike = true), report(unlikeOk = true))
        assertTrue(plan.relike)
        assertFalse(plan.isEmpty)
    }

    @Test
    fun undoPlan_doesNotRelikeWhenUnlikeHadFailed() {
        // 取消红心失败 → 红心还在服务端,不该再 like 一次
        val plan = undoPlanFor(outcome(unlike = true), report(unlikeOk = false))
        assertFalse(plan.relike)
        assertTrue("什么都没改成时撤销应当是空操作", plan.isEmpty)
    }

    @Test
    fun undoPlan_doesNotRelikeWhenSongWasNeverLiked() {
        val plan = undoPlanFor(outcome(unlike = false), report(unlikeOk = null))
        assertFalse(plan.relike)
    }

    @Test
    fun undoPlan_isEmptyWhenNothingSucceeded() {
        val plan = undoPlanFor(outcome(remoteTargets = listOf(1L), unlike = true), report())
        assertTrue(plan.isEmpty)
    }

    @Test
    fun undoPlan_keepsPlaylistOrderForStableLogs() {
        val plan = undoPlanFor(
            outcome(remoteTargets = listOf(30L, 10L, 20L)),
            report(remoteOk = listOf(30L, 10L, 20L)),
        )
        assertEquals(listOf(30L, 10L, 20L), plan.playlistIds)
    }

    @Test
    fun report_hasFailureCoversEveryFailureKind() {
        assertFalse(report(remoteOk = listOf(1L)).hasFailure)
        assertTrue(report(remoteOk = listOf(1L), stale = listOf(2L)).hasFailure)
        assertTrue(report(unlikeOk = false).hasFailure)
        assertTrue(
            RemoveReport(
                songId = 1L,
                fileDeleted = true,
                remoteFailed = listOf(RemoteFailure(1L, "HTTP 500")),
            ).hasFailure,
        )
    }

    @Test
    fun report_queuedIsNotAFailure() {
        // 离线排队:远端一个字都没发出去。当成失败的话,结果条会给「重试」而不是「撤销」,
        // 而这时用户唯一想做的其实是「我点错了,取消掉」。
        val queued = RemoveReport(songId = 1L, fileDeleted = true, queued = true)
        assertFalse(queued.hasFailure)
    }
}
