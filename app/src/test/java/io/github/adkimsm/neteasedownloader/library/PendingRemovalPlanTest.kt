package io.github.adkimsm.neteasedownloader.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离线删除的排队决策。
 *
 * 三个用例是回归闸门:
 *  - [shouldQueue_offlineWithoutRemoteWork_isFalse]:既不在本人歌单、也没红心的歌,
 *    删除本来就不需要网络。一旦有人把"离线一律排队"写死,这条会挂;
 *  - [remainingAfterFlush_keepsStalePlaylists]:请求成功但写后读回发现服务端还留着,
 *    必须留在队列里重试 —— 丢掉就是骗用户"删好了";
 *  - [mergePending_mergesInsteadOfAppending]:同一首歌连点两次不能产生两条队列项。
 */
class PendingRemovalPlanTest {

    private fun outcome(
        remoteTargets: List<Long> = emptyList(),
        unlike: Boolean = false,
        deleteLocal: Boolean = true,
    ) = RemoveOutcome(
        remoteTargets = remoteTargets,
        unlike = unlike,
        deleteLocal = deleteLocal,
        survivingEnabled = emptyList(),
    )

    private fun report(
        remoteOk: List<Long> = emptyList(),
        remoteFailed: List<Long> = emptyList(),
        unlikeOk: Boolean? = null,
        stale: List<Long> = emptyList(),
    ) = RemoveReport(
        songId = 1L,
        fileDeleted = false,
        remoteOk = remoteOk,
        remoteFailed = remoteFailed.map { RemoteFailure(it, "boom") },
        unlikeOk = unlikeOk,
        remoteStale = stale,
    )

    // ---------- 要不要排队 ----------

    @Test
    fun shouldQueue_offlineWithPlaylistTargets_isTrue() {
        assertTrue(shouldQueue(outcome(remoteTargets = listOf(1L, 2L)), online = false))
    }

    @Test
    fun shouldQueue_offlineWithOnlyUnlike_isTrue() {
        assertTrue(shouldQueue(outcome(unlike = true), online = false))
    }

    @Test
    fun shouldQueue_offlineWithoutRemoteWork_isFalse() {
        // 只删本地、且这首歌不在任何本人歌单里也没红心:没网也当场删,不该让用户白等一次联网
        assertFalse(shouldQueue(outcome(), online = false))
    }

    @Test
    fun shouldQueue_online_isAlwaysFalse() {
        assertFalse(shouldQueue(outcome(remoteTargets = listOf(1L), unlike = true), online = true))
    }

    // ---------- 合并 ----------

    @Test
    fun mergePending_firstTimeIsTakenAsIs() {
        val incoming = PendingRemoval(7L, listOf(3L), unlike = true, createdAt = 100L)
        assertEquals(incoming, mergePending(null, incoming))
    }

    @Test
    fun mergePending_mergesInsteadOfAppending() {
        val existing = PendingRemoval(7L, listOf(3L, 1L), unlike = false, createdAt = 100L)
        val incoming = PendingRemoval(7L, listOf(2L, 3L), unlike = true, createdAt = 200L)
        val merged = mergePending(existing, incoming)

        // 并集去重 + 排序,红心取或,入队时间保持最早那次(队列顺序稳定)
        assertEquals(listOf(1L, 2L, 3L), merged.playlistIds)
        assertTrue(merged.unlike)
        assertEquals(100L, merged.createdAt)
        assertEquals(7L, merged.songId)
    }

    @Test
    fun mergePending_keepsEarlierUnlikeWhenIncomingDoesNotNeedIt() {
        val merged = mergePending(
            PendingRemoval(7L, emptyList(), unlike = true, createdAt = 100L),
            PendingRemoval(7L, listOf(5L), unlike = false, createdAt = 200L),
        )
        assertTrue("已经排队的取消红心不能被后来的入队抹掉", merged.unlike)
    }

    // ---------- 执行计划 ----------

    @Test
    fun flushOutcomeFor_neverTouchesLocalFileAgain() {
        val plan = flushOutcomeFor(PendingRemoval(9L, listOf(4L), unlike = true))
        assertEquals(listOf(4L), plan.remoteTargets)
        assertTrue(plan.unlike)
        // 本地文件在入队那一刻就删掉了;联网后重跑一遍只会对同一个 songId 再删一次
        assertFalse(plan.deleteLocal)
        assertTrue(plan.survivingEnabled.isEmpty())
    }

    // ---------- 执行之后还剩什么 ----------

    @Test
    fun remainingAfterFlush_isNullWhenEverythingSucceeded() {
        val entry = PendingRemoval(1L, listOf(1L, 2L), unlike = true)
        assertNull(remainingAfterFlush(entry, report(remoteOk = listOf(1L, 2L), unlikeOk = true)))
    }

    @Test
    fun remainingAfterFlush_keepsFailedPlaylists() {
        val entry = PendingRemoval(1L, listOf(1L, 2L, 3L), unlike = false)
        val left = remainingAfterFlush(entry, report(remoteOk = listOf(1L), remoteFailed = listOf(2L, 3L)))
        assertEquals(listOf(2L, 3L), left?.playlistIds)
        assertFalse(left!!.unlike)
    }

    @Test
    fun remainingAfterFlush_keepsStalePlaylists() {
        // 返回 200 却不生效是这个接口最常见的骗人方式,不能当成成功
        val entry = PendingRemoval(1L, listOf(1L, 2L), unlike = false)
        val left = remainingAfterFlush(entry, report(remoteOk = listOf(1L), stale = listOf(2L)))
        assertEquals(listOf(2L), left?.playlistIds)
    }

    @Test
    fun remainingAfterFlush_keepsUnlikeWhenItFailedOrWasNeverReported() {
        val entry = PendingRemoval(1L, emptyList(), unlike = true)
        assertTrue(remainingAfterFlush(entry, report(unlikeOk = false))!!.unlike)
        assertTrue(remainingAfterFlush(entry, report(unlikeOk = null))!!.unlike)
        assertNull(remainingAfterFlush(entry, report(unlikeOk = true)))
    }

    @Test
    fun remainingAfterFlush_keepsBothKindsOfLeftovers() {
        val entry = PendingRemoval(1L, listOf(1L, 2L), unlike = true)
        val left = remainingAfterFlush(entry, report(remoteOk = listOf(1L), remoteFailed = listOf(2L), unlikeOk = false))
        assertEquals(listOf(2L), left?.playlistIds)
        assertTrue(left!!.unlike)
        assertEquals(1L, left.songId)
    }

    // ---------- 歌单已经不存在 ----------

    @Test
    fun pruneTargets_dropsPlaylistsThatNoLongerExist() {
        val entry = PendingRemoval(1L, listOf(1L, 2L, 3L), unlike = false)
        val left = pruneTargets(entry, knownPlaylistIds = setOf(1L, 3L))
        assertEquals(listOf(1L, 3L), left?.playlistIds)
    }

    @Test
    fun pruneTargets_isNullWhenNothingIsLeft() {
        // 歌单全没了、也没红心要取消:整条可以丢掉,否则每次联网都白跑一次请求
        val entry = PendingRemoval(1L, listOf(1L, 2L), unlike = false)
        assertNull(pruneTargets(entry, knownPlaylistIds = emptySet()))
    }

    @Test
    fun pruneTargets_keepsUnlikeEvenWhenEveryPlaylistIsGone() {
        val entry = PendingRemoval(1L, listOf(1L), unlike = true)
        val left = pruneTargets(entry, knownPlaylistIds = emptySet())
        assertTrue(left!!.playlistIds.isEmpty())
        assertTrue(left.unlike)
    }

    @Test
    fun pendingRemoval_isEmptyOnlyWithoutAnyRemoteWork() {
        assertTrue(PendingRemoval(1L).isEmpty)
        assertFalse(PendingRemoval(1L, listOf(2L)).isEmpty)
        assertFalse(PendingRemoval(1L, unlike = true).isEmpty)
    }
}
