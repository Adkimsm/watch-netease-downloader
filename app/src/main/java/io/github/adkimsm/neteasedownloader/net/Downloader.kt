package io.github.adkimsm.neteasedownloader.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.OutputStream

import java.net.URI

/** 下载地址是否需要带凭证:网易云 mp3 长链走明文 http,m4a/flac 走 https */
fun isCleartextUrl(url: String): Boolean = url.startsWith("http://", ignoreCase = true)

/**
 * 把网易云下载长链规范成 okhttp 能直接请求的形式:
 *  - 长链里紧跟 host 的 `:443` 是冗余的,去掉后不影响签名;
 *  - 已带其它显式端口(如 :1443)的原样返回,绝不改写导致签名失效。
 */
fun normalizeDownloadUrl(url: String): String {
    val raw = url.trim()
    val uri = runCatching { URI(raw) }.getOrNull() ?: return raw
    val port = uri.port
    if (port != -1 && port != 443) return raw
    val host = uri.host ?: return raw
    return buildString {
        append("https://")
        uri.userInfo?.let { append(it).append('@') }
        append(host)
        if (port == 443) append(":443")
        append(uri.rawPath ?: "")
        uri.rawQuery?.let { append('?').append(it) }
        uri.rawFragment?.let { append('#').append(it) }
    }
}

/** 流式下载工具:边写边算 md5,支持回调已写字节数 */
class Downloader(private val client: OkHttpClient) {
    data class Result(val bytes: Long, val md5: String?)

    suspend fun download(
        url: String,
        output: OutputStream,
        onProgress: (Long) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(normalizeDownloadUrl(url)).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("空响应体")
            val digest = java.security.MessageDigest.getInstance("MD5")
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0L
            body.byteStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    total += read
                    onProgress(total)
                }
            }
            output.flush()
            Result(total, digest.digest().joinToString("") { "%02x".format(it) })
        }
    }

    companion object {
        private const val BUFFER_SIZE = 8 * 1024
    }
}
