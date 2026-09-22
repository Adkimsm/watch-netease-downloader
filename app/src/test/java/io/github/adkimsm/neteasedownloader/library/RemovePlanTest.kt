package io.github.adkimsm.neteasedownloader.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「删除这首歌」的范围决策。
 *
 * 这些用例钉住的是三件容易悄悄改坏的事:
 *  1. **红心一律自动取消**,与模式、与面板勾选都无关(有人"顺手"加个开关就会挂);
 *  2. **他人歌单永远不可能是删除目标**(接口会拒绝,勾了也白勾);
 *  3. **后果预览**:删完还剩哪些已勾选歌单含这首歌 —— 那些歌下一轮同步会被重新下载,
 *     必须在按删除之前就告诉用户。
 */
class RemovePlanTest {

    private fun entry(id: Long, owned: Boolean, enabled: Boolean = false, name: String = "歌单$id") =
        PresenceEntry(playlistId = id, name = name, owned = owned, enabled = enabled)

    private fun presence(
        playlists: List<PresenceEntry> = emptyList(),
        liked: Boolean = false,
    ) = SongPresence(songId = 1L, songName = "难听的歌", playlists = playlists, liked = liked)

    // ---------- 设置项解析 ----------

    @Test
    fun removeScopeFrom_parsesEveryValidValue() {
        assertEquals(RemoveScope.ASK, removeScopeFrom("ASK"))
        assertEquals(RemoveScope.ALL, removeScopeFrom("ALL"))
        assertEquals(RemoveScope.LOCAL_ONLY, removeScopeFrom("LOCAL_ONLY"))
    }

    @Test
    fun removeScopeFrom_isCaseInsensitive() {
        assertEquals(RemoveScope.ALL, removeScopeFrom("all"))
        assertEquals(RemoveScope.LOCAL_ONLY, removeScopeFrom("local_only"))
    }

    @Test
    fun removeScopeFrom_fallsBackToAskOnGarbage() {
        // 旧版本残留、手改配置文件、空值 —— 一律回到最安全的"每次询问"
        assertEquals(RemoveScope.ASK, removeScopeFrom(null))
        assertEquals(RemoveScope.ASK, removeScopeFrom(""))
        assertEquals(RemoveScope.ASK, removeScopeFrom("DELETE_EVERYTHING"))
    }

    // ---------- 默认选择 ----------

    @Test
    fun defaultSelection_selectsEveryOwnedPlaylist() {
        val p = presence(listOf(entry(1L, owned = true), entry(2L, owned = true), entry(3L, owned = false)))
        assertEquals(setOf(1L, 2L), defaultSelection(p).playlistIds)
    }

    @Test
    fun defaultSelection_doesNotAskToKeepLocal() {
        val p = presence(listOf(entry(1L, owned = true)))
        val selection = defaultSelection(p)
        assertFalse(selection.keepLocal)
        assertFalse(selection.localOnly)
    }

    // ---------- ASK 模式 ----------

    @Test
    fun ask_defaultPlan_targetsAllOwnedPlaylistsAndDeletesLocal() {
        val p = presence(listOf(entry(1L, owned = true), entry(2L, owned = true)))
        val outcome = planFor(RemoveScope.ASK, p, defaultSelection(p))
        assertEquals(listOf(1L, 2L), outcome.remoteTargets)
        assertTrue(outcome.deleteLocal)
    }

    @Test
    fun ask_uncheckingAPlaylist_leavesItAsSurvivingEnabled() {
        // 只从歌单 1 移除,歌单 2 仍勾着同步 → 下一轮同步会把文件下回来
        val p = presence(
            listOf(
                entry(1L, owned = true, enabled = true),
                entry(2L, owned = true, enabled = true),
            ),
        )
        val outcome = planFor(RemoveScope.ASK, p, RemoveSelection(playlistIds = setOf(1L)))
        assertEquals(listOf(1L), outcome.remoteTargets)
        assertEquals(listOf(2L), outcome.survivingEnabled.map { it.playlistId })
    }

    @Test
    fun ask_survivingOnlyCountsEnabledPlaylists() {
        // 未勾选同步的歌单留着这首歌无所谓 —— 它本来就不会下载
        val p = presence(
            listOf(
                entry(1L, owned = true, enabled = true),
                entry(2L, owned = true, enabled = false),
            ),
        )
        val outcome = planFor(RemoveScope.ASK, p, RemoveSelection(playlistIds = setOf(1L)))
        assertTrue(outcome.survivingEnabled.isEmpty())
    }

    @Test
    fun ask_localOnly_touchesNoPlaylist() {
        val p = presence(listOf(entry(1L, owned = true, enabled = true)))
        val outcome = planFor(RemoveScope.ASK, p, RemoveSelection(localOnly = true))
        assertTrue(outcome.remoteTargets.isEmpty())
        assertTrue(outcome.deleteLocal)
    }

    @Test
    fun ask_localOnly_takesPrecedenceOverKeepLocal() {
        // 两个开关理论上互斥;真出现都勾的异常状态,以"只删本地"为准,
        // 而不是变成一次什么都不做的删除
        val p = presence(listOf(entry(1L, owned = true)))
        val outcome = planFor(
            RemoveScope.ASK,
            p,
            RemoveSelection(localOnly = true, keepLocal = true),
        )
        assertTrue(outcome.remoteTargets.isEmpty())
        assertTrue(outcome.deleteLocal)
    }

    @Test
    fun ask_keepLocal_onlyTidiesPlaylists() {
        val p = presence(listOf(entry(1L, owned = true), entry(2L, owned = true)))
        val outcome = planFor(
            RemoveScope.ASK,
            p,
            RemoveSelection(playlistIds = setOf(1L, 2L), keepLocal = true),
        )
        assertEquals(listOf(1L, 2L), outcome.remoteTargets)
        assertFalse(outcome.deleteLocal)
    }

    @Test
    fun ask_keepLocal_hasNoRedownloadWarning() {
        // 文件本来就没删,不存在"下次同步下回来"
        val p = presence(listOf(entry(1L, owned = true, enabled = true), entry(2L, owned = true, enabled = true)))
        val outcome = planFor(
            RemoveScope.ASK,
            p,
            RemoveSelection(playlistIds = setOf(1L), keepLocal = true),
        )
        assertTrue(outcome.survivingEnabled.isEmpty())
    }

    // ---------- 快模式 ----------

    @Test
    fun all_targetsEveryOwnedPlaylistAndDeletesLocal() {
        val p = presence(
            listOf(
                entry(1L, owned = true),
                entry(2L, owned = false),
                entry(3L, owned = true),
            ),
        )
        val outcome = planFor(RemoveScope.ALL, p, selection = null)
        assertEquals(listOf(1L, 3L), outcome.remoteTargets)
        assertTrue(outcome.deleteLocal)
    }

    @Test
    fun localOnlyFastMode_hasNoRemoteTargets() {
        val p = presence(listOf(entry(1L, owned = true), entry(2L, owned = true)))
        val outcome = planFor(RemoveScope.LOCAL_ONLY, p, selection = null)
        assertTrue(outcome.remoteTargets.isEmpty())
        assertTrue(outcome.deleteLocal)
    }

    @Test
    fun localOnlyFastMode_stillWarnsAboutRedownload() {
        val p = presence(listOf(entry(1L, owned = true, enabled = true)))
        val outcome = planFor(RemoveScope.LOCAL_ONLY, p, selection = null)
        assertEquals(listOf(1L), outcome.survivingEnabled.map { it.playlistId })
    }

    // ---------- 他人歌单 ----------

    @Test
    fun notOwnedPlaylists_areNeverRemoteTargets() {
        val p = presence(listOf(entry(1L, owned = false), entry(2L, owned = false)))
        listOf(RemoveScope.ASK, RemoveScope.ALL, RemoveScope.LOCAL_ONLY).forEach { scope ->
            assertTrue(
                "scope=$scope 不该把他人歌单当成删除目标",
                planFor(scope, p, defaultSelection(p)).remoteTargets.isEmpty(),
            )
        }
    }

    @Test
    fun notOwnedEnabledPlaylist_becomesASurvivingWarning() {
        // 收藏来的歌单删不掉远端,只能靠取消勾选同步 —— 后果预览必须说清楚
        val p = presence(listOf(entry(9L, owned = false, enabled = true)))
        val outcome = planFor(RemoveScope.ALL, p, selection = null)
        assertTrue(outcome.remoteTargets.isEmpty())
        assertEquals(listOf(9L), outcome.survivingEnabled.map { it.playlistId })
    }

    // ---------- 红心:恒自动 ----------

    @Test
    fun unlikeIsAutomatic_whenTheSongIsLiked() {
        val p = presence(listOf(entry(1L, owned = true)), liked = true)
        assertTrue(planFor(RemoveScope.ALL, p, null).unlike)
        assertTrue(planFor(RemoveScope.LOCAL_ONLY, p, null).unlike)
        assertTrue(planFor(RemoveScope.ASK, p, defaultSelection(p)).unlike)
    }

    @Test
    fun unlikeStaysOff_whenTheSongIsNotLiked() {
        val p = presence(listOf(entry(1L, owned = true)), liked = false)
        listOf(RemoveScope.ALL, RemoveScope.LOCAL_ONLY).forEach { scope ->
            assertFalse(planFor(scope, p, null).unlike)
        }
    }

    @Test
    fun unlikeIsUnaffectedByLocalOnlyOrKeepLocal() {
        // 这是 D14 的回归闸门:谁把红心做成可选项,这里就会挂
        val p = presence(listOf(entry(1L, owned = true)), liked = true)
        assertTrue(planFor(RemoveScope.ASK, p, RemoveSelection(localOnly = true)).unlike)
        assertTrue(planFor(RemoveScope.ASK, p, RemoveSelection(keepLocal = true)).unlike)
    }

    // ---------- 空处境 ----------

    @Test
    fun emptyPresence_stillDeletesLocalFile() {
        val outcome = planFor(RemoveScope.ALL, presence(), selection = null)
        assertTrue(outcome.remoteTargets.isEmpty())
        assertTrue(outcome.deleteLocal)
        assertTrue(outcome.survivingEnabled.isEmpty())
        assertFalse(outcome.unlike)
    }

    @Test
    fun removeTargetCount_matchesTargets() {
        val p = presence(listOf(entry(1L, owned = true), entry(2L, owned = true)))
        assertEquals(2, removeTargetCount(planFor(RemoveScope.ALL, p, null)))
        assertEquals(0, removeTargetCount(planFor(RemoveScope.LOCAL_ONLY, p, null)))
    }
}
