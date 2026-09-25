package io.github.adkimsm.neteasedownloader.net.unlock

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 匹配器自身的行为(解析器测试只间接覆盖到它)。
 *
 * 重点是**探活前的 URL 规范化**:Android(targetSdk ≥ 28)默认禁止明文流量,
 * 音源若返回 `http://`,不规范化就会在真机上探活失败 —— 候选被白白丢掉,
 * 而下载/播放侧其实已经由 `normalizeDownloadUrl` 修正过,属于白丢。
 */
class SourceMatcherTest {

    private val query = SongQuery(songId = 186016, name = "晴天", artist = "周杰伦", durationMs = 269_000)

    private class FakeProvider(
        override val id: String,
        private val candidates: List<SourceCandidate>,
        private val urlOf: (SourceCandidate) -> String? = { "https://cdn/$id/${it.id}.mp3" },
    ) : SourceProvider {
        override suspend fun candidates(http: ProviderHttp, query: SongQuery): List<SourceCandidate> =
            candidates

        override suspend fun track(http: ProviderHttp, candidate: SourceCandidate): TrackResult? =
            urlOf(candidate)?.let { TrackResult(url = it, size = 1000, br = 128_000) }
    }

    /** 记录被探活的地址;`failFirst` 用于让前 N 次探活失败 */
    private class RecordingHttp(
        private val failFirst: Int = 0,
        private val totalBytes: Long = FULL_TRACK_BYTES,
        private val bytesOf: ((Int) -> Long)? = null,
    ) : ProviderHttp {
        val probed = mutableListOf<String>()

        override suspend fun getText(url: String, headers: Map<String, String>): String? = null

        override suspend fun probe(url: String): ProbeResult? {
            probed += url
            if (probed.size <= failFirst) return null
            val bytes = bytesOf?.invoke(probed.size) ?: totalBytes
            return ProbeResult(206, bytes, "audio/mpeg", null, hex("fffb90c4"))
        }
    }

    private fun candidate(id: String, durationSec: Int = 269) =
        SourceCandidate(id = id, title = "晴天", artist = "周杰伦", durationSec = durationSec)

    @Test
    fun cleartextCandidateUrl_isNormalizedBeforeProbe() = runBlocking {
        val http = RecordingHttp()
        val provider = FakeProvider(ProviderId.KUWO, listOf(candidate("228908"))) {
            "http://cdn.kuwo.cn/a.mp3"
        }

        val matched = SourceMatcher(listOf(provider)).match(http, query, listOf(ProviderId.KUWO))

        assertEquals("探活必须打 https 地址", listOf("https://cdn.kuwo.cn/a.mp3"), http.probed)
        assertEquals(
            "交给下载/播放的地址也必须是 https",
            "https://cdn.kuwo.cn/a.mp3",
            matched?.url,
        )
    }

    @Test
    fun probeFailure_fallsThroughToNextCandidate() = runBlocking {
        val http = RecordingHttp(failFirst = 1)
        val provider = FakeProvider(ProviderId.KUWO, listOf(candidate("A"), candidate("B")))

        val matched = SourceMatcher(listOf(provider)).match(http, query, listOf(ProviderId.KUWO))

        assertEquals("第一条探活失败后应换第二条", 2, http.probed.size)
        assertEquals("https://cdn/kuwo/B.mp3", matched?.url)
    }

    @Test
    fun maxCandidatesPerProvider_isRespected() = runBlocking {
        val http = RecordingHttp(failFirst = Int.MAX_VALUE)
        val provider = FakeProvider(ProviderId.KUWO, (1..5).map { candidate("C$it") })

        val matched = SourceMatcher(listOf(provider)).match(http, query, listOf(ProviderId.KUWO))

        assertNull(matched)
        assertEquals(
            "每个音源最多探活这么多条",
            SourceMatcher.MAX_CANDIDATES_PER_PROVIDER,
            http.probed.size,
        )
    }

    @Test
    fun secondProviderIsUsed_whenFirstHasNoCandidates() = runBlocking {
        val http = RecordingHttp()
        val kuwo = FakeProvider(ProviderId.KUWO, emptyList())
        val kugou = FakeProvider(ProviderId.KUGOU, listOf(candidate("HASH")))

        val matched = SourceMatcher(listOf(kuwo, kugou)).match(http, query, ProviderId.ALL)

        assertEquals(ProviderId.KUGOU, matched?.providerId)
        assertEquals(listOf("https://cdn/kugou/HASH.mp3"), http.probed)
    }

    @Test
    fun candidateBelowScoreThreshold_isNotProbed() = runBlocking {
        val http = RecordingHttp()
        val provider = FakeProvider(
            ProviderId.KUWO,
            listOf(SourceCandidate(id = "1", title = "七里香", artist = "周杰伦", durationSec = 269)),
        )

        val matched = SourceMatcher(listOf(provider)).match(http, query, listOf(ProviderId.KUWO))

        assertNull(matched)
        assertTrue("分数不够的候选不该浪费一次探活", http.probed.isEmpty())
    }

    @Test
    fun previewClip_isSkipped_andNextCandidateUsed() = runBlocking {
        // 第一次探活是酷我那种固定 181521 字节的提示音,第二次才是整曲
        val http = RecordingHttp(bytesOf = { n -> if (n == 1) PREVIEW_BYTES else FULL_TRACK_BYTES })
        val provider = FakeProvider(ProviderId.KUWO, listOf(candidate("preview"), candidate("full")))

        val matched = SourceMatcher(listOf(provider)).match(http, query, listOf(ProviderId.KUWO))

        assertEquals("试听片段必须丢掉,换下一条候选", "https://cdn/kuwo/full.mp3", matched?.url)
        assertEquals(2, http.probed.size)
    }

    @Test
    fun previewClip_fallsThroughToNextProvider() = runBlocking {
        val http = RecordingHttp(bytesOf = { n -> if (n == 1) PREVIEW_BYTES else FULL_TRACK_BYTES })
        val kuwo = FakeProvider(ProviderId.KUWO, listOf(candidate("A")))
        val kugou = FakeProvider(ProviderId.KUGOU, listOf(candidate("HASH")))

        val matched = SourceMatcher(listOf(kuwo, kugou)).match(http, query, ProviderId.ALL)

        assertEquals(ProviderId.KUGOU, matched?.providerId)
        assertEquals("https://cdn/kugou/HASH.mp3", matched?.url)
    }

    @Test
    fun isPreviewClip_rejectsElevenSecondNotice() {
        assertEquals(11, durationFromBytes(PREVIEW_BYTES, 128_000))
        assertTrue(isPreviewClip(267, 11))
        assertTrue("整曲不该被当成试听", isPreviewClip(269, 269).not())
        assertTrue("标称时长未知时不判", isPreviewClip(0, 11).not())
        assertTrue("码率读不出时不判", isPreviewClip(269, null).not())
    }

    private companion object {
        /** 269s * 128kbps */
        const val FULL_TRACK_BYTES = 4_304_000L

        /** 酷我实测返回的固定提示音大小 */
        const val PREVIEW_BYTES = 181_521L
    }
}
