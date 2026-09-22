package io.github.adkimsm.neteasedownloader.player

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import io.github.adkimsm.neteasedownloader.data.SongState
import io.github.adkimsm.neteasedownloader.diag.Diag
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** 一首歌解析后的播放目标 */
sealed interface ResolveTarget {
    /** 本地已下载文件(content://…) */
    data class Local(val uri: String) : ResolveTarget

    /** 网易云签名直链(https://…),边下边播、不落盘 */
    data class Stream(val url: String) : ResolveTarget

    /** 本地没有、也拿不到可用地址(无网 / 无版权 / 仅试听) */
    data object Unavailable : ResolveTarget
}

/** 歌曲不可播放。ExoPlayer 会把它包成 PlaybackException 交给播放层映射文案。 */
class StreamUnavailableException(message: String) : IOException(message)

/** 本地文件与下载状态 */
data class LocalInfo(val localUri: String?, val state: String)

/** 解析一首歌所需的最小信息来源(接口化,便于在 App 里注入 DAO/网络实现) */
interface PlaybackSource {
    suspend fun localInfoOf(songId: Long): LocalInfo?

    /** 已校验可用的串流地址;无版权/仅试听/取不到时返回 null */
    suspend fun streamUrlOf(songId: Long): String?
}

/**
 * 本地优先的唯一决策点。
 *
 * 纯函数,不碰数据库/网络/时钟 —— 全部分支都能钉进单测(见 LocalFirstResolverTest)。
 *
 * 规则:
 *  1. 本地文件在且状态为 OK → 播本地(完全离线可用);
 *  2. 否则有网且拿到可用直链 → 串流;
 *  3. 否则不可播放(UI 据此灰化曲目行,而不是弹一个说不清的错误框)。
 *
 * 注意第 1 条只看 [state],不看文件是否真的存在 —— 文件被外部删掉时,
 * 打开 `content://` 会失败,由播放层把该曲置回 PENDING 并重试串流(自动降级)。
 */
fun decideTarget(
    localUri: String?,
    state: String,
    hasNetwork: Boolean,
    streamUrl: String?,
): ResolveTarget {
    if (!localUri.isNullOrEmpty() && state == SongState.OK.name) {
        return ResolveTarget.Local(localUri)
    }
    if (!hasNetwork) return ResolveTarget.Unavailable
    if (streamUrl.isNullOrEmpty()) return ResolveTarget.Unavailable
    return ResolveTarget.Stream(streamUrl)
}

/**
 * 把 `watchmusic://song/<id>` 在打开前解析成真实地址,交给上游 `DefaultDataSource`。
 *
 * 为什么在这一层做:seek / 重试 / 本地文件失效降级都会重新构造 DataSpec,
 * 放在这里只需写一遍判定;放在队列构造时做,则"下一首"和"重试"都得各写一遍。
 *
 * [resolveDataSpec] 运行在 ExoPlayer 的加载线程上,所以这里用 `runBlocking` 桥接
 * 挂起函数 —— 与参考实现同法。命中缓存时不产生任何挂起调用;未命中才会去查库/取地址。
 */
class LocalFirstResolver(
    private val source: PlaybackSource,
    private val hasNetwork: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : ResolvingDataSource.Resolver {

    private data class Cached(val target: ResolveTarget, val expiresAt: Long)

    private val cache = ConcurrentHashMap<Long, Cached>()

    /** 本地文件消失 / 直链过期(403、404)时调用,下次打开重新解析 */
    fun invalidate(songId: Long) {
        cache.remove(songId)
    }

    fun invalidateAll() {
        cache.clear()
    }

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val songId = PlaybackUri.songIdOf(dataSpec.uri.toString()) ?: return dataSpec
        return when (val target = resolve(songId)) {
            is ResolveTarget.Local -> dataSpec.withUri(Uri.parse(target.uri))
            is ResolveTarget.Stream -> dataSpec.withUri(Uri.parse(target.url))
            ResolveTarget.Unavailable -> {
                Diag.w(TAG, "歌曲不可播放 songId=$songId(本地无文件且取不到可用地址)")
                throw StreamUnavailableException("歌曲不可播放")
            }
        }
    }

    private fun resolve(songId: Long): ResolveTarget {
        val cached = cache[songId]
        if (cached != null && cached.expiresAt > clock()) return cached.target

        val target = runBlocking {
            val info = runCatching { source.localInfoOf(songId) }.getOrNull()
            val local = decideTarget(info?.localUri, info?.state.orEmpty(), true, null)
            if (local is ResolveTarget.Local) return@runBlocking local

            if (!hasNetwork()) return@runBlocking ResolveTarget.Unavailable
            val url = runCatching { source.streamUrlOf(songId) }
                .onFailure { Diag.w(TAG, "取串流地址失败 songId=$songId:${it.message}") }
                .getOrNull()
            if (url.isNullOrEmpty()) ResolveTarget.Unavailable else ResolveTarget.Stream(url)
        }

        // 不可用不缓存:可能只是暂时没网,下次打开应该再试一次
        if (target !is ResolveTarget.Unavailable) {
            val ttl = if (target is ResolveTarget.Stream) STREAM_TTL_MS else LOCAL_TTL_MS
            cache[songId] = Cached(target, clock() + ttl)
        }
        return target
    }

    private companion object {
        const val TAG = "LocalFirstResolver"

        /** 直链带签名且会过期,给一个保守的复用窗口,避免 seek 时反复取地址 */
        const val STREAM_TTL_MS = 5 * 60 * 1000L

        /** 本地文件路径可能被同步删除,缓存窗口取短一些 */
        const val LOCAL_TTL_MS = 60 * 1000L
    }
}
