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

    /**
     * weapi 调用。
     *
     * 与 eapi 的差别:URL 走 music.163.com、表单多一个 encSecKey、
     * 请求体里要带 csrf_token,并且必须带 Referer。
     */
    suspend fun weapiRaw(uri: String, payloadJson: String): NcmResponse =
        withContext(Dispatchers.IO) {
            val withCsrf = RemoteWritePayload.withCsrfToken(payloadJson, cookieProvider.csrfToken())
            val form = NcmCrypto.weapi(withCsrf)
            val shortUri = uri.removePrefix("/api/")
            val request = Request.Builder()
                .url("$WEB_DOMAIN/weapi/$shortUri")
                .post(
                    FormBody.Builder()
                        .add("params", form.params)
                        .add("encSecKey", form.encSecKey)
                        .build(),
                )
                .header("Cookie", cookieProvider.cookieHeader())
                .header("Referer", WEB_DOMAIN)
                .header("User-Agent", WEB_UA)
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
                        "weapi $shortUri -> http=${resp.code} code=${result.code} " +
                            "耗时=${(System.nanoTime() - start) / 1_000_000}ms",
                    )
                    result
                }
            } catch (e: Exception) {
                Diag.w(
                    "NcmApi",
                    "weapi $shortUri 异常: ${e.message} " +
                        "耗时=${(System.nanoTime() - start) / 1_000_000}ms",
                )
                throw e
            }
        }

    suspend inline fun <reified Req> weapi(uri: String, req: Req): NcmResponse =
        weapiRaw(uri, NcmJson.encodeToString(req))

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
    // 这组端点在参考实现里一律走 weapi:eapi 对其中的写操作并非全部可用,
    // 而且写失败常常是静默的(返回 200 却不生效),照抄参考实现最稳。

    /** 我的红心歌曲 id 列表 */
    suspend fun fetchLikedSongIds(uid: Long): List<Long> {
        val resp = weapiRaw("/api/song/like/get", RemoteWritePayload.likedIds(uid))
        if (resp.code != 200) throw NcmApiException("红心列表返回 ${resp.code}")
        return resp.decode<LikedIdsResp>().ids
    }

    /** 新建歌单,返回新歌单 id */
    suspend fun createPlaylist(name: String): Long {
        val resp = weapiRaw("/api/playlist/create", RemoteWritePayload.playlistCreate(name))
        if (resp.code != 200) throw NcmApiException("新建歌单返回 ${resp.code}")
        val body = resp.body ?: throw NcmApiException("新建歌单响应为空")
        return body["id"]?.jsonPrimitive?.longOrNull
            ?: body["playlist"]?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull
            ?: throw NcmApiException("新建歌单未返回 id")
    }

    /** 删除歌单(只能删自己的) */
    suspend fun removePlaylists(ids: List<Long>) {
        if (ids.isEmpty()) return
        val resp = weapiRaw("/api/playlist/remove", RemoteWritePayload.playlistRemove(ids))
        if (resp.code != 200) throw NcmApiException("删除歌单返回 ${resp.code}")
    }

    /** 重命名歌单(仅本人歌单可用) */
    suspend fun renamePlaylist(playlistId: Long, name: String) {
        val resp = weapiRaw("/api/batch", RemoteWritePayload.batchRename(playlistId, name))
        if (resp.code != 200) throw NcmApiException("重命名歌单返回 ${resp.code}")
    }

    /** 把歌曲加入歌单 */
    suspend fun addTracksToPlaylist(playlistId: Long, songIds: List<Long>) {
        if (songIds.isEmpty()) return
        val resp = weapiRaw(
            "/api/playlist/track/add",
            RemoteWritePayload.playlistTrackOp(playlistId, songIds),
        )
        if (resp.code != 200) throw NcmApiException("加入歌单返回 ${resp.code}")
    }

    /** 从歌单移除歌曲(仅本人歌单可用) */
    suspend fun removeTracksFromPlaylist(playlistId: Long, songIds: List<Long>) {
        if (songIds.isEmpty()) return
        val resp = weapiRaw(
            "/api/playlist/track/delete",
            RemoteWritePayload.playlistTrackOp(playlistId, songIds),
        )
        if (resp.code != 200) throw NcmApiException("从歌单移除返回 ${resp.code}")
    }

    /** 红心 / 取消红心 */
    suspend fun setLiked(songId: Long, liked: Boolean) {
        val resp = weapiRaw("/api/radio/like", RemoteWritePayload.like(songId, liked))
        if (resp.code != 200) throw NcmApiException("红心操作返回 ${resp.code}")
    }

    companion object {
        private const val API_DOMAIN = "https://interface.music.163.com"
        private const val IPHONE_UA =
            "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)"
        private const val BATCH_SONG_DETAIL = 500
        private const val BATCH_SONG_URL = 50

        /** weapi 走主站域名;必须带 Referer,否则服务端会拒 */
        private const val WEB_DOMAIN = "https://music.163.com"
        private const val WEB_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0"
    }
}
