package io.github.adkimsm.neteasedownloader.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自定义播放 scheme 的解析。
 *
 * 队列里所有曲目都用 `watchmusic://song/<id>`,真实地址由 LocalFirstResolver 在
 * 打开前解析 —— 所以这个解析必须足够严:把别的 scheme 误认成歌曲 URI 会让
 * ExoPlayer 拿到一个永远打不开的地址。
 */
class PlaybackUriTest {

    @Test
    fun forSong_roundTrips() {
        assertEquals(123456L, PlaybackUri.songIdOf(PlaybackUri.forSong(123456L)))
    }

    @Test
    fun songIdOf_rejectsOtherSchemes() {
        assertNull(PlaybackUri.songIdOf("https://music.163.com/song/1"))
        assertNull(PlaybackUri.songIdOf("content://media/external_primary/audio/media/42"))
        assertNull(PlaybackUri.songIdOf("file:///sdcard/a.mp3"))
    }

    @Test
    fun songIdOf_rejectsGarbageAndBlank() {
        assertNull(PlaybackUri.songIdOf(null))
        assertNull(PlaybackUri.songIdOf(""))
        assertNull(PlaybackUri.songIdOf("   "))
        assertNull(PlaybackUri.songIdOf("watchmusic://song/abc"))
        assertNull(PlaybackUri.songIdOf("watchmusic://song/"))
    }

    @Test
    fun songIdOf_rejectsNonPositiveIds() {
        assertNull(PlaybackUri.songIdOf("watchmusic://song/0"))
        assertNull(PlaybackUri.songIdOf("watchmusic://song/-5"))
    }

    @Test
    fun songIdOf_toleratesTrailingSegmentsAndQuery() {
        assertEquals(7L, PlaybackUri.songIdOf("watchmusic://song/7/extra"))
        assertEquals(7L, PlaybackUri.songIdOf("watchmusic://song/7?x=1"))
    }

    @Test
    fun songIdOf_toleratesSurroundingWhitespace() {
        assertEquals(9L, PlaybackUri.songIdOf("  watchmusic://song/9  "))
    }

    @Test
    fun isPlaybackUri_matchesSongIdOf() {
        assertTrue(PlaybackUri.isPlaybackUri(PlaybackUri.forSong(1L)))
        assertTrue(!PlaybackUri.isPlaybackUri("https://example.com/a.mp3"))
    }

    @Test
    fun forSong_matchesDeclaredScheme() {
        assertNotNull(PlaybackUri.SCHEME)
        assertEquals("watchmusic", PlaybackUri.SCHEME)
        assertTrue(PlaybackUri.forSong(5L).startsWith("watchmusic://"))
    }
}
