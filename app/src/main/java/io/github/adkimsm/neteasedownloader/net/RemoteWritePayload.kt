package io.github.adkimsm.neteasedownloader.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 远端写操作的请求体构造(纯函数,可单测)。
 *
 * 网易云这几个写接口有个共同的坑:**要把一段 JSON 当成字符串再塞进 JSON**。
 * `/api/playlist/manipulate/tracks` 的 `trackIds` 值是 `"[\"186016\",\"123\"]"`,
 * `/api/batch` 的值更是内嵌一整段带歌名的 JSON。手工拼字符串时,歌名里的引号、
 * 反斜杠、换行会把请求体彻底写坏 —— 所以这里一律走 kotlinx.serialization 生成,
 * 不做字符串拼接。
 *
 * 端点与取值对照 NeteaseCloudMusicApi 4.32.0 的 module 实现。
 * **通道说明**:这些端点目前一律走 eapi(实测 weapi 通道 2026-09 起对全部端点
 * 返回 HTTP 200 空 body,静默失败;eapi 通道正常)。红心列表(likelist.js)与
 * `/api/batch` 在参考实现里本就默认 eapi;加/删曲参考 playlist_tracks.js 走
 * `/api/playlist/manipulate/tracks`。
 */
object RemoteWritePayload {

    /** JSON 字符串字面量(含两侧引号,内部已转义) */
    private fun jsonString(value: String): String =
        NcmJson.encodeToString(kotlinx.serialization.serializer<String>(), value)

    /**
     * 歌单加/删曲共用体:`/api/playlist/manipulate/tracks`(参考 playlist_tracks.js)。
     *
     * ```json
     * {"op":"del","pid":123,"trackIds":"[\"186016\",\"123\"]","imme":"true"}
     * ```
     *
     * 注意 `trackIds` 是**字符串 id 的 JSON 字符串**(不是对象数组),`imme` 也是字符串。
     */
    fun manipulateTracks(op: String, playlistId: Long, songIds: List<Long>): String {
        // 手工给 separator,别让默认的 ", " 混进 JSON
        val ids = songIds.joinToString(separator = ",", prefix = "[", postfix = "]") { "\"$it\"" }
        return buildJsonObject {
            put("op", op)
            put("pid", playlistId)
            put("trackIds", ids)
            put("imme", "true")
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
     * eapi 写端点按参考实现把设备 header 内嵌进 payload(与 Cookie 头同源)。
     * 已有该字段时按传入值覆盖。
     */
    fun withDeviceHeader(payloadJson: String, header: Map<String, String>): String {
        if (header.isEmpty()) return payloadJson
        val obj = runCatching { NcmJson.parseToJsonElement(payloadJson).jsonObject }.getOrNull()
            ?: return payloadJson
        val headerObj = buildJsonObject {
            header.forEach { (k, v) -> put(k, v) }
        }
        return JsonObject(obj.toMutableMap().apply { put("header", headerObj) }).toString()
    }
    const val BATCH_RENAME_PATH = "/api/playlist/update/name"
}
