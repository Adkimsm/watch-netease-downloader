package io.github.adkimsm.neteasedownloader.ui

import io.github.adkimsm.neteasedownloader.data.SongEntity
import io.github.adkimsm.neteasedownloader.data.SongState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 曲目行「能不能点」的判定。
 *
 * 钉住的是双开关引入的那个坑:**下载开关关着时,灰歌会被同步标成 MISSING_URL**;
 * 若 [isPlayable] 不看播放开关,曲目行就会置灰且点不动 —— 播放开关等于白设,
 * 用户根本没有入口去播它。
 */
class PlaylistDetailPlayableTest {

    private fun song(state: SongState, localUri: String? = null) = SongEntity(
        songId = 1L,
        name = "晴天",
        artist = "周杰伦",
        album = null,
        duration = 269_000,
        md5 = null,
        size = 0,
        br = 0,
        type = null,
        state = state.name,
        localUri = localUri,
        updatedAt = 0,
    )

    @Test
    fun missingUrl_isPlayableOnlyWhenStreamFallbackIsOn() {
        val missing = song(SongState.MISSING_URL)
        assertFalse("播放开关关着 → 置灰,与今天一致", isPlayable(missing, streamFallback = false))
        assertTrue("播放开关开着 → 可点,播放时去匹配第三方音源", isPlayable(missing, streamFallback = true))
    }

    @Test
    fun localFile_isPlayableRegardlessOfFallback() {
        val local = song(SongState.OK, localUri = "content://media/external/audio/media/1")
        assertTrue(isPlayable(local, streamFallback = false))
        assertTrue(isPlayable(local, streamFallback = true))
    }

    @Test
    fun missingUrlWithStaleLocalUri_isStillUnplayableWithoutFallback() {
        // state 不是 OK 时 localUri 不作数:文件可能已被外部删掉
        val stale = song(SongState.MISSING_URL, localUri = "content://media/external/audio/media/9")
        assertFalse(isPlayable(stale, streamFallback = false))
        assertTrue(isPlayable(stale, streamFallback = true))
    }

    @Test
    fun pending_isPlayable() {
        assertTrue(isPlayable(song(SongState.PENDING), streamFallback = false))
    }
}
