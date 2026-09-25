package io.github.adkimsm.neteasedownloader.net.unlock

/**
 * 一首歌匹配第三方音源所需的最小元数据。
 *
 * 刻意只依赖本地库已有的字段(歌名/歌手/时长),**不再额外打一次网易云接口** ——
 * 参考实现要调 `/api/song/detail` 取元数据,那是给"只有 id"的代理场景用的;
 * 本 App 手里本来就有这些字段。
 */
data class SongQuery(
    val songId: Long,
    val name: String,
    val artist: String,
    val durationMs: Long,
) {
    /** 音源搜索用的关键词:「歌名 歌手」 */
    val keyword: String
        get() = listOf(name, artist)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
}

/** 搜索得到的一个候选曲目。 */
data class SourceCandidate(
    /** 音源内部的曲目 id:酷我是 rid(纯数字),酷狗是 FileHash */
    val id: String,
    val title: String,
    val artist: String,
    /** 时长(秒);音源没给就是 0,打分时按"无证据"处理 */
    val durationSec: Int,
    /** 备选 id:酷狗在普通/HQ 两个 hash 之间互备 */
    val alternateId: String? = null,
)

/** 取到的直链 + 音源自报的元信息(可能缺失,由探活补齐)。 */
data class TrackResult(
    val url: String,
    val size: Long = 0L,
    val br: Long = 0L,
    val type: String = "mp3",
    val md5: String? = null,
)

/** 最终交给上层的一个可用音源。 */
data class MatchedSource(
    val providerId: String,
    val url: String,
    val size: Long,
    val br: Long,
    val md5: String?,
    val type: String,
)

/**
 * 第三方音源。
 *
 * 新增音源只需实现本接口并在 [ProviderId.ALL] 注册 —— 匹配器与解析器都不用动。
 * 搜索/取链的**解析**一律做成伴生对象里的纯函数,便于用真实响应 fixture 钉单测。
 */
interface SourceProvider {
    val id: String

    /** 搜索候选;失败返回空列表(调用方按"这个音源没戏"处理,不抛异常) */
    suspend fun candidates(http: ProviderHttp, query: SongQuery): List<SourceCandidate>

    /** 取直链;拿不到返回 null */
    suspend fun track(http: ProviderHttp, candidate: SourceCandidate): TrackResult?
}

object ProviderId {
    const val KUWO = "kuwo"
    const val KUGOU = "kugou"

    /** 顺序即优先级。酷我优先:实测酷我搜索比酷狗稳(酷狗会偶发返回空 lists)。 */
    val ALL = listOf(KUWO, KUGOU)
}
