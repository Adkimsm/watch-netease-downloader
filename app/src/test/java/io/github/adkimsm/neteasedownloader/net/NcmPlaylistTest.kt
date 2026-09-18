package io.github.adkimsm.neteasedownloader.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 直连 live 接口验证歌单/歌曲相关批量接口(匿名调用公开歌单)。
 */
class NcmPlaylistTest {
    private val api = NcmApi(CookieProvider { "" })

    @Test
    fun playlistDetail_songDetail_and_songUrls() = runBlocking {
        val detail = api.fetchPlaylistTrackIds(3778678L) // 热歌榜
        assertTrue("热歌榜应返回大量 trackIds", detail.trackIds.size > 10)

        val songs = api.fetchSongDetails(detail.trackIds.map { it.id }.take(5))
        assertEquals("歌曲详情应全量返回", 5, songs.size)
        assertTrue("歌曲应有名字", songs.all { it.name.isNotEmpty() })

        val urls = api.fetchSongUrls(songs.map { it.id }, "standard")
        assertEquals("下载地址应逐首返回", 5, urls.size)
    }
}
