package io.github.adkimsm.neteasedownloader.net.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 探活响应判定。
 *
 * 只看状态码不够:失效直链常常返回 200 + 一段 HTML 错误页,播起来就是一个
 * 立刻失败的"能播的歌"。所以类型/魔数也要一起看。
 */
class ProbeParserTest {

    /** 酷我直链实测的首字节:MPEG1 Layer3 / 128kbps */
    private val mp3Head = hex("fffb90c4000000000000000000000000")

    @Test
    fun contentRangeWins_overContentLength() {
        val result = parseProbe(
            206,
            mapOf(
                "content-range" to "bytes 0-8191/181521",
                "content-length" to "8192",
                "content-type" to "audio/mpeg",
            ),
            mp3Head,
        )
        assertNotNull(result)
        requireNotNull(result)
        assertEquals(181521L, result.totalBytes)
        assertEquals("audio/mpeg", result.contentType)
        assertEquals(206, result.status)
    }

    @Test
    fun contentLengthFallback() {
        val result = parseProbe(200, mapOf("content-length" to "1234", "content-type" to "audio/mpeg"), mp3Head)
        assertNotNull(result)
        assertEquals(1234L, requireNotNull(result).totalBytes)
    }

    @Test
    fun missingLength_yieldsZero() {
        val result = parseProbe(200, mapOf("content-type" to "audio/mpeg"), mp3Head)
        assertNotNull(result)
        assertEquals("拿不到长度时按 0 处理,上层据此跳过大小校验", 0L, requireNotNull(result).totalBytes)
    }

    @Test
    fun htmlErrorPage_isRejected() {
        assertNull(
            parseProbe(
                200,
                mapOf("content-type" to "text/html; charset=utf-8"),
                "<!doctype html><html>...".toByteArray(),
            ),
        )
    }

    @Test
    fun octetStreamWithMp3Magic_isAccepted() {
        val result = parseProbe(206, mapOf("content-type" to "application/octet-stream"), mp3Head)
        assertNotNull("有 MP3 帧同步头就认", result)
    }

    @Test
    fun noContentType_givesBenefitOfTheDoubt() {
        // 有些 CDN 不给 content-type:这时不该仅凭这一点判死
        assertNotNull(parseProbe(200, emptyMap(), mp3Head))
        assertNotNull(parseProbe(200, emptyMap(), ByteArray(16)))
    }

    @Test
    fun explicitNonAudioType_withoutMagic_isRejected() {
        assertNull(parseProbe(200, mapOf("content-type" to "application/octet-stream"), ByteArray(16)))
    }

    @Test
    fun nonSuccess_isRejected() {
        assertNull(parseProbe(403, mapOf("content-type" to "audio/mpeg"), mp3Head))
        assertNull(parseProbe(404, mapOf("content-type" to "audio/mpeg"), mp3Head))
        assertNull(parseProbe(500, mapOf("content-type" to "audio/mpeg"), mp3Head))
    }

    @Test
    fun flacMagic_isAccepted() {
        val head = "fLaC".toByteArray() + ByteArray(8)
        assertNotNull(parseProbe(200, mapOf("content-type" to "audio/flac"), head))
    }

    @Test
    fun serverMd5_isCaptured() {
        val result = parseProbe(
            206,
            mapOf("content-type" to "audio/mpeg", "server-md5" to "ABCDEF0123456789"),
            mp3Head,
        )
        assertEquals("ABCDEF0123456789", requireNotNull(result).serverMd5)
    }
}
