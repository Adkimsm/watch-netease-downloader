package io.github.adkimsm.neteasedownloader.net.unlock

import io.github.adkimsm.neteasedownloader.net.SongUrlDto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析器:官方优先、第三方兜底,并且**两条路径的开关各自独立**。
 *
 * 这里全部用假音源与假网络,不打真实接口 —— 真实链路由 UnlockProviderLiveTest 覆盖。
 */
class SongSourceResolverTest {

    private val query = SongQuery(songId = 186016, name = "晴天", artist = "周杰伦", durationMs = 269_000)
    private val candidate = SourceCandidate(id = "228908", title = "晴天", artist = "周杰伦", durationSec = 269)
    private val okProbe = ProbeResult(206, 181_521, "audio/mpeg", null, hex("fffb90c4"))

    private fun unusable(id: Long) = SongUrlDto(
        id = id, url = null, br = 0, size = 0, md5 = null, type = null,
        fee = 1, freeTrialInfo = JsonPrimitive(true), level = "standard",
    )

    private fun usable(id: Long) = SongUrlDto(
        // 官方直链不经过匹配器,原样透传(下载/播放侧各自会 normalizeDownloadUrl)
        id = id, url = "http://official/$id.mp3", br = 320_000, size = 9_000_000, md5 = "abc",
        type = "mp3", fee = 0, freeTrialInfo = null, level = "standard",
    )

    private class FakeProvider(
        override val id: String,
        private val candidates: List<SourceCandidate>,
        // 假音源故意返回 http:匹配器必须把它规范化成 https(见 SourceMatcherTest),
        // 否则真机上探活会被 Android 明文策略拦掉、候选被白白丢掉
        private val trackUrl: String? = "http://thirdparty/$id.mp3",
    ) : SourceProvider {
        var searches = 0
        override suspend fun candidates(http: ProviderHttp, query: SongQuery): List<SourceCandidate> {
            searches++
            return candidates
        }

        override suspend fun track(http: ProviderHttp, candidate: SourceCandidate): TrackResult? =
            trackUrl?.let { TrackResult(url = it, size = 181_521, br = 128_000) }
    }

    private class FakeHttp(private val probe: ProbeResult?) : ProviderHttp {
        override suspend fun getText(url: String, headers: Map<String, String>): String? = null
        override suspend fun probe(url: String): ProbeResult? = probe
    }

    private fun resolver(
        official: suspend (List<Long>, String) -> List<SongUrlDto>,
        providers: List<SourceProvider>,
        enabled: (UnlockMode) -> Boolean = { true },
        activeProviderIds: () -> List<String> = { providers.map { provider -> provider.id } },
        maxPerRun: Int = SongSourceResolver.MAX_PER_RUN,
    ) = SongSourceResolver(
        official = official,
        matcher = SourceMatcher(providers),
        http = FakeHttp(okProbe),
        enabled = enabled,
        activeProviderIds = activeProviderIds,
        maxPerRun = maxPerRun,
    )

    @Test
    fun officialUsable_passesThrough_withoutSearching() = runBlocking {
        val provider = FakeProvider(ProviderId.KUWO, listOf(candidate))
        val result = resolver(official = { ids, _ -> ids.map { usable(it) } }, providers = listOf(provider))
            .fetchUrls(listOf(query), "standard", UnlockMode.DOWNLOAD)

        val single = result.single()
        assertNull("官方能用就不该换成第三方", single.providerId)
        assertEquals("http://official/186016.mp3", single.dto.url)
        assertEquals("官方可用时一次搜索都不该发生", 0, provider.searches)
    }

    @Test
    fun unusable_isReplacedByProviderSource() = runBlocking {
        val provider = FakeProvider(ProviderId.KUWO, listOf(candidate))
        val result = resolver(official = { ids, _ -> ids.map { unusable(it) } }, providers = listOf(provider))
            .fetchUrls(listOf(query), "standard", UnlockMode.DOWNLOAD)
            .single()

        assertEquals(ProviderId.KUWO, result.providerId)
        assertEquals("https://thirdparty/kuwo.mp3", result.dto.url)
        assertEquals("替换来的条目必须显式可播", 0, result.dto.fee)
        assertNull("freeTrialInfo 必须清掉,否则上层仍判不可用", result.dto.freeTrialInfo)
        assertEquals("mp3", result.dto.type)
        assertEquals(181_521L, result.dto.size)
        assertEquals(128_000L, result.dto.br)
        assertEquals(186016L, result.dto.id)
    }

    @Test
    fun modeGating_downloadOn_streamOff() = runBlocking {
        val provider = FakeProvider(ProviderId.KUWO, listOf(candidate))
        val subject = resolver(
            official = { ids, _ -> ids.map { unusable(it) } },
            providers = listOf(provider),
            enabled = { it == UnlockMode.DOWNLOAD },
        )

        assertNotNull(
            "下载开关开着 → 替换",
            subject.fetchUrls(listOf(query), "standard", UnlockMode.DOWNLOAD).single().providerId,
        )
        assertNull(
            "播放开关关着 → 不替换",
            subject.fetchUrls(listOf(query), "standard", UnlockMode.STREAM).single().providerId,
        )
        assertEquals("只该在下载那次搜过", 1, provider.searches)
    }

    @Test
    fun modeGating_downloadOff_streamOn() = runBlocking {
        val provider = FakeProvider(ProviderId.KUWO, listOf(candidate))
        val subject = resolver(
            official = { ids, _ -> ids.map { unusable(it) } },
            providers = listOf(provider),
            enabled = { it == UnlockMode.STREAM },
        )

        assertNull(
            "下载开关关着 → 不替换,这首歌应继续计入跳过",
            subject.fetchUrls(listOf(query), "standard", UnlockMode.DOWNLOAD).single().providerId,
        )
        assertNotNull(
            "播放开关开着 → 播放时替换",
            subject.fetchUrls(listOf(query), "standard", UnlockMode.STREAM).single().providerId,
        )
        assertEquals(1, provider.searches)
    }

    @Test
    fun noActiveProvider_skipsMatchingEntirely() = runBlocking {
        val provider = FakeProvider(ProviderId.KUWO, listOf(candidate))
        val result = resolver(
            official = { ids, _ -> ids.map { unusable(it) } },
            providers = listOf(provider),
            activeProviderIds = { emptyList() },
        ).fetchUrls(listOf(query), "standard", UnlockMode.DOWNLOAD).single()

        assertNull(result.providerId)
        assertNull("官方响应原样返回", result.dto.url)
        assertEquals(0, provider.searches)
    }

    @Test
    fun providerFailure_keepsOfficialEntry_andDoesNotThrow() = runBlocking {
        val provider = FakeProvider(ProviderId.KUWO, listOf(candidate), trackUrl = null)
        val result = resolver(official = { ids, _ -> ids.map { unusable(it) } }, providers = listOf(provider))
            .fetchUrls(listOf(query), "standard", UnlockMode.DOWNLOAD)
            .single()

        assertNull(result.providerId)
        assertNull("拿不到直链就保持原样,交给上层标 MISSING_URL", result.dto.url)
        assertNotNull(result.dto.freeTrialInfo)
    }

    @Test
    fun secondProviderIsUsed_whenFirstHasNoCandidates() = runBlocking {
        val kuwo = FakeProvider(ProviderId.KUWO, emptyList())
        val kugou = FakeProvider(ProviderId.KUGOU, listOf(candidate))
        val result = resolver(
            official = { ids, _ -> ids.map { unusable(it) } },
            providers = listOf(kuwo, kugou),
        ).fetchUrls(listOf(query), "standard", UnlockMode.DOWNLOAD).single()

        assertEquals(ProviderId.KUGOU, result.providerId)
        assertEquals(1, kuwo.searches)
        assertEquals(1, kugou.searches)
    }

    @Test
    fun maxPerRun_capsMatching_andLeavesTheRestUntouched() = runBlocking {
        val provider = FakeProvider(ProviderId.KUWO, listOf(candidate))
        val queries = (1L..5L).map { SongQuery(it, "晴天", "周杰伦", 269_000) }
        val result = resolver(
            official = { ids, _ -> ids.map { unusable(it) } },
            providers = listOf(provider),
            maxPerRun = 2,
        ).fetchUrls(queries, "standard", UnlockMode.DOWNLOAD)

        assertEquals("结果条数必须与入参一一对应", 5, result.size)
        assertEquals("超出上限的留到下一轮", 2, result.count { it.providerId != null })
        assertEquals(2, provider.searches)
    }

    @Test
    fun mixedInput_onlyUnusableIsReplaced() = runBlocking {
        val provider = FakeProvider(ProviderId.KUWO, listOf(candidate))
        val official = { ids: List<Long>, _: String ->
            ids.map { if (it == 1L) usable(it) else unusable(it) }
        }
        val queries = listOf(
            SongQuery(songId = 1L, name = "别的歌", artist = "某人", durationMs = 100_000),
            query,
        )
        val result = resolver(official = official, providers = listOf(provider))
            .fetchUrls(queries, "standard", UnlockMode.DOWNLOAD)

        assertNull(result.first { it.dto.id == 1L }.providerId)
        assertEquals(ProviderId.KUWO, result.first { it.dto.id == 186016L }.providerId)
        assertEquals("只有那首不可用的被搜过", 1, provider.searches)
    }

    @Test
    fun officialMissingEntry_isNotInvented() = runBlocking {
        val provider = FakeProvider(ProviderId.KUWO, emptyList())
        val result = resolver(official = { _, _ -> emptyList() }, providers = listOf(provider))
            .fetchUrls(listOf(query), "standard", UnlockMode.DOWNLOAD)

        assertTrue("官方没给这条记录时不应凭空造一个", result.isEmpty())
    }

    @Test
    fun isUnusable_matchesTheSyncLayersDefinition() {
        assertTrue(SongSourceResolver.isUnusable(null))
        assertTrue(SongSourceResolver.isUnusable(unusable(1L)))
        assertTrue(SongSourceResolver.isUnusable(usable(1L).copy(url = "")))
        assertTrue("仅试听也算不可用", SongSourceResolver.isUnusable(usable(1L).copy(freeTrialInfo = JsonPrimitive(true))))
        assertTrue("可用的不该被当成不可用", !SongSourceResolver.isUnusable(usable(1L)))
    }
}
