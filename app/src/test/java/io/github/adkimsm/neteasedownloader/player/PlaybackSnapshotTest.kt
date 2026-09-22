package io.github.adkimsm.neteasedownloader.player

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放快照的序列化与恢复。
 *
 * 重点在**恢复时要与本地库求交**:歌单取消勾选后本地文件会被同步删掉,
 * 快照里的 songId 可能已经不存在。不做求交就会出现"点继续播放,直接报错"。
 */
class PlaybackSnapshotTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private fun encode(snapshot: PlaybackSnapshot) =
        json.encodeToString(PlaybackSnapshot.serializer(), snapshot)

    private fun decode(raw: String) = json.decodeFromString(PlaybackSnapshot.serializer(), raw)

    @Test
    fun roundTrip_preservesEveryField() {
        val snapshot = PlaybackSnapshot(
            songIds = listOf(11L, 22L, 33L),
            index = 2,
            positionMs = 65_000L,
            repeat = Repeat.ALL.name,
            shuffle = true,
            sourcePlaylistId = 999L,
            savedAt = 1_700_000_000_000L,
        )
        assertEquals(snapshot, decode(encode(snapshot)))
    }

    @Test
    fun unknownFields_areIgnored() {
        val raw = """{"songIds":[1,2],"index":1,"futureField":"whatever"}"""
        val decoded = decode(raw)
        assertEquals(listOf(1L, 2L), decoded.songIds)
        assertEquals(1, decoded.index)
    }

    @Test
    fun missingFields_fallBackToDefaults() {
        val decoded = decode("""{"songIds":[7]}""")
        assertEquals(0, decoded.index)
        assertEquals(0L, decoded.positionMs)
        assertEquals(Repeat.OFF.name, decoded.repeat)
        assertTrue(!decoded.shuffle)
        assertNull(decoded.sourcePlaylistId)
    }

    @Test
    fun emptySnapshot_isEmpty() {
        assertTrue(PlaybackSnapshot.EMPTY.isEmpty)
        assertTrue(!PlaybackSnapshot(songIds = listOf(1L)).isEmpty)
    }

    @Test
    fun repeatFrom_knownValueIsPreserved() {
        assertEquals(Repeat.ONE, repeatFrom(Repeat.ONE.name))
        assertEquals(Repeat.ALL, repeatFrom(Repeat.ALL.name))
    }

    @Test
    fun repeatFrom_unknownValueFallsBackToOff() {
        assertEquals(Repeat.OFF, repeatFrom("LOOP_SOMETHING"))
        assertEquals(Repeat.OFF, repeatFrom(""))
        assertEquals(Repeat.OFF, repeatFrom(null))
    }

    @Test
    fun restoreQueue_keepsOnlySongsStillPresent() {
        val snapshot = PlaybackSnapshot(songIds = listOf(1L, 2L, 3L), index = 2, positionMs = 5_000L)
        val restored = restoreQueue(snapshot, availableIds = setOf(1L, 3L))
        assertNotNull(restored)
        assertEquals(listOf(1L, 3L), restored!!.songIds)
        assertEquals("当前曲仍在,应保持当前曲", 1, restored.index)
        assertEquals(5_000L, restored.positionMs)
    }

    @Test
    fun restoreQueue_currentSongRemoved_fallsBackToFirstWithoutPosition() {
        val snapshot = PlaybackSnapshot(songIds = listOf(1L, 2L, 3L), index = 2, positionMs = 9_000L)
        val restored = restoreQueue(snapshot, availableIds = setOf(1L, 2L))
        assertNotNull(restored)
        assertEquals(0, restored!!.index)
        assertEquals("当前曲没了,位置没有保留意义", 0L, restored.positionMs)
    }

    @Test
    fun restoreQueue_emptySnapshot_returnsNull() {
        assertNull(restoreQueue(PlaybackSnapshot.EMPTY, availableIds = setOf(1L)))
    }

    @Test
    fun restoreQueue_noSongSurvives_returnsNull() {
        val snapshot = PlaybackSnapshot(songIds = listOf(1L, 2L), index = 0)
        assertNull(restoreQueue(snapshot, availableIds = emptySet()))
    }

    @Test
    fun restoreQueue_outOfRangeIndex_isSafe() {
        val snapshot = PlaybackSnapshot(songIds = listOf(1L, 2L), index = 99, positionMs = 1_000L)
        val restored = restoreQueue(snapshot, availableIds = setOf(1L, 2L))
        assertEquals(1, restored!!.index)
    }

    @Test
    fun restoreQueue_negativeIndex_isSafe() {
        val snapshot = PlaybackSnapshot(songIds = listOf(1L, 2L), index = -3, positionMs = 1_000L)
        val restored = restoreQueue(snapshot, availableIds = setOf(1L, 2L))
        assertEquals(0, restored!!.index)
    }

    @Test
    fun restoreQueue_negativePosition_isClamped() {
        val snapshot = PlaybackSnapshot(songIds = listOf(1L), index = 0, positionMs = -42L)
        val restored = restoreQueue(snapshot, availableIds = setOf(1L))
        assertEquals(0L, restored!!.positionMs)
    }

    @Test
    fun restoreQueue_duplicateSongIds_keepFirstOccurrenceMapping() {
        val snapshot = PlaybackSnapshot(songIds = listOf(1L, 2L, 1L), index = 2, positionMs = 500L)
        val restored = restoreQueue(snapshot, availableIds = setOf(1L, 2L))
        assertEquals(listOf(1L, 2L, 1L), restored!!.songIds)
        assertEquals("重复 id 时当前曲取首次出现的位置", 0, restored.index)
    }
}
