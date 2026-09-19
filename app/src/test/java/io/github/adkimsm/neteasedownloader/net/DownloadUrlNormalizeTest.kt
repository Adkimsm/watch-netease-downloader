package io.github.adkimsm.neteasedownloader.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载直链规范化:网易云 standard 音质返回明文 http 长链,
 * Android 默认禁止明文流量,故改写成 https 后再交给 okhttp。
 * 关键约束:改写不得破坏 path/query(签名覆盖这两部分),
 * 且带非 443 端口的链接必须原样保留。
 */
class DownloadUrlNormalizeTest {

    @Test
    fun cleartext_mp3_long_url_becomes_https() {
        val http = "http://m701.music.126.net/2024092001/abc123/xyz.mp3?vuutv=1"
        assertEquals(
            "https://m701.music.126.net/2024092001/abc123/xyz.mp3?vuutv=1",
            normalizeDownloadUrl(http),
        )
    }

    @Test
    fun path_and_query_signature_are_preserved_byte_for_byte() {
        val http =
            "http://m801.music.126.net/aaaa/bbbb/cccc.mp3" +
                "?authSecret=deadbeef&vuutv=1234&bitrate=320"
        val got = normalizeDownloadUrl(http)
        val tail = got.substringAfter("m801.music.126.net")
        assertEquals(
            "/aaaa/bbbb/cccc.mp3?authSecret=deadbeef&vuutv=1234&bitrate=320",
            tail,
        )
    }

    @Test
    fun https_url_is_unchanged() {
        val https = "https://m701.music.126.net/2024/track.flac?vuutv=1"
        assertEquals(https, normalizeDownloadUrl(https))
    }

    @Test
    fun explicit_port_443_is_kept() {
        val http = "http://m701.music.126.net:443/path/song.mp3?x=1"
        assertEquals(
            "https://m701.music.126.net:443/path/song.mp3?x=1",
            normalizeDownloadUrl(http),
        )
    }

    @Test
    fun non_443_port_is_left_untouched() {
        // 非 443 端口很可能不是 TLS 服务,改写会把策略失败变成 404/403
        val url = "http://m701.music.126.net:1443/path/song.mp3?x=1"
        assertEquals(url, normalizeDownloadUrl(url))
    }

    @Test
    fun unparseable_url_falls_back_to_original() {
        val weird = "http://[::not a url/path"
        assertEquals(weird, normalizeDownloadUrl(weird))
    }

    @Test
    fun userinfo_and_fragment_survive() {
        val http = "http://user:pass@m701.music.126.net/a/b.mp3?x=1#frag"
        assertEquals(
            "https://user:pass@m701.music.126.net/a/b.mp3?x=1#frag",
            normalizeDownloadUrl(http),
        )
    }

    @Test
    fun isCleartextUrl_detects_scheme_case_insensitively() {
        assertTrue(isCleartextUrl("http://m701.music.126.net/a.mp3"))
        assertTrue(isCleartextUrl("HTTP://m701.music.126.net/a.mp3"))
        assertFalse(isCleartextUrl("https://m701.music.126.net/a.flac"))
    }

    @Test
    fun normalized_url_is_never_cleartext() {
        val samples = listOf(
            "http://m701.music.126.net/a.mp3?x=1",
            "HTTP://m702.music.126.net/b/c.mp3",
            "https://m703.music.126.net/d.flac",
        )
        samples.forEach { raw ->
            assertFalse(
                "规范化后不应仍是明文:$raw",
                isCleartextUrl(normalizeDownloadUrl(raw)),
            )
        }
    }
}
