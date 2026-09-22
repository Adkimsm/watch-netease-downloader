package io.github.adkimsm.neteasedownloader.player

import io.github.adkimsm.neteasedownloader.data.SettingsStore
import io.github.adkimsm.neteasedownloader.data.SongDao
import io.github.adkimsm.neteasedownloader.diag.Diag
import io.github.adkimsm.neteasedownloader.net.NcmApi
import io.github.adkimsm.neteasedownloader.net.normalizeDownloadUrl
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 播放层的歌曲信息来源。
 *
 * 串流地址单独缓存,**与下载用的 SyncEngine.urlCache 无关**:
 *  - 直链带签名会过期,给 5 分钟复用窗口,避免每次 seek 都重新取地址;
 *  - `freeTrialInfo` 非空(仅试听)一律视为不可用 —— 与同步里 MISSING_URL 的判定保持一致;
 *  - 同一首歌的并发请求用 Mutex 收口,避免"下一首 + 预加载"同时打两次接口。
 */
class NcmPlaybackSource(
    private val songDao: SongDao,
    private val api: NcmApi,
    private val settingsStore: SettingsStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : PlaybackSource {

    private data class CachedUrl(val url: String, val expiresAt: Long)

    private val urlCache = ConcurrentHashMap<Long, CachedUrl>()
    private val lock = Mutex()

    override suspend fun localInfoOf(songId: Long): LocalInfo? =
        songDao.getByIds(listOf(songId)).firstOrNull()
            ?.let { LocalInfo(it.localUri, it.state) }

    override suspend fun streamUrlOf(songId: Long): String? {
        urlCache[songId]?.let { if (it.expiresAt > clock()) return it.url }

        return lock.withLock {
            urlCache[songId]?.let { if (it.expiresAt > clock()) return@withLock it.url }

            val level = settingsStore.streamLevel.value
            val dto = api.fetchSongUrls(listOf(songId), level).firstOrNull { it.id == songId }
            when {
                dto == null -> {
                    Diag.w(TAG, "串流地址缺失 songId=$songId level=$level")
                    null
                }

                dto.freeTrialInfo != null -> {
                    Diag.i(TAG, "仅试听,不串流 songId=$songId")
                    null
                }

                dto.url.isNullOrEmpty() -> {
                    Diag.w(TAG, "串流地址为空 songId=$songId level=$level")
                    null
                }

                else -> normalizeDownloadUrl(dto.url).also {
                    urlCache[songId] = CachedUrl(it, clock() + URL_TTL_MS)
                }
            }
        }
    }

    /** 直链失效(403/404)或本地文件消失时调用 */
    fun invalidate(songId: Long) {
        urlCache.remove(songId)
    }

    private companion object {
        const val TAG = "NcmPlaybackSource"
        const val URL_TTL_MS = 5 * 60 * 1000L
    }
}
