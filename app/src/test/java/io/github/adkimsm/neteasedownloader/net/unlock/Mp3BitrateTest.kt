package io.github.adkimsm.neteasedownloader.net.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MP3 帧头码率解析。
 *
 * 关键样本是**真实酷我直链的首 16 字节** `FF FB 90 C4 ...`(MPEG1 Layer3,
 * bitrate index 9 → 128 kbps) —— 不是手搓的,直接来自抓包。
 */
class Mp3BitrateTest {

    @Test
    fun realKuwoHead_is128k() {
        assertEquals(128000L, mp3Bitrate(hex("fffb90c4000000000000000000000000")))
    }

    @Test
    fun mpeg1Layer3_320k() {
        // 0xE0:bitrate index 14 → 320kbps
        assertEquals(320000L, mp3Bitrate(hex("fffbe0c4")))
    }

    @Test
    fun mpeg1Layer2_192k() {
        // b1=0xFD:version=11(MPEG1)、layer=10(Layer2);b2=0xA0:index 10
        // Layer2 表 index 10 是 192,而 Layer3 表同一 index 是 160 —— 用错表会得到 160000
        assertEquals(192000L, mp3Bitrate(hex("fffda0c4")))
    }

    @Test
    fun id3v2Prefix_isSkipped() {
        // ID3v2.3,10 字节头,size 全 0 → 帧从 offset 10 开始
        val head = "ID3".toByteArray() + byteArrayOf(3, 0, 0, 0, 0, 0, 0) + hex("fffb90c4")
        assertEquals(128000L, mp3Bitrate(head))
    }

    @Test
    fun flacMagic_returnsNull() {
        assertNull(mp3Bitrate("fLaC".toByteArray() + ByteArray(8)))
    }

    @Test
    fun garbage_returnsNull() {
        assertNull(mp3Bitrate(ByteArray(16)))
        assertNull(mp3Bitrate(ByteArray(0)))
    }

    @Test
    fun reservedVersionOrLayer_returnsNull() {
        // b1=0xEB:version=01(保留)
        assertNull(mp3Bitrate(hex("ffeba000")))
        // b1=0xF9:layer=00(保留)
        assertNull(mp3Bitrate(hex("fff9a000")))
    }
}