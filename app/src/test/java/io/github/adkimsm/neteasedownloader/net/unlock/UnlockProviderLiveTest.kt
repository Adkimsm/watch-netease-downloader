package io.github.adkimsm.neteasedownloader.net.unlock

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 真实音源的 live 冒烟。
 *
 * 只有设置 `UNLOCK_LIVE=1` 才执行,否则整类跳过 —— 与 `NcmWriteLiveTest` 同法:
 * 这类测试依赖第三方站点,不该让每次构建都因为对方限流而变红。
 *
 * ```
 * docker exec android-dev bash -lc 'export UNLOCK_LIVE=1; cd /workspace/watchmusic && \
 *   ./gradlew :app:testDebugUnitTest --rerun-tasks --tests "*UnlockProviderLiveTest"'
 * ```
 *
 * 必须是 `export`:`VAR=1 cmd && ...` 只把变量绑给 `cd`,Gradle 与测试 JVM 都看不到它 ——
 * 那样测试会静默 skip,看着"通过"其实一步都没跑。
 *
 * 断言的是**端到端可播**:搜索 → 打分 → 取直链 → Range 探活。匹配器内部已经探活,
 * 所以能拿到非空结果就意味着这四步全过。
 */
class UnlockProviderLiveTest {

    private val live = System.getenv("UNLOCK_LIVE") == "1"

    private val http = OkHttpProviderHttp(
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build(),
    )

    private val query = SongQuery(songId = 186016, name = "晴天", artist = "周杰伦", durationMs = 269_000)

    private fun match(providerId: String): MatchedSource? = runBlocking {
        val provider = if (providerId == ProviderId.KUWO) KuwoProvider() else KugouProvider()
        SourceMatcher(listOf(provider)).match(http, query, listOf(providerId))
    }

    /**
     * 酷狗搜索实测会偶发返回 200 + 空 lists(限流/反爬),所以给它重试;
     * 酷我不需要。
     */
    private fun matchWithRetry(providerId: String, attempts: Int = 3): MatchedSource? {
        repeat(attempts - 1) {
            match(providerId)?.let { return it }
            Thread.sleep(1500)
        }
        return match(providerId)
    }

    @Test
    fun kuwo_returnsPlayableSource() {
        assumeTrue("设置 UNLOCK_LIVE=1 才跑真实音源", live)
        val matched = match(ProviderId.KUWO)
        assertNotNull("酷我应能匹配到可播放直链", matched)
        requireNotNull(matched)
        assertTrue("直链必须是 http(s)", matched.url.startsWith("http"))
        assertTrue("探活应拿到音频大小,实际 ${matched.size}", matched.size > 0)
        assertTrue("探活应能解析出码率,实际 ${matched.br}", matched.br > 0)
        assertTrue("替换来源必须是酷我", matched.providerId == ProviderId.KUWO)
    }

    @Test
    fun kugou_returnsPlayableSource() {
        assumeTrue("设置 UNLOCK_LIVE=1 才跑真实音源", live)
        val matched = matchWithRetry(ProviderId.KUGOU)
        assertNotNull("酷狗应能匹配到可播放直链(已重试 3 次)", matched)
        requireNotNull(matched)
        assertTrue("直链必须是 http(s)", matched.url.startsWith("http"))
        assertTrue("探活应拿到音频大小,实际 ${matched.size}", matched.size > 0)
    }
}
