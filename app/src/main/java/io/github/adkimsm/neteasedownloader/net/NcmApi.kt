package io.github.adkimsm.neteasedownloader.net

import io.github.adkimsm.neteasedownloader.crypto.NcmCrypto
import io.github.adkimsm.neteasedownloader.diag.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class NcmApiException(message: String) : RuntimeException(message)

/** 单次 eapi 响应的封装 */
class NcmResponse(
    val httpCode: Int,
    val rawBody: String,
    val setCookies: List<String>,
) {
    val body: JsonObject? = runCatching { NcmJson.parseToJsonElement(rawBody).jsonObject }.getOrNull()
        ?: run {
            if (rawBody.isEmpty()) {
                Diag.w("NcmApi", "响应为空(HTTP $httpCode 空 body)")
            } else {
                Diag.w("NcmApi", "响应非JSON,len=${rawBody.length},前120字=${rawBody.take(120)}")
            }
            null
        }

    /** 业务状态码,取自响应体 code,缺失时退回 httpCode */
    val code: Int
        get() = body?.get("code")?.jsonPrimitive?.intOrNull ?: httpCode

    inline fun <reified T> decode(): T = NcmJson.decodeFromString(rawBody)
}

/**
 * 网易云音乐 eapi 直连客户端。对照 NeteaseCloudMusicApi 的请求层:
 *  - URL: https://interface.music.163.com/eapi/<去掉 /api/ 前缀的 uri>
 *  - 表单仅含 params(hex)
 *  - Cookie 头按客户端样式构造(osver/deviceId/os/appver/__csrf/MUSIC_U...)
 *
 * 读操作与远端写操作(歌单管理/红心)一律走 eapi:2026-09 实测 weapi 通道
 * (music.163.com/weapi 路径)对全部端点返回 HTTP 200 空 body,静默失败。
 */
class NcmApi(private val cookieProvider: CookieProvider) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend inline fun <reified Req> eapi(uri: String, req: Req): NcmResponse =
        eapiRaw(uri, NcmJson.encodeToString(req))

    /** 不带请求体的 eapi 调用(如账号信息接口) */
    suspend fun eapiEmpty(uri: String): NcmResponse = eapiRaw(uri, "{}")

    suspend fun eapiRaw(uri: String, payloadJson: String): NcmResponse =
        withContext(Dispatchers.IO) {
            val form = NcmCrypto.eapi(uri, payloadJson)
            val request = Request.Builder()
                .url("$API_DOMAIN/eapi/${uri.removePrefix("/api/")}")
                .post(FormBody.Builder().add("params", form.params).build())
                .header("Cookie", cookieProvider.cookieHeader())
                .header("User-Agent", IPHONE_UA)
                .build()
            val start = System.nanoTime()
            try {
                client.newCall(request).execute().use { resp ->
                    val rawBody = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw NcmApiException("HTTP ${resp.code}: ${rawBody.take(200)}")
                    }
                    val result = NcmResponse(resp.code, rawBody, resp.headers("Set-Cookie"))
                    Diag.i(
                        "NcmApi",
                        "${uri.removePrefix("/api/")} -> http=${resp.code} code=${result.code} " +
                            "耗时=${(System.nanoTime() - start) / 1_000_000}ms",
                    )
                    result
                }
            } catch (e: Exception) {
                Diag.w(
                    "NcmApi",
                    "${uri.removePrefix("/api/")} 异常: ${e.message} " +
                        "耗时=${(System.nanoTime() - start) / 1_000_000}ms",
                )
                throw e
            }
        }

    /** 扫码登录:申请 unikey */
    suspend fun createQrcodeUnikey(): String {
        val resp = eapi("/api/login/qrcode/unikey", QrcodeUnikeyReq())
        if (resp.code != 200) throw NcmApiException("unikey 接口返回 ${resp.code}")
        return resp.decode<UnikeyResp>().unikey
            .ifEmpty { throw NcmApiException("unikey 为空") }
    }

    /** 扫码登录:轮询状态,返回原始响应(调用方自行 decode + 取 cookies) */
    suspend fun checkQrcodeLogin(key: String): NcmResponse =
        eapi("/api/login/qrcode/client/login", QrcodeCheckReq(key))

    /** 取当前登录账号信息;未登录返回 null */
    suspend fun fetchAccount(): NcmAccount? {
        val resp = eapiEmpty("/api/w/nuser/account/get")
        if (resp.code != 200) return null
        val account = resp.body?.get("account") ?: return null
        return NcmJson.decodeFromJsonElement(NcmAccount.serializer(), account)
    }

    /** 从 Set-Cookie 中提取 MUSIC_U */
    fun extractMusicU(cookies: List<String>): String? =
        cookies.firstNotNullOfOrNull { cookie ->
            cookie.substringBefore(";").split("=", limit = 2)
                .takeIf { it.size == 2 && it[0].trim() == "MUSIC_U" }
                ?.get(1)
        }

    // ---------- 歌单与歌曲 ----------

    /** 用户歌单列表 */
    suspend fun fetchUserPlaylists(uid: Long): List<PlaylistDto> {
        val resp = eapi("/api/user/playlist", UserPlaylistReq(uid))
        if (resp.code != 200) throw NcmApiException("歌单列表返回 ${resp.code}")
        return resp.decode<UserPlaylistResp>().playlist
    }

    /** 歌单全部曲目 id(v6 详情接口一次性返回 trackIds) */
    suspend fun fetchPlaylistTrackIds(playlistId: Long): PlaylistDetailDto {
        val resp = eapi("/api/v6/playlist/detail", PlaylistDetailReq(playlistId))
        if (resp.code != 200) throw NcmApiException("歌单详情返回 ${resp.code}")
        return resp.decode<PlaylistDetailResp>().playlist
            ?: throw NcmApiException("歌单详情为空")
    }

    /** 批量取歌曲详情(每批 [BATCH_SONG_DETAIL] 首),返回 SongDto 列表 */
    suspend fun fetchSongDetails(ids: List<Long>): List<SongDto> {
        val results = mutableListOf<SongDto>()
        for (chunk in ids.chunked(BATCH_SONG_DETAIL)) {
            val c = chunk.joinToString(prefix = "[", postfix = "]") { """{"id":$it}""" }
            val resp = eapi("/api/v3/song/detail", SongDetailReq(c))
            if (resp.code != 200) throw NcmApiException("歌曲详情返回 ${resp.code}")
            results += resp.decode<SongDetailResp>().songs
        }
        return results
    }

    /** 批量取下载地址(每批 [BATCH_SONG_URL] 首) */
    suspend fun fetchSongUrls(ids: List<Long>, level: String): List<SongUrlDto> {
        val results = mutableListOf<SongUrlDto>()
        for (chunk in ids.chunked(BATCH_SONG_URL)) {
            val idsParam = chunk.joinToString(prefix = "[", postfix = "]", transform = { it.toString() })
            val resp = eapi("/api/song/enhance/player/url/v1", SongUrlReq(idsParam, level))
            if (resp.code != 200) throw NcmApiException("下载地址返回 ${resp.code}")
            results += resp.decode<SongUrlResp>().data
        }
        return results
    }

    // ---------- 远端写操作(歌单管理与红心) ----------
    // 这组端点照参考实现 4.32.0 走 eapi:红心列表(likelist.js)与 /api/batch 本就
    // 默认 eapi;加/删曲参考 playlist_tracks.js 走 /api/playlist/manipulate/tracks。
    // 不要切回 weapi:2026-09 实测 music.163.com/weapi/* 对全部端点返回 HTTP 200
    // 空 body(静默失败,日志表现为 code=200 却不生效),eapi 通道正常。

    /** eapi 写调用:按参考实现把设备 header 内嵌进 payload(与 Cookie 头同源) */
    private suspend fun eapiWriteRaw(uri: String, payloadJson: String): NcmResponse =
        eapiRaw(uri, RemoteWritePayload.withDeviceHeader(payloadJson, cookieProvider.deviceHeader()))

    /** 我的红心歌曲 id 列表(eapi,参考 likelist.js) */
    suspend fun fetchLikedSongIds(uid: Long): List<Long> {
        val resp = eapiWriteRaw("/api/song/like/get", RemoteWritePayload.likedIds(uid))
        if (resp.code != 200) throw NcmApiException("红心列表返回 ${resp.code}")
        if (resp.body == null) throw NcmApiException("红心列表响应为空(HTTP 200 空 body)")
        return resp.decode<LikedIdsResp>().ids
    }

    /** 新建歌单,返回新歌单 id */
    suspend fun createPlaylist(name: String): Long {
        val resp = eapiWriteRaw("/api/playlist/create", RemoteWritePayload.playlistCreate(name))
        if (resp.code != 200) throw NcmApiException("新建歌单返回 ${resp.code}")
        val body = resp.body ?: throw NcmApiException("新建歌单响应为空")
        return body["id"]?.jsonPrimitive?.longOrNull
            ?: body["playlist"]?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull
            ?: throw NcmApiException("新建歌单未返回 id")
    }

    /** 删除歌单(只能删自己的) */
    suspend fun removePlaylists(ids: List<Long>) {
        if (ids.isEmpty()) return
        val resp = eapiWriteRaw("/api/playlist/remove", RemoteWritePayload.playlistRemove(ids))
        if (resp.code != 200) throw NcmApiException("删除歌单返回 ${resp.code}")
    }

    /** 重命名歌单(仅本人歌单可用) */
    suspend fun renamePlaylist(playlistId: Long, name: String) {
        val resp = eapiWriteRaw("/api/batch", RemoteWritePayload.batchRename(playlistId, name))
        if (resp.code != 200) throw NcmApiException("重命名歌单返回 ${resp.code}")
    }

    /** 歌单加/删曲:eapi manipulate/tracks,按参考实现处理 code=512 重试 */
    private suspend fun manipulateTracks(op: String, playlistId: Long, songIds: List<Long>) {
        if (songIds.isEmpty()) return
        val resp = eapiWriteRaw(
            "/api/playlist/manipulate/tracks",
            RemoteWritePayload.manipulateTracks(op, playlistId, songIds),
        )
        if (resp.code == 512) {
            // 新歌单/操作频繁时服务端返回 512:重试一次且 trackIds 翻倍(参考实现怪癖)
            val retry = eapiWriteRaw(
                "/api/playlist/manipulate/tracks",
                RemoteWritePayload.manipulateTracks(op, playlistId, songIds + songIds),
            )
            if (retry.code != 200) throw NcmApiException("歌单操作返回 ${retry.code}")
            return
        }
        if (resp.code != 200) throw NcmApiException("歌单操作返回 ${resp.code}")
    }

    /** 把歌曲加入歌单 */
    suspend fun addTracksToPlaylist(playlistId: Long, songIds: List<Long>) {
        manipulateTracks("add", playlistId, songIds)
    }

    /** 从歌单移除歌曲(仅本人歌单可用) */
    suspend fun removeTracksFromPlaylist(playlistId: Long, songIds: List<Long>) {
        manipulateTracks("del", playlistId, songIds)
    }

    /** 红心 / 取消红心 */
    suspend fun setLiked(songId: Long, liked: Boolean) {
        val resp = eapiWriteRaw("/api/radio/like", RemoteWritePayload.like(songId, liked))
        if (resp.code != 200) throw NcmApiException("红心操作返回 ${resp.code}")
    }
    companion object {
        private const val API_DOMAIN = "https://interface.music.163.com"
        private const val IPHONE_UA =
            "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)"
        private const val BATCH_SONG_DETAIL = 500
        private const val BATCH_SONG_URL = 50

    }
}
