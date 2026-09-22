package io.github.adkimsm.neteasedownloader.sync

import io.github.adkimsm.neteasedownloader.data.SongEntity
import io.github.adkimsm.neteasedownloader.data.SongState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同步待删集合的纯逻辑。
 *
 * 两个用例是回归闸门:
 *  - [browsedButNotDownloadedMetadata_isNeverDeleted]:浏览过的未勾选歌单会留下
 *    未下载的元数据缓存,它们**不是**待删对象 —— 一旦有人把"只看有没有本地文件"
 *    改回"所有 song 行",这条会挂;
 *  - [currentlyPlayingSong_isProtectedForOneRound]:正在听的歌不能被同步删掉。
 */
class DiffPlannerTest {

    private fun song(
        id: Long,
        state: String = SongState.OK.name,
        localUri: String? = "content://media/audio/$id",
    ) = SongEntity(
        songId = id,
        name = "歌名$id",
        artist = "歌手",
        album = null,
        duration = 0L,
        md5 = null,
        size = 0L,
        br = 0L,
        type = "mp3",
        state = state,
        localUri = localUri,
        updatedAt = 0L,
    )

    @Test
    fun songStillInEnabledPlaylist_isNotDeleted() {
        val downloaded = listOf(song(1L), song(2L))
        val plan = planLocalDeletions(downloaded, enabledRemoteIds = setOf(1L, 2L))
        assertTrue(plan.isEmpty())
    }

    @Test
    fun songLeavingAllEnabledPlaylists_isDeleted() {
        val downloaded = listOf(song(1L), song(2L))
        val plan = planLocalDeletions(downloaded, enabledRemoteIds = setOf(1L))
        assertEquals(listOf(2L), plan.map { it.songId })
    }

    @Test
    fun songSharedByTwoPlaylists_survivesWhileOneStillHasIt() {
        // 引用计数的等价表述:只要还有一个勾选歌单含它,就不删
        val downloaded = listOf(song(7L))
        assertTrue(planLocalDeletions(downloaded, enabledRemoteIds = setOf(7L)).isEmpty())
        assertEquals(1, planLocalDeletions(downloaded, enabledRemoteIds = emptySet()).size)
    }

    @Test
    fun browsedButNotDownloadedMetadata_isNeverDeleted() {
        // 未下载的歌(localUri 为空)根本不在 downloaded 集合里 ——
        // 这正是"浏览缓存不会每轮同步被清掉"的保证
        val downloaded = listOf(song(1L, state = SongState.OK.name, localUri = null))
        val plan = planLocalDeletions(downloaded, enabledRemoteIds = emptySet())
        assertTrue("没有本地文件的歌不该进待删集合", plan.isEmpty())
    }

    @Test
    fun currentlyPlayingSong_isProtectedForOneRound() {
        val downloaded = listOf(song(1L), song(2L), song(3L))
        val plan = planLocalDeletions(
            downloaded,
            enabledRemoteIds = emptySet(),
            playingSongId = 2L,
        )
        assertEquals(listOf(1L, 3L), plan.map { it.songId })
    }

    @Test
    fun currentlyPlayingSong_stillInPlaylist_isNotDeletedEither() {
        val downloaded = listOf(song(5L))
        val plan = planLocalDeletions(
            downloaded,
            enabledRemoteIds = setOf(5L),
            playingSongId = 5L,
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun protectedSongIsNotSpecialWhenItIsNotDownloaded() {
        // 保护只对"本来会被删的已下载文件"有意义;不在候选里的歌不受影响
        val downloaded = listOf(song(1L))
        val plan = planLocalDeletions(
            downloaded,
            enabledRemoteIds = emptySet(),
            playingSongId = 999L,
        )
        assertEquals(listOf(1L), plan.map { it.songId })
    }

    @Test
    fun emptyInputs_produceEmptyPlan() {
        assertTrue(planLocalDeletions(emptyList(), emptySet()).isEmpty())
    }

    @Test
    fun orderIsPreserved() {
        val downloaded = listOf(song(30L), song(10L), song(20L))
        val plan = planLocalDeletions(downloaded, enabledRemoteIds = setOf(10L))
        assertEquals("顺序应保持输入顺序,便于复用上层排序", listOf(30L, 20L), plan.map { it.songId })
    }
}
