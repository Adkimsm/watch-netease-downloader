package io.github.adkimsm.neteasedownloader.ui

import io.github.adkimsm.neteasedownloader.data.SongEntity
import io.github.adkimsm.neteasedownloader.data.SongState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌单内搜索的筛选规则。
 *
 * 钉住两件事:
 *  - 空关键词(含纯空白)返回**原列表**,而不是空 —— 否则一进歌单就是"没有匹配的歌曲";
 *  - 多词是 AND 关系,「周杰伦 晴天」只留同时命中歌手与歌名的那些。
 */
class PlaylistSearchFilterTest {

    private fun song(id: Long, name: String, artist: String) = SongEntity(
        songId = id,
        name = name,
        artist = artist,
        album = null,
        duration = 0,
        md5 = null,
        size = 0,
        br = 0,
        type = null,
        state = SongState.PENDING.name,
        updatedAt = 0,
    )

    private val tracks = listOf(
        song(1, "晴天", "周杰伦"),
        song(2, "稻香", "周杰伦"),
        song(3, "晴天", "李荣浩"),
        song(4, "夜曲", "周杰伦/方文山"),
    )

    @Test
    fun blankQuery_returnsEverythingUntouched() {
        assertEquals(tracks, filterTracks(tracks, ""))
        assertEquals("纯空白不该被当成筛选条件", tracks, filterTracks(tracks, "   "))
        assertEquals(tracks, filterTracks(tracks, "　"))
    }

    @Test
    fun matchesSongName() {
        assertEquals(listOf(1L, 3L), filterTracks(tracks, "晴天").map { it.songId })
    }

    @Test
    fun matchesArtist() {
        assertEquals(listOf(1L, 2L, 4L), filterTracks(tracks, "周杰伦").map { it.songId })
    }

    @Test
    fun multipleTerms_areConjunctive() {
        assertEquals(
            "两个词都要命中,不能变成并集",
            listOf(1L),
            filterTracks(tracks, "周杰伦 晴天").map { it.songId },
        )
        assertEquals(emptyList<Long>(), filterTracks(tracks, "周杰伦 不存在的歌").map { it.songId })
    }

    @Test
    fun matchingIsCaseInsensitive() {
        val english = listOf(song(9, "Hello", "Adele"))
        assertEquals(listOf(9L), filterTracks(english, "hello").map { it.songId })
        assertEquals(listOf(9L), filterTracks(english, "ADELE").map { it.songId })
    }

    @Test
    fun noMatch_yieldsEmptyList() {
        assertTrue(filterTracks(tracks, "找不到的歌").isEmpty())
    }
}
