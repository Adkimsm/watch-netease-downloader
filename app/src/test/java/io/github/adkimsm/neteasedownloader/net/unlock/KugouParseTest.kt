package io.github.adkimsm.neteasedownloader.net.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 酷狗搜索与取链响应解析。
 *
 * 两条实测结论被钉在这里:
 *  - HQ hash 需要付费(`HQPrivilege != 0`)时**不能**优先用它,只能当备选;
 *  - 取链走 `m.kugou.com/app/i/getSongInfo.php`(`trackercdn` 那条已失效)。
 */
class KugouParseTest {

    @Test
    fun parsesRealFixture() {
        val candidates = KugouProvider.parseSearch(Fixtures.text("kugou_search.json"))
        assertEquals(3, candidates.size)

        val liveClip = candidates[0]
        assertEquals("A925B6F4818F1F73FFD6B7E146010046", liveClip.id)
        assertEquals("晴天 (Live)", liveClip.title)
        assertEquals("周杰伦", liveClip.artist)
        assertEquals(134, liveClip.durationSec)
        assertNull("HQFileHash 为空时没有备选", liveClip.alternateId)

        val full = candidates[1]
        assertEquals(261, full.durationSec)
        assertEquals("4C1422690F23FBF67EB21DFDF0C4D841", full.id)
        assertEquals("HQ 需付费 → 仍用普通 hash", "25818C3E9080BE22F2997241F28F0D5E", full.alternateId)
    }

    @Test
    fun freeHqHash_isPreferredOverNormal() {
        val body = """{"data":{"lists":[{"SongName":"x","SingerName":"y","Duration":10,
            "FileHash":"NORMAL","HQFileHash":"HQ","HQFileSize":900,"HQPrivilege":0}]}}"""
        val candidate = KugouProvider.parseSearch(body).single()
        assertEquals("HQ", candidate.id)
        assertEquals("NORMAL", candidate.alternateId)
    }

    @Test
    fun blankFileHashEntriesAreSkipped() {
        val body = """{"data":{"lists":[
            {"SongName":"no-hash","FileHash":""},
            {"SongName":"ok","FileHash":"H1","Duration":10}]}}"""
        assertEquals(listOf("H1"), KugouProvider.parseSearch(body).map { it.id })
    }

    @Test
    fun emptyLists_returnsEmpty() {
        assertTrue(KugouProvider.parseSearch(Fixtures.text("kugou_search_empty.json")).isEmpty())
    }

    @Test
    fun malformedBody_returnsEmpty() {
        assertTrue(KugouProvider.parseSearch("not json").isEmpty())
        assertTrue(KugouProvider.parseSearch("{}").isEmpty())
        assertTrue(KugouProvider.parseSearch("""{"data":{"lists":null}}""").isEmpty())
    }

    @Test
    fun parsesTrackFixture() {
        val track = KugouProvider.parseTrack(Fixtures.text("kugou_track.json"))
        assertNotNull(track)
        requireNotNull(track)
        assertTrue(track.url.startsWith("http"))
        assertEquals(2145846L, track.size)
        assertEquals(128000L, track.br)
        assertEquals("mp3", track.type)
    }

    @Test
    fun trackWithNonOneStatus_isRejected() {
        assertNull(KugouProvider.parseTrack("""{"status":2,"url":"http://x/y.mp3"}"""))
    }

    @Test
    fun trackWithBlankOrNonHttpUrl_isRejected() {
        assertNull(KugouProvider.parseTrack("""{"status":1,"url":""}"""))
        assertNull(KugouProvider.parseTrack("""{"status":1,"url":"ftp://x/y.mp3"}"""))
    }

    @Test
    fun searchUrlEncodesKeyword() {
        val url = KugouProvider.buildSearchUrl(SongQuery(1, "晴天", "周杰伦", 0))
        assertTrue(url.startsWith("http://songsearch.kugou.com/song_search_v2?"))
        assertTrue(url.contains("keyword=%E6%99%B4%E5%A4%A9+%E5%91%A8%E6%9D%B0%E4%BC%A6"))
    }
}
