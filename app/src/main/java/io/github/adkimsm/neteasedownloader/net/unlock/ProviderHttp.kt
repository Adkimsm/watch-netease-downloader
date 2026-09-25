package io.github.adkimsm.neteasedownloader.net.unlock

import io.github.adkimsm.neteasedownloader.diag.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 直链探活结果。
 *
 * [head] 是响应体的前 [PROBE_BYTES] 字节,用来嗅探音频容器与 MP3 帧头码率 ——
 * 光看 HTTP 状态码不够:有些音源会对失效直链返回 200 + 一段 HTML 错误页。
 */
data class ProbeResult(
    val status: Int,
    val totalBytes: Long,
    val contentType: String?,
    val serverMd5: String?,
    val head: ByteArray,
)

/**
 * 第三方音源要用的最小网络面。接口化是为了让匹配逻辑能在单测里注入假实现
 * (离线跑,不打真实音源)。
 */
interface ProviderHttp {
    /** GET 文本;失败/超时返回 null */
    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String?

    /** `Range: bytes=0-8191` 探活;不可播返回 null */
    suspend fun probe(url: String): ProbeResult?
}

/**
 * 真实实现。
 *
 * UA 用桌面 Chrome:部分音源对空 UA / 爬虫 UA 直接拒绝。
 * 超时压到 5s —— 手表上"匹配不到"必须很快变成"跳过",而不是把同步拖住。
 */
class OkHttpProviderHttp(private val client: OkHttpClient) : ProviderHttp {

    override suspend fun getText(url: String, headers: Map<String, String>): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url).apply {
                    header("User-Agent", USER_AGENT)
                    headers.forEach { (name, value) -> header(name, value) }
                }.build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Diag.w(TAG, "GET ${url.take(120)} -> HTTP ${resp.code}")
                        return@use null
                    }
                    resp.body?.string()
                }
            }.onFailure { Diag.w(TAG, "GET ${url.take(120)} 异常:${it.message}") }
                .getOrNull()
        }

    override suspend fun probe(url: String): ProbeResult? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Range", "bytes=0-${PROBE_BYTES - 1}")
                .build()
            client.newCall(request).execute().use { resp ->
                val head = resp.body?.bytes() ?: ByteArray(0)
                val headers = resp.headers.toMultimap()
                    .mapValues { (_, values) -> values.firstOrNull().orEmpty() }
                parseProbe(resp.code, headers, head)
            }
        }.onFailure { Diag.w(TAG, "探活失败 ${url.take(120)}:${it.message}") }
            .getOrNull()
    }

    companion object {
        private const val TAG = "ProviderHttp"
        const val PROBE_BYTES = 8 * 1024
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}

/**
 * 判定一次探活是否"可播",并抽出总字节数。纯函数(单测见 ProbeParserTest)。
 *
 * - 非 2xx → 不可播;
 * - 类型不是音频(content-type 以 audio 开头)且头部没有音频魔数 → 不可播(挡掉 HTML 错误页);
 * - 总长优先取 `Content-Range` 的 `/` 之后(206 场景),退回 `Content-Length`;
 *   两者都没有就是 0,上层据此跳过大小校验。
 */
fun parseProbe(status: Int, headers: Map<String, String>, head: ByteArray): ProbeResult? {
    if (status !in 200..299) return null
    val contentType = headers["content-type"]
        ?.substringBefore(';')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    if (!looksLikeAudio(contentType, head)) return null
    val total = headers["content-range"]?.substringAfterLast('/')?.trim()?.toLongOrNull()
        ?: headers["content-length"]?.trim()?.toLongOrNull()
        ?: 0L
    return ProbeResult(
        status = status,
        totalBytes = total,
        contentType = contentType,
        serverMd5 = headers["server-md5"]?.trim()?.takeIf { it.isNotEmpty() },
        head = head,
    )
}

private fun looksLikeAudio(contentType: String?, head: ByteArray): Boolean {
    if (contentType != null && contentType.startsWith("audio", ignoreCase = true)) return true
    if (hasId3Magic(head) || hasFlacMagic(head) || hasMpegSync(head)) return true
    // 完全没有类型信息时给一次机会(靠上面的魔数把关);有类型且不是音频的一律拒绝
    return contentType == null
}

private fun hasId3Magic(head: ByteArray): Boolean =
    head.size >= 3 && head[0] == 'I'.code.toByte() &&
        head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()

private fun hasFlacMagic(head: ByteArray): Boolean =
    head.size >= 4 && head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() &&
        head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte()

private fun hasMpegSync(head: ByteArray): Boolean =
    head.size >= 2 && (head[0].toInt() and 0xFF) == 0xFF && ((head[1].toInt() shr 5) and 0x07) == 0x07

/**
 * 从 MP3 帧头读码率(bit/s);读不出来返回 null。纯函数(单测见 Mp3BitrateTest)。
 *
 * 移植参考实现 `provider/match.js` 的 `decode()`:跳过 ID3v2 标签后读帧头,
 * 按 (MPEG 版本, 层) 查表。酷我直链实测首字节是 `FF FB 90 C4`(MPEG1 Layer3 index 9)
 * → 128 kbps。
 */
fun mp3Bitrate(head: ByteArray): Long? {
    var pointer = 0
    if (hasId3Magic(head)) {
        if (head.size < 10) return null
        var size = 0
        for (index in 6..9) {
            size = (size shl 7) or (head[index].toInt() and 0x7F)
        }
        pointer = 10 + size
    }
    if (pointer + 4 > head.size) return null

    val b0 = head[pointer].toInt() and 0xFF
    val b1 = head[pointer + 1].toInt() and 0xFF
    val b2 = head[pointer + 2].toInt() and 0xFF
    if (b0 != 0xFF || ((b1 shr 5) and 0x07) != 0x07) return null

    val version = (b1 shr 3) and 0x03 // 0=MPEG2.5, 1=保留, 2=MPEG2, 3=MPEG1
    val layer = (b1 shr 1) and 0x03 // 0=保留, 1=Layer3, 2=Layer2, 3=Layer1
    val bitrateIndex = (b2 shr 4) and 0x0F
    if (version == 1 || layer == 0 || bitrateIndex == 0 || bitrateIndex == 0x0F) return null

    val kbps = when {
        version == 3 && layer == 3 -> MPEG1_LAYER1[bitrateIndex]
        version == 3 && layer == 2 -> MPEG1_LAYER2[bitrateIndex]
        version == 3 && layer == 1 -> MPEG1_LAYER3[bitrateIndex]
        layer == 3 -> MPEG2_LAYER1[bitrateIndex] // MPEG2 / MPEG2.5 Layer1
        else -> MPEG2_LAYER23[bitrateIndex] // MPEG2 / MPEG2.5 Layer2、Layer3
    }
    return if (kbps > 0) kbps * 1000L else null
}

// index 0 = free,15 = bad:两种都按"读不出来"处理,所以填 0
private val MPEG1_LAYER1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0)
private val MPEG1_LAYER2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0)
private val MPEG1_LAYER3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
private val MPEG2_LAYER1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, 0)
private val MPEG2_LAYER23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
