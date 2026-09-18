package io.github.adkimsm.neteasedownloader.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.OutputStream

/** 流式下载工具:边写边算 md5,支持回调已写字节数 */
class Downloader(private val client: OkHttpClient) {
    data class Result(val bytes: Long, val md5: String?)

    suspend fun download(
        url: String,
        output: OutputStream,
        onProgress: (Long) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
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
