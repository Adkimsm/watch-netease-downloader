package io.github.adkimsm.neteasedownloader.net.unlock

import io.github.adkimsm.neteasedownloader.diag.Diag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder

/**
 * 酷狗音源。
 *
 * 走的两段接口(2026-09 实测仍可用):
 *  - 搜索 `songsearch.kugou.com/song_search_v2` 返回标准 JSON;
 *  - 取链 **不走** `trackercdn.kugou.com/i/v2/`(复测恒返回 `status:2` 无 url),
 *    改走 `m.kugou.com/app/i/getSongInfo.php`,返回 `url`/`fileSize`/`bitRate`。
 *
 * 已知怪癖:搜索会**偶发返回 200 + 空 lists**(限流或反爬),已经实测捕捉到 ——
 * 这属于"这个音源这轮没戏",按空候选处理即可(酷我排在前面,正好兜底)。
 */
class KugouProvider : SourceProvider {

    override val id = ProviderId.KUGOU

    override suspend fun candidates(http: ProviderHttp, query: SongQuery): List<SourceCandidate> {
        val body = http.getText(buildSearchUrl(query)) ?: return emptyList()
        return parseSearch(body)
    }

    override suspend fun track(http: ProviderHttp, candidate: SourceCandidate): TrackResult? {
        // 普通/hq 两个 hash 各试一次:首要 hash 失效时换备选
        var current = candidate
        repeat(2) {
            val body = http.getText(buildTrackUrl(current.id))
            val result = body?.let { parseTrack(it) }
            if (result != null) return result
            val alternate = current.alternateId ?: return null
            current = current.copy(id = alternate, alternateId = null)
        }
        return null
    }

    companion object {
        private const val TAG = "KugouProvider"

        /** 宽泛解析:第三方响应里 null/缺字段/类型漂移都很常见,一律按空值处理。 */
        private val TOLERANT_JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        fun buildSearchUrl(query: SongQuery): String =
            "https://songsearch.kugou.com/song_search_v2?keyword=${URLEncoder.encode(query.keyword, "UTF-8")}&page=1"

        fun buildTrackUrl(hash: String): String =
            "https://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=$hash"

        /** 解析搜索响应。畸形输入一律返回空列表,不抛异常。 */
        fun parseSearch(body: String): List<SourceCandidate> {
            val root = runCatching { TOLERANT_JSON.parseToJsonElement(body) }.getOrNull()
                as? JsonObject ?: return emptyList()
            val lists = (root["data"] as? JsonObject)?.get("lists") as? JsonArray
                ?: return emptyList()
            return lists.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val normal = item.text("FileHash")
                val hq = item.text("HQFileHash")
                val hqUsable = hq.isNotEmpty() &&
                    item.int("HQPrivilege") == 0 && item.long("HQFileSize") > 0
                val primary = if (hqUsable) hq else normal
                if (primary.isEmpty()) return@mapNotNull null
                val alternate = when {
                    primary == normal -> hq.takeIf { it.isNotEmpty() }
                    else -> normal.takeIf { it.isNotEmpty() }
                }
                SourceCandidate(
                    id = primary,
                    title = item.text("SongName"),
                    artist = item.text("SingerName"),
                    durationSec = item.int("Duration"),
                    alternateId = alternate,
                )
            }
        }

        /** 解析取链响应。status != 1 或拿不到 URL 就返回 null。 */
        fun parseTrack(body: String): TrackResult? {
            val root = runCatching { TOLERANT_JSON.parseToJsonElement(body) }.getOrNull()
                as? JsonObject ?: return null
            if (root.int("status") != 1) {
                Diag.w(TAG, "getSongInfo status=${root.int("status")} error=${root.text("error")}")
                return null
            }
            val url = root.text("url")
            if (!url.startsWith("http", ignoreCase = true)) return null
            val bitrateKbps = root.int("bitRate")
            return TrackResult(
                url = url,
                size = root.long("fileSize"),
                br = if (bitrateKbps > 0) bitrateKbps.toLong() * 1000L else 0L,
                type = root.text("extName").ifBlank { "mp3" },
            )
        }
    }
}

internal fun JsonObject.text(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

internal fun JsonObject.int(key: String): Int =
    (this[key] as? JsonPrimitive)?.intOrNull ?: 0

internal fun JsonObject.long(key: String): Long =
    (this[key] as? JsonPrimitive)?.longOrNull ?: 0L