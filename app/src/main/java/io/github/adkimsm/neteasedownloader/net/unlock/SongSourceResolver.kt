package io.github.adkimsm.neteasedownloader.net.unlock

import io.github.adkimsm.neteasedownloader.diag.Diag
import io.github.adkimsm.neteasedownloader.net.SongUrlDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 解锁用在哪条路径上。两条路径的开关**互相独立**(见 `SettingsStore`):
 * 串流不落盘、关掉立即可恢复;下载会产出永久的本地文件。
 */
enum class UnlockMode { DOWNLOAD, STREAM }

/** 一首歌的解析结果:要么是网易云直链([providerId] 为空),要么是替换来的第三方音源。 */
data class ResolvedUrl(val dto: SongUrlDto, val providerId: String? = null)

/**
 * 歌曲直链的唯一入口:先问网易云,拿不到可用直链时按需改用第三方音源。
 *
 * 为什么收口在这一层:下载([SyncEngine])与在线播放([NcmPlaybackSource])都要
 * "官方优先、第三方兜底",两处各写一遍必然会漂移。这里只做一件事 ——
 * 把官方响应里**不可用**的条目换掉,可用的原样放行。
 *
 * 几条刻意的设计:
 *  - **不可用 = url 为空 / 仅试听(freeTrialInfo 非空)**,与同步层原有的
 *    MISSING_URL 判定逐字一致;
 *  - 替换只发生在开关打开、且至少启用了一个音源时;
 *  - 匹配失败**保留官方响应原样返回**(url 仍为空),上层继续按 MISSING_URL 处理;
 *  - 并发压到 [MAX_CONCURRENCY],单轮上限 [MAX_PER_RUN] 首 —— 手表上不能因为
 *    几千首灰歌就把电量和流量打光;
 *  - 成功的替换由调用方各自缓存(下载有 urlCache、播放有 5 分钟 TTL);
 *    失败**不**缓存,provider 抽风是暂时的,下次该再试。
 */
class SongSourceResolver(
    private val official: suspend (List<Long>, String) -> List<SongUrlDto>,
    private val matcher: SourceMatcher,
    private val http: ProviderHttp,
    private val enabled: (UnlockMode) -> Boolean,
    private val activeProviderIds: () -> List<String>,
    private val onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    private val maxPerRun: Int = MAX_PER_RUN,
) {

    suspend fun fetchUrls(
        songs: List<SongQuery>,
        level: String,
        mode: UnlockMode,
    ): List<ResolvedUrl> {
        if (songs.isEmpty()) return emptyList()

        val officialById = official(songs.map { it.songId }, level).associateBy { it.id }
        val passthrough = { songs.mapNotNull { officialById[it.songId]?.let { dto -> ResolvedUrl(dto) } } }

        val providerIds = if (enabled(mode)) activeProviderIds() else emptyList()
        if (providerIds.isEmpty()) return passthrough()

        val targets = songs.filter { isUnusable(officialById[it.songId]) }.take(maxPerRun)
        if (targets.isEmpty()) return passthrough()

        Diag.i(TAG, "解锁(${mode.name}):${targets.size}/${songs.size} 首待匹配第三方音源")
        val matched = ConcurrentHashMap<Long, ResolvedUrl>()
        val semaphore = Semaphore(MAX_CONCURRENCY)
        val done = AtomicInteger(0)

        coroutineScope {
            targets.forEach { song ->
                launch(Dispatchers.IO) {
                    semaphore.withPermit {
                        val source = runCatching { matcher.match(http, song, providerIds) }.getOrNull()
                        if (source != null) {
                            matched[song.songId] = ResolvedUrl(
                                dto = source.toDto(song.songId, level),
                                providerId = source.providerId,
                            )
                        }
                    }
                    onProgress(done.incrementAndGet(), targets.size)
                }
            }
        }

        Diag.i(TAG, "解锁(${mode.name}):命中 ${matched.size}/${targets.size} 首")
        return songs.mapNotNull { song ->
            matched[song.songId] ?: officialById[song.songId]?.let { ResolvedUrl(it) }
        }
    }

    companion object {
        private const val TAG = "SongSourceResolver"

        /** 单轮最多匹配多少首:几千首灰歌不能一次性把电量与流量打光。 */
        const val MAX_PER_RUN = 200

        /** 并发 2:再高对音源不友好,也容易触发反爬。 */
        const val MAX_CONCURRENCY = 2

        /** 官方这条记录能不能直接用 */
        fun isUnusable(dto: SongUrlDto?): Boolean =
            dto == null || dto.url.isNullOrEmpty() || dto.freeTrialInfo != null
    }
}

/**
 * 把第三方音源包装成上层通用的 [SongUrlDto]。
 *
 * `fee = 0`、`freeTrialInfo = null` 是必须的:上层正是靠这两个字段判断"能播"。
 * `type` 用音源自报的扩展名(实测都是 mp3),文件名与 mime 都跟着它走。
 */
private fun MatchedSource.toDto(songId: Long, level: String): SongUrlDto = SongUrlDto(
    id = songId,
    url = url,
    br = br,
    size = size,
    md5 = md5,
    type = type,
    fee = 0,
    freeTrialInfo = null,
    level = level,
)
