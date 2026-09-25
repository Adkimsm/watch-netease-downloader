package io.github.adkimsm.neteasedownloader.net.unlock

import java.net.URLEncoder

/**
 * 酷我音源。
 *
 * 走的是老接口(2026-09 实测仍可用):
 *  - 搜索 `search.kuwo.cn/r.s` 返回的是**类 JSON 文本** —— 单引号、键不带引号,
 *    条目里还嵌着 `audiobookpayinfo:{...}` 这种嵌套对象。它既不是合法 JSON,
 *    也不能按 `},{` 硬切(会把嵌套对象切碎),必须做深度感知的顶层对象切分。
 *  - 取链 `antiserver.kuwo.cn/anti.s` 直接返回纯文本 URL,配合 `Range` 探活可用。
 *
 * 新的 `www.kuwo.cn/api/www/...` 需要 `kw_token`,实测返回
 * `The request is illegal!`,所以不用。
 */
class KuwoProvider : SourceProvider {

    override val id = ProviderId.KUWO

    override suspend fun candidates(http: ProviderHttp, query: SongQuery): List<SourceCandidate> {
        val body = http.getText(buildSearchUrl(query)) ?: return emptyList()
        return parseSearch(body)
    }

    override suspend fun track(http: ProviderHttp, candidate: SourceCandidate): TrackResult? {
        val body = http.getText(buildTrackUrl(candidate.id)) ?: return null
        val url = body.trim()
        if (!url.startsWith("http", ignoreCase = true)) return null
        // 酷我只给 URL,大小/码率交给探活与帧头解析补齐
        return TrackResult(url = url)
    }

    companion object {
        private const val TAG = "KuwoProvider"
        private const val ABSLIST_MARKER = "'abslist':"

        private val MUSIC_RID = Regex("""'MUSICRID':'MUSIC_(\d+)'""")
        private val HTML_ENTITIES = mapOf(
            "&nbsp;" to " ",
            "&amp;" to "&",
            "&quot;" to "\"",
            "&#39;" to "'",
            "&lt;" to "<",
            "&gt;" to ">",
        )

        fun buildSearchUrl(query: SongQuery): String {
            val name = URLEncoder.encode(query.name, "UTF-8")
            val artist = URLEncoder.encode(query.artist, "UTF-8")
            return "http://search.kuwo.cn/r.s?ft=music&rformat=json&encoding=utf8&rn=8" +
                "&vipver=MUSIC_8.0.3.1&SONGNAME=$name&ARTIST=$artist"
        }

        fun buildTrackUrl(rid: String): String =
            "http://antiserver.kuwo.cn/anti.s?type=convert_url&format=mp3&response=url&rid=MUSIC_$rid"

        /** 解析搜索响应。畸形输入一律返回空列表,不抛异常。 */
        fun parseSearch(body: String): List<SourceCandidate> {
            val marker = body.indexOf(ABSLIST_MARKER)
            if (marker < 0) return emptyList()
            val bracket = body.indexOf('[', marker)
            if (bracket < 0) return emptyList()

            return splitTopLevelObjects(body, bracket).mapNotNull { element ->
                val rid = MUSIC_RID.find(element)?.groupValues?.get(1) ?: return@mapNotNull null
                val title = field(element, "NAME")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SourceCandidate(
                    id = rid,
                    title = title,
                    artist = field(element, "ARTIST").orEmpty(),
                    durationSec = field(element, "DURATION")?.toIntOrNull() ?: 0,
                )
            }
        }

        private fun field(element: String, key: String): String? =
            Regex("""'$key':'((?:[^'\\]|\\.)*)'""")
                .find(element)
                ?.groupValues
                ?.get(1)
                ?.let(::unescape)

        private fun unescape(raw: String): String {
            var out = raw.replace("\\'", "'").replace("\\\\", "\\")
            HTML_ENTITIES.forEach { (entity, replacement) -> out = out.replace(entity, replacement) }
            return out
        }
    }
}

/**
 * 从 [bracketIndex] 处的 `[` 开始,切出其中的**顶层** `{...}` 元素。
 *
 * 深度感知是必须的:酷我条目里嵌着 `audiobookpayinfo:{...}`,
 * 按 `},{` 切会把一个条目切成好几块(单测 `handlesNestedObject` 钉住这一点)。
 * 单引号字符串内的括号不参与计数。
 */
internal fun splitTopLevelObjects(text: String, bracketIndex: Int): List<String> {
    val out = ArrayList<String>()
    var depth = 0
    var elementStart = -1
    var inString = false
    var index = bracketIndex + 1
    while (index < text.length) {
        val ch = text[index]
        when {
            inString -> if (ch == '\'') inString = false
            ch == '\'' -> inString = true
            ch == '{' -> {
                if (depth == 0) elementStart = index
                depth++
            }
            ch == '}' -> {
                depth--
                if (depth == 0 && elementStart >= 0) {
                    out += text.substring(elementStart, index + 1)
                    elementStart = -1
                }
            }
            ch == ']' && depth == 0 -> return out
        }
        index++
    }
    return out
}
