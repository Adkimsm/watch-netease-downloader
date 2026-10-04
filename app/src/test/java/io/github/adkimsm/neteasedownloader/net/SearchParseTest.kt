package io.github.adkimsm.neteasedownloader.net

import io.github.adkimsm.neteasedownloader.data.SongEntity
import io.github.adkimsm.neteasedownloader.data.SongState
import io.github.adkimsm.neteasedownloader.data.songEntityFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 搜索链路的解析与映射。
 *
 * - cloudsearch 的 result.songs 走 NcmJson 解码为 SongDto(与歌单详情同构);
 * - SongDto → SongEntity 的映射由 songEntityFrom 完成(纯函数)。
 */
class SearchParseTest {

    private fun downloadedOld() = SongEntity(
        songId = 186016,
        name = "晴天",
        artist = "周杰伦",
        album = "叶惠美",
        duration = 269000,
        md5 = "abc",
        size = 1024,
        br = 320000,
        type = "mp3",
        state = SongState.OK.name,
        localUri = "content://test/file.mp3",
        updatedAt = 0,
    )

    @Test
    fun cloudsearch_resp_decodes_songs() {
        val body = """
            {
              "code": 200,
              "result": {
                "songCount": 2,
                "songs": [
                  {
                    "id": 186016,
                    "name": "晴天",
                    "ar": [{ "id": 6452, "name": "周杰伦" }],
                    "al": { "id": 18918, "name": "叶惠美" },
                    "dt": 269000
                  },
                  {
                    "id": 108694,
                    "name": "稻香",
                    "ar": [
                      { "id": 6452, "name": "周杰伦" },
                      { "id": 6453, "name": "其他人" }
                    ],
                    "al": { "id": 72130, "name": "魔杰座" },
                    "dt": 223000
                  }
                ]
              }
            }
        """.trimIndent()

        val resp = NcmJson.decodeFromString<SearchResp>(body)

        assertEquals(200, resp.code)
        assertEquals(2, resp.result?.songCount)
        val songs = resp.result?.songs.orEmpty()
        assertEquals(2, songs.size)

        val first = songs[0]
        assertEquals(186016L, first.id)
        assertEquals("晴天", first.name)
        assertEquals("周杰伦", first.ar.single().name)
        assertEquals("叶惠美", first.al?.name)
        assertEquals(269000L, first.dt)
    }

    @Test
    fun cloudsearch_resp_without_result_yields_empty() {
        val resp = NcmJson.decodeFromString<SearchResp>("""{"code":200}""")
        assertEquals(0, resp.result?.songs.orEmpty().size)
        assertEquals(0, resp.result?.songCount ?: 0)
    }

    @Test
    fun songEntityFrom_new_row_is_pending() {
        val dto = SongDto(
            id = 186016,
            name = "晴天",
            ar = listOf(ArtistDto(6452, "周杰伦")),
            al = AlbumDto(18918, "叶惠美"),
            dt = 269000,
        )

        val entity = songEntityFrom(dto, old = null, now = 1000L)

        assertEquals(186016L, entity.songId)
        assertEquals("晴天", entity.name)
        assertEquals("周杰伦", entity.artist)
        assertEquals("叶惠美", entity.album)
        assertEquals(269000L, entity.duration)
        assertEquals(SongState.PENDING.name, entity.state)
        assertNull(entity.md5)
        assertEquals(0L, entity.size)
        assertNull(entity.localUri)
    }

    @Test
    fun songEntityFrom_multiple_artists_joined_with_slash() {
        val dto = SongDto(
            id = 108694,
            name = "稻香",
            ar = listOf(ArtistDto(6452, "周杰伦"), ArtistDto(6453, "其他人")),
            al = AlbumDto(72130, "魔杰座"),
            dt = 223000,
        )

        val entity = songEntityFrom(dto, old = null, now = 1000L)

        assertEquals("周杰伦/其他人", entity.artist)
    }

    @Test
    fun songEntityFrom_existing_row_keeps_local_facts() {
        val dto = SongDto(
            id = 186016,
            name = "晴天(新版名)",
            ar = listOf(ArtistDto(6452, "周杰伦")),
            al = null,
            dt = 270000,
        )

        val entity = songEntityFrom(dto, old = downloadedOld(), now = 2000L)

        // 远端元数据刷新
        assertEquals("晴天(新版名)", entity.name)
        assertEquals(270000L, entity.duration)
        assertNull(entity.album)
        // 本地下载事实原样保留
        assertEquals(SongState.OK.name, entity.state)
        assertEquals("content://test/file.mp3", entity.localUri)
        assertEquals("abc", entity.md5)
        assertEquals(1024L, entity.size)
    }
}
