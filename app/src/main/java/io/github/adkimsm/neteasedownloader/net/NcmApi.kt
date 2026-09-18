package io.github.adkimsm.neteasedownloader.net

import io.github.adkimsm.neteasedownloader.crypto.NcmCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    val body: JsonObject? = runCatching { Json.parseToJsonElement(rawBody).jsonObject }.getOrNull()

    /** 业务状态码,取自响应体 code,缺失时退回 httpCode */
    val code: Int
        get() = body?.get("code")?.jsonPrimitive?.intOrNull ?: httpCode
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

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    suspend inline fun <reified Req> eapi(uri: String, req: Req): NcmResponse =
        eapiRaw(uri, json.encodeToString(req))

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
            client.newCall(request).execute().use { resp ->
                val rawBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw NcmApiException("HTTP ${resp.code}: ${rawBody.take(200)}")
                }
                NcmResponse(resp.code, rawBody, resp.headers("Set-Cookie"))
            }
        }

    /** 扫码登录:申请 unikey */
    suspend fun createQrcodeUnikey(): String {
        val resp = eapi("/api/login/qrcode/unikey", QrcodeUnikeyReq())
        if (resp.code != 200) throw NcmApiException("unikey 接口返回 ${resp.code}")
        return json.decodeFromString(UnikeyResp.serializer(), resp.rawBody).unikey
            .ifEmpty { throw NcmApiException("unikey 为空") }
    }

    /** 扫码登录:轮询状态 */
    suspend fun checkQrcodeLogin(key: String): QrcodeCheckResp {
        val resp = eapi("/api/login/qrcode/client/login", QrcodeCheckReq(key))
        return json.decodeFromString(QrcodeCheckResp.serializer(), resp.rawBody)
    }

    /** 取当前登录账号信息;未登录返回 null */
    suspend fun fetchAccount(): NcmAccount? {
        val resp = eapiEmpty("/api/w/nuser/account/get")
        if (resp.code != 200) return null
        val account = resp.body?.get("account") ?: return null
        return json.decodeFromJsonElement(NcmAccount.serializer(), account)
    }

    /** 从 Set-Cookie 中提取 MUSIC_U */
    fun extractMusicU(cookies: List<String>): String? =
        cookies.firstNotNullOfOrNull { cookie ->
            cookie.substringBefore(";").split("=", limit = 2)
                .takeIf { it.size == 2 && it[0].trim() == "MUSIC_U" }
                ?.get(1)
        }

    companion object {
        private const val API_DOMAIN = "https://interface.music.163.com"
        private const val IPHONE_UA =
            "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)"
    }
}
