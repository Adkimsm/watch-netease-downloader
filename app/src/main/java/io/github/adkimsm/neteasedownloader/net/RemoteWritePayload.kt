package io.github.adkimsm.neteasedownloader.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 远端写操作的请求体构造(纯函数,可单测)。
 *
 * 网易云这几个写接口有个共同的坑:**要把一段 JSON 当成字符串再塞进 JSON**。
 * `tracks` 的值是 `"[{\"type\":3,\"id\":456}]"`,`/api/batch` 的值更是内嵌一整段
 * 带歌名的 JSON。手工拼字符串时,歌名里的引号、反斜杠、换行会把请求体彻底写坏 ——
 * 所以这里一律走 kotlinx.serialization 生成,不做字符串拼接。
 *
 * 端点与取值对照 NeteaseCloudMusicApi 4.32.0 的 module 实现。
 */
object RemoteWritePayload {

    /** JSON 字符串字面量(含两侧引号,内部已转义) */
    private fun jsonString(value: String): String =
        NcmJson.encodeToString(kotlinx.serialization.serializer<String>(), value)

    /**
     * 歌单曲目增删共用体:`/api/playlist/track/add` 与 `/api/playlist/track/delete`。
     *
     * ```json
     * {"id":123,"tracks":"[{\"type\":3,\"id\":456}]"}
     * ```
     */
    fun playlistTrackOp(playlistId: Long, songIds: List<Long>): String {
        // 同上:手工给 separator,别让默认的 ", " 混进 JSON
        val refs = songIds.joinToString(separator = ",", prefix = "[", postfix = "]") {
            """{"type":3,"id":$it}"""
        }
        return buildJsonObject {
            put("id", playlistId)
            put("tracks", refs)
        }.toString()
    }

    /** 删除歌单:`/api/playlist/remove`,注意是 ids 数组的**字符串** */
    fun playlistRemove(playlistIds: List<Long>): String {
        // 分隔符必须是逗号本身:joinToString 默认的 ", " 会在 JSON 里插入空格,
        // 与参考实现不一致(服务端宽容,但没有理由去赌)
        val ids = playlistIds.joinToString(separator = ",", prefix = "[", postfix = "]")
        return buildJsonObject { put("ids", ids) }.toString()
    }

    /** 新建歌单:`/api/playlist/create` */
    fun playlistCreate(name: String): String = buildJsonObject {
        put("name", name)
        put("privacy", "0")
        put("type", "NORMAL")
    }.toString()

    /**
     * 重命名歌单:`/api/batch`,子请求名就是端点路径。
     *
     * ```json
     * {"/api/playlist/update/name":"{\"id\":123,\"name\":\"歌名\"}"}
     * ```
     */
    fun batchRename(playlistId: Long, name: String): String {
        val inner = """{"id":$playlistId,"name":${jsonString(name)}}"""
        return buildJsonObject { put(BATCH_RENAME_PATH, inner) }.toString()
    }

    /** 红心 / 取消红心:`/api/radio/like` */
    fun like(songId: Long, liked: Boolean): String = buildJsonObject {
        put("alg", "itembased")
        put("trackId", songId)
        put("like", liked)
        put("time", "3")
    }.toString()

    /** 我的红心 id 列表:`/api/song/like/get` */
    fun likedIds(uid: Long): String = buildJsonObject { put("uid", uid) }.toString()

    /**
     * weapi 要求把 `__csrf` 同时放进请求体(不像 eapi 只放在 Cookie 头里)。
     * 已有该字段时按传入值覆盖,避免出现两个 csrf_token。
     */
    fun withCsrfToken(payloadJson: String, csrf: String): String {
        val obj = runCatching { NcmJson.parseToJsonElement(payloadJson).jsonObject }.getOrNull()
            ?: return payloadJson
        return JsonObject(obj.toMutableMap().apply { put("csrf_token", JsonPrimitive(csrf)) }).toString()
    }

    const val BATCH_RENAME_PATH = "/api/playlist/update/name"
}
