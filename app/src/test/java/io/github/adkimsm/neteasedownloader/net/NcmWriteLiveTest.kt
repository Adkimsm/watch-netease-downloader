package io.github.adkimsm.neteasedownloader.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * eapi **写端点**的全链路 live 验证。
 *
 * 2026-09 起远端写操作从 weapi 迁到 eapi(weapi 对全部端点返回 HTTP 200 空 body,
 * 静默失败),本测试就是这次迁移的**验收闸门**:下面的每一步都真实打到网易云并读回确认。
 *
 * 这三件事(建歌单 / 加歌删歌 / 红心)每天都在生产里跑,但之前只能靠
 * 「写后读回」兜底 —— 因为它们读不到你的账号就跑不了。这个测试把整条链
 * 老老实实走一遍:自建临时歌单 → 加歌 → 红心 → 删歌 → 取消红心 → 删歌单,
 * 每一步都读回确认,**用完不留痕迹**。
 *
 * 只有设置 `NCM_MUSIC_U` 与 `NCM_UID` 两个环境变量才会执行,否则整类跳过:
 *
 * ```
 * docker exec android-dev bash -lc \
 *   'NCM_MUSIC_U=你的MUSIC_U NCM_UID=你的uid \
 *    cd /workspace/watchmusic && ./gradlew :app:testDebugUnitTest --rerun-tasks --tests "*NcmWriteLiveTest"'
 * ```
 *
 * MUSIC_U 从浏览器 devtools 里登录网易云后随便哪个请求的 Cookie 里复制;
 * 它是账号级凭证,别提交到仓库。uid 可以在设置里看,或从 `/api/user/playlist`
 * 的响应里抄。
 */
class NcmWriteLiveTest {

    private val musicU: String? = System.getenv("NCM_MUSIC_U")
    private val rawUid: String? = System.getenv("NCM_UID")
    private val uid: Long? = rawUid?.toLongOrNull()
    private val loggedIn: Boolean get() = !musicU.isNullOrEmpty() && uid != null && uid!! > 0L

    private val api = NcmApi(object : CookieProvider {
        override fun deviceHeader(): Map<String, String> = if (loggedIn) {
            mapOf(
                "osver" to "16.2",
                "deviceId" to "live-test-device",
                "os" to "iPhone OS",
                "appver" to "9.0.90",
                "versioncode" to "140",
                "mobilename" to "",
                "buildver" to "0",
                "resolution" to "1920x1080",
                "__csrf" to "",
                "channel" to "distribution",
                "requestId" to "${System.currentTimeMillis()}_1",
                "MUSIC_U" to musicU!!,
            )
        } else {
            emptyMap()
        }

        override fun cookieHeader(): String = if (loggedIn) {
            listOf(
                "osver" to "16.2",
                "deviceId" to "live-test-device",
                "os" to "iPhone OS",
                "appver" to "9.0.90",
                "versioncode" to "140",
                "buildver" to "0",
                "resolution" to "1920x1080",
                "channel" to "distribution",
                "requestId" to "${System.currentTimeMillis()}_1",
                "MUSIC_U" to musicU!!,
            ).joinToString("; ") { (k, v) -> "$k=$v" }
        } else {
            ""
        }
    })

    @Test
    fun fullLifecycle_createAddLikeRemoveUnlikeDelete() = runBlocking {
        assumeTrue("需要环境变量 NCM_MUSIC_U 与 NCM_UID 才执行(否则跳过)", loggedIn)

        // 陈奕迅《孤勇者》的公开歌曲 id,稳定存在
        val songId = 186016L
        val playlistId = api.createPlaylist("livedemo-${System.currentTimeMillis()}")
        assertTrue("新建歌单应返回 id", playlistId > 0)

        try {
            // 加歌 → 读回确认
            api.addTracksToPlaylist(playlistId, listOf(songId))
            assertTrue(
                "加歌后应能读回",
                api.fetchPlaylistTrackIds(playlistId).trackIds.any { it.id == songId },
            )

            // 红心 → 读回确认
            api.setLiked(songId, true)
            assertTrue("红心后应出现在喜欢列表", api.fetchLikedSongIds(uid!!).contains(songId))

            // 从歌单移除 → 读回确认
            api.removeTracksFromPlaylist(playlistId, listOf(songId))
            assertTrue(
                "移除后歌单不应再有该曲",
                api.fetchPlaylistTrackIds(playlistId).trackIds.none { it.id == songId },
            )

            // 取消红心 → 读回确认
            api.setLiked(songId, false)
            assertFalse("取消红心后不应在喜欢列表", api.fetchLikedSongIds(uid!!).contains(songId))
        } finally {
            // 无论上面哪一步失败,临时歌单都要删掉
            api.removePlaylists(listOf(playlistId))
        }
    }
}