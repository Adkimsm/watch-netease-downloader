package io.github.adkimsm.neteasedownloader.net.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 酷我搜索响应解析。
 *
 * 这个响应**不是合法 JSON**:单引号、键不带引号、条目里还嵌着
 * `audiobookpayinfo:{...}`。所以核心用例是 `handlesNestedObject` ——
 * 按 `},{` 硬切的实现会被它打挂。
 */
class KuwoParseTest {

    @Test
    fun parsesRealFixture() {
        val candidates = KuwoProvider.parseSearch(Fixtures.text("kuwo_search.txt"))
        assertEquals("真实 fixture 应解析出 3 条候选", 3, candidates.size)

        val first = candidates.first()
        assertEquals("228908", first.id)
        assertEquals("晴天", first.title)
        assertEquals("周杰伦", first.artist)
        assertEquals(269, first.durationSec)
    }

    @Test
    fun handlesNestedObject() {
        // 条目里嵌着「数组对象」(酷我搜索响应里 audiobookpayinfo 之类),
        // 内部会构成 `},{` 假边界:按 `},{` 硬切/每个 `}` 都收口的实现,
        // 会把一个条目切成两半、MUSICRID 掉进另一半被丢弃。
        val body = "{'abslist':[" +
            "{'NAME':'a','subs':[{'x':1},{'y':2}],'MUSICRID':'MUSIC_1','DURATION':'10'}," +
            "{'NAME':'b','MUSICRID':'MUSIC_2','DURATION':'20'}]}"
        val candidates = KuwoProvider.parseSearch(body)
        assertEquals("嵌套对象不能被切开", 2, candidates.size)
        assertEquals(listOf("1", "2"), candidates.map { it.id })
    }

    @Test
    fun decodesHtmlEntitiesInFields() {
        val body = "{'abslist':[{'NAME':'x','ARTIST':'Jay&nbsp;Chou'," +
            "'MUSICRID':'MUSIC_9','DURATION':'30'}]}"
        assertEquals("Jay Chou", KuwoProvider.parseSearch(body).single().artist)
    }

    @Test
    fun skipsEntriesWithoutRidOrName() {
        val body = "{'abslist':[{'NAME':'x','DURATION':'10'},{'MUSICRID':'MUSIC_3'}," +
            "{'NAME':'ok','MUSICRID':'MUSIC_4','DURATION':'11'}]}"
        assertEquals(listOf("4"), KuwoProvider.parseSearch(body).map { it.id })
    }

    @Test
    fun malformedBody_returnsEmpty() {
        assertTrue(KuwoProvider.parseSearch("").isEmpty())
        assertTrue(KuwoProvider.parseSearch("{\"code\":500}").isEmpty())
        assertTrue(KuwoProvider.parseSearch("'abslist':[{'NAME':'x'}]").isEmpty())
    }

    @Test
    fun missingDuration_becomesZero() {
        val body = "{'abslist':[{'NAME':'x','MUSICRID':'MUSIC_7'}]}"
        assertEquals(0, KuwoProvider.parseSearch(body).single().durationSec)
    }

    @Test
    fun buildUrls() {
        val query = SongQuery(songId = 1, name = "晴天", artist = "周杰伦", durationMs = 269_000)
        val search = KuwoProvider.buildSearchUrl(query)
        assertTrue(search.startsWith("http://search.kuwo.cn/r.s?"))
        assertTrue(search.contains("SONGNAME=%E6%99%B4%E5%A4%A9"))
        assertTrue(search.contains("ARTIST=%E5%91%A8%E6%9D%B0%E4%BC%A6"))

        assertEquals(
            "http://antiserver.kuwo.cn/anti.s?type=convert_url&format=mp3&response=url&rid=MUSIC_228908",
            KuwoProvider.buildTrackUrl("228908"),
        )
    }

    @Test
    fun splitTopLevelObjects_ignoresBracketsInsideStrings() {
        val text = "[{'a':'}','b':1},{'c':2}]"
        val parts = splitTopLevelObjects(text, 0)
        assertEquals(2, parts.size)
        assertEquals("{'a':'}','b':1}", parts[0])
        assertEquals("{'c':2}", parts[1])
    }
}
