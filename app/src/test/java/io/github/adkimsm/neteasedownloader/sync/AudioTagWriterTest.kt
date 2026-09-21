package io.github.adkimsm.neteasedownloader.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * AudioTagWriter 的回归测试。重点盯几处字节级细节:
 *  - v2.3 帧长是大端原值、v2.4 是 syncsafe;混用会把帧切开(保留的帧是原样搬的,版本必须跟着原文件走)
 *  - 中文/emoji 必须走 UTF-16 + BOM
 *  - FLAC 块头长度是大端,而 VORBIS_COMMENT 内部长度是小端
 *  - 只替换歌名/歌手,其它标签(专辑/封面)与音频正文必须逐字节保留
 *  - 畸形或不支持的输入必须整体放弃(抛异常),而不是写出损坏文件
 *
 * 断言用的解析器都是独立实现的,故意不复用被测代码,避免自证。
 */
class AudioTagWriterTest {

    // ==================== MP3 ====================

    @Test
    fun mp3WithoutTag_getsTitleAndArtist() {
        val audio = fakeAudio()
        val result = retag("mp3", "晴天", "周杰伦", audio)

        val (frames, tagEnd) = parseId3(result)
        assertEquals("晴天", decodeText(frames.getValue("TIT2")))
        assertEquals("周杰伦", decodeText(frames.getValue("TPE1")))
        assertArrayEquals("音频正文必须原样保留", audio, result.copyOfRange(tagEnd, result.size))
        assertEquals("无原标签时新建 v2.3", 3, result[3].toInt())
    }

    @Test
    fun mp3NullTypeFallbackNotApplicable_uppercaseExtensionWorks() {
        val result = retag("MP3", "晴天", "周杰伦", fakeAudio())
        val (frames, _) = parseId3(result)
        assertEquals("晴天", decodeText(frames.getValue("TIT2")))
    }

    @Test
    fun mp3WithV23Tag_replacesOnlyTitleAndArtist() {
        val album = textPayload("范特西")
        val original = id3Tag(
            listOf(
                id3Frame("TIT2", textPayload("旧歌名")),
                id3Frame("TPE1", textPayload("旧歌手")),
                id3Frame("TALB", album),
            ),
        ) + fakeAudio()

        val result = retag("mp3", "晴天", "周杰伦", original)
        val (frames, tagEnd) = parseId3(result)

        assertEquals("晴天", decodeText(frames.getValue("TIT2")))
        assertEquals("周杰伦", decodeText(frames.getValue("TPE1")))
        assertArrayEquals("专辑帧必须原样保留", album, frames.getValue("TALB"))
        assertArrayEquals("音频正文必须原样保留", fakeAudio(), result.copyOfRange(tagEnd, result.size))
        assertEquals("重建后仍是 v2.3", 3, result[3].toInt())
    }

    @Test
    fun mp3WithV24Tag_parsesSyncsafeFrameSizesAndKeepsVersion() {
        // 载荷 > 127 字节时,大端原值与 syncsafe 的编码才真正不同,能验出用错编码
        val album = textPayload("专".repeat(120), encoding = 3)
        assertTrue("fixture 需要超过 127 字节以区分两种编码", album.size > 127)
        val original = id3Tag(
            listOf(
                id3Frame("TIT2", textPayload("旧歌名"), major = 4),
                id3Frame("TALB", album, major = 4),
            ),
            major = 4,
        ) + fakeAudio()

        val result = retag("mp3", "晴天", "周杰伦", original)
        val (frames, tagEnd) = parseId3(result)

        assertEquals("版本必须跟原标签一致,否则保留帧的帧长会被读错", 4, result[3].toInt())
        assertEquals("晴天", decodeText(frames.getValue("TIT2")))
        assertArrayEquals("v2.4 的专辑帧必须原样保留", album, frames.getValue("TALB"))
        assertArrayEquals(fakeAudio(), result.copyOfRange(tagEnd, result.size))
    }

    @Test
    fun tagSizeUsesSevenBitsPerByte() {
        val audio = fakeAudio()
        val result = retag("mp3", "歌".repeat(100), "手".repeat(100), audio)

        (6..9).forEach { assertTrue("header size 第 $it 字节必须是 syncsafe", result[it] < 0x80) }
        val declaredTagSize = syncSafeAt(result, 6)
        assertEquals("声明的标签体长度必须等于实际长度", result.size - 10 - audio.size, declaredTagSize)
        // 同一份数据按大端原值读会得到不同的值,说明确实用了 syncsafe
        assertTrue("长度应超过 127 字节以便区分编码", declaredTagSize > 127)
    }

    @Test
    fun probeMp3_reportsExistingValues() {
        val original = id3Tag(
            listOf(
                id3Frame("TIT2", textPayload("晴天")),
                id3Frame("TPE1", textPayload("周杰伦")),
            ),
        ) + fakeAudio()

        val existing = AudioTagWriter.probe("mp3", ByteArrayInputStream(original))
        assertEquals("晴天", existing?.title)
        assertEquals("周杰伦", existing?.artist)
    }

    @Test
    fun probeMp3_withoutTagReturnsEmptyTag() {
        val existing = AudioTagWriter.probe("mp3", ByteArrayInputStream(fakeAudio()))
        assertEquals(null, existing?.title)
        assertEquals(null, existing?.artist)
    }

    @Test
    fun probeMp3_decodesIso8859AndUtf8Frames() {
        val latin = id3Tag(listOf(id3Frame("TIT2", textPayload("Sunny", encoding = 0))))
        assertEquals("Sunny", AudioTagWriter.probe("mp3", ByteArrayInputStream(latin))?.title)

        val utf8 = id3Tag(listOf(id3Frame("TIT2", textPayload("晴天", encoding = 3), major = 4)), major = 4)
        assertEquals("晴天", AudioTagWriter.probe("mp3", ByteArrayInputStream(utf8))?.title)
    }

    @Test
    fun mp3WithUnsynchronisationFlag_isLeftAlone() {
        // flags 位 0x80 = unsynchronisation,帧布局被改写,原样搬移会出错
        val tagged = id3Tag(listOf(id3Frame("TIT2", textPayload("晴天"))), flags = 0x80) + fakeAudio()

        assertNull("unsync 标签无法安全解析", AudioTagWriter.probe("mp3", ByteArrayInputStream(tagged)))
        expectThrows<AudioTagUnsupportedException> { retag("mp3", "新", "新", tagged) }
    }

    @Test
    fun mp3WithV22Tag_isLeftAlone() {
        val tagged = id3Tag(listOf(id3Frame("TIT2", textPayload("晴天"))), major = 2) + fakeAudio()
        assertNull(AudioTagWriter.probe("mp3", ByteArrayInputStream(tagged)))
        expectThrows<AudioTagUnsupportedException> { retag("mp3", "新", "新", tagged) }
    }

    @Test
    fun mp3WithCompressedFrame_isLeftAlone() {
        // 帧格式标志低字节置 0x80 = 压缩,载荷语义未知
        val tagged = id3Tag(listOf(id3Frame("TIT2", textPayload("晴天"), flags = 0x0080))) + fakeAudio()
        assertNull(AudioTagWriter.probe("mp3", ByteArrayInputStream(tagged)))
        expectThrows<AudioTagUnsupportedException> { retag("mp3", "新", "新", tagged) }
    }

    @Test
    fun mp3WithOverlongTagSize_isLeftAlone() {
        // 声明 8MB+ 的标签体:必须直接拒绝,不能按声明长度分配内存
        val header = "ID3".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(3, 0, 0) +
            syncSafe(8 * 1024 * 1024 + 1)
        assertNull(AudioTagWriter.probe("mp3", ByteArrayInputStream(header)))
        expectThrows<AudioTagUnsupportedException> { retag("mp3", "新", "新", header) }
    }

    @Test
    fun mp3TinyFile_isStillTagged() {
        // 不足 10 字节的残缺文件也要能前置标签,且原始字节不丢
        val tiny = byteArrayOf(0x01, 0x02, 0x03)
        val result = retag("mp3", "晴天", "周杰伦", tiny)
        val (frames, tagEnd) = parseId3(result)
        assertEquals("晴天", decodeText(frames.getValue("TIT2")))
        assertEquals(3, result.size - tagEnd)
    }

    // ==================== FLAC ====================

    @Test
    fun flacWithoutComment_getsCommentAndKeepsStreamInfoAndAudio() {
        val info = streamInfo()
        val audio = fakeAudio()
        val original = flacFile(listOf(STREAMINFO to info), audio)

        val result = retag("flac", "晴天", "周杰伦", original)
        val (blocks, audioStart) = parseFlac(result)

        assertEquals("STREAMINFO 必须仍是第一块且逐字节不变", 0, blocks[0].first)
        assertArrayEquals(info, blocks[0].second)
        val (_, fields) = vorbisComment(blocks[1].second)
        assertEquals("晴天", fieldOf(fields, "TITLE"))
        assertEquals("周杰伦", fieldOf(fields, "ARTIST"))
        assertArrayEquals("音频帧必须原样保留", audio, result.copyOfRange(audioStart, result.size))
        assertTrue("最后一块要置 last 标志", (result[4 + 4 + info.size].toInt() and 0x80) != 0)
    }

    @Test
    fun flacWithExistingComment_preservesOtherFieldsAndKeepsSingleBlock() {
        val vendor = "NeteaseCloudMusic"
        val info = streamInfo()
        val original = flacFile(
            listOf(
                STREAMINFO to info,
                COMMENT to vorbisComment(vendor, listOf("TITLE" to "旧歌名", "ALBUM" to "范特西", "TRACKNUMBER" to "3")),
            ),
            fakeAudio(),
        )

        val result = retag("flac", "晴天", "周杰伦", original)
        val (blocks, _) = parseFlac(result)

        assertEquals("comment 块应只有一个", 1, blocks.count { it.first == COMMENT })
        val (resultVendor, fields) = vorbisComment(blocks.first { it.first == COMMENT }.second)
        assertEquals("vendor 必须保留", vendor, resultVendor)
        assertEquals("晴天", fieldOf(fields, "TITLE"))
        assertEquals("周杰伦", fieldOf(fields, "ARTIST"))
        assertEquals("其它字段必须保留", "范特西", fieldOf(fields, "ALBUM"))
        assertEquals("其它字段必须保留", "3", fieldOf(fields, "TRACKNUMBER"))
        assertEquals("TITLE 不能重复", 1, fields.count { it.first.equals("TITLE", true) })
    }

    @Test
    fun flacWithPicture_keepsPictureBlock() {
        val picture = ByteArray(64) { (it * 3).toByte() }
        val original = flacFile(
            listOf(
                STREAMINFO to streamInfo(),
                PICTURE to picture,
                COMMENT to vorbisComment("v", listOf("TITLE" to "旧")),
            ),
            fakeAudio(),
        )

        val result = retag("flac", "晴天", "周杰伦", original)
        val (blocks, audioStart) = parseFlac(result)

        assertEquals("STREAMINFO 第一", 0, blocks[0].first)
        assertEquals("封面块必须保留", 1, blocks.count { it.first == PICTURE })
        assertArrayEquals(picture, blocks.first { it.first == PICTURE }.second)
        assertEquals("comment 应紧跟 STREAMINFO", COMMENT, blocks[1].first)
        assertArrayEquals(fakeAudio(), result.copyOfRange(audioStart, result.size))
    }

    @Test
    fun flacVorbisLengthsAreLittleEndian() {
        val result = retag("flac", "晴天", "周杰伦", flacFile(listOf(STREAMINFO to streamInfo()), fakeAudio()))
        val (blocks, _) = parseFlac(result)
        val data = blocks.first { it.first == COMMENT }.second

        // vendor 长度字段按小端写:长度 10 -> 0A 00 00 00
        assertEquals("vendor 长度低字节", DEFAULT_VENDOR.length, data[0].toInt())
        assertEquals(0, data[1].toInt())
        assertEquals(0, data[2].toInt())
        assertEquals(0, data[3].toInt())
        assertEquals(
            DEFAULT_VENDOR,
            String(data, 4, DEFAULT_VENDOR.length, Charsets.UTF_8),
        )
    }

    @Test
    fun probeFlac_reportsExistingValues() {
        val original = flacFile(
            listOf(
                STREAMINFO to streamInfo(),
                COMMENT to vorbisComment("v", listOf("TITLE" to "晴天", "ARTIST" to "周杰伦")),
            ),
            fakeAudio(),
        )
        val existing = AudioTagWriter.probe("flac", ByteArrayInputStream(original))
        assertEquals("晴天", existing?.title)
        assertEquals("周杰伦", existing?.artist)
    }

    @Test
    fun probeFlac_withoutCommentReturnsEmptyTag() {
        val original = flacFile(listOf(STREAMINFO to streamInfo()), fakeAudio())
        val existing = AudioTagWriter.probe("flac", ByteArrayInputStream(original))
        assertEquals(null, existing?.title)
        assertEquals(null, existing?.artist)
    }

    @Test
    fun flacWithBadMagic_probeNullAndRetagWritesNothing() {
        val notFlac = "OggS".toByteArray(Charsets.ISO_8859_1) + fakeAudio()
        assertNull(AudioTagWriter.probe("flac", ByteArrayInputStream(notFlac)))

        val out = ByteArrayOutputStream()
        expectThrows<AudioTagUnsupportedException> {
            AudioTagWriter.retag("flac", "晴天", "周杰伦", ByteArrayInputStream(notFlac), out)
        }
        assertEquals("失败时必须一个字节都没写出去", 0, out.size())
    }

    @Test
    fun flacWithTruncatedBlock_isLeftAlone() {
        // 块头声明 100 字节,实际只给 4 字节
        val broken = "fLaC".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x64) +
            byteArrayOf(1, 2, 3, 4)
        assertNull(AudioTagWriter.probe("flac", ByteArrayInputStream(broken)))
        expectThrows<AudioTagUnsupportedException> { retag("flac", "晴天", "周杰伦", broken) }
    }

    @Test
    fun flacWithoutStreamInfoFirst_isLeftAlone() {
        val broken = flacFile(listOf(PADDING to ByteArray(8)), fakeAudio())
        assertNull(AudioTagWriter.probe("flac", ByteArrayInputStream(broken)))
        expectThrows<AudioTagUnsupportedException> { retag("flac", "晴天", "周杰伦", broken) }
    }

    // ==================== 通用 ====================

    @Test
    fun supportsCoversOnlyMp3AndFlac() {
        assertTrue(AudioTagWriter.supports("mp3"))
        assertTrue(AudioTagWriter.supports("FLAC"))
        listOf("m4a", "aac", "ogg", "wav", "", null).forEach {
            assertFalse("不应支持 $it", AudioTagWriter.supports(it))
        }
    }

    @Test
    fun unsupportedExtensionInRetagThrows() {
        val out = ByteArrayOutputStream()
        expectThrows<AudioTagUnsupportedException> {
            AudioTagWriter.retag("m4a", "晴天", "周杰伦", ByteArrayInputStream(fakeAudio()), out)
        }
        assertEquals(0, out.size())
    }

    @Test
    fun cjkAndEmojiRoundTripInBothContainers() {
        val title = "晴天🌤"
        val artist = "周杰伦 / 费玉清"

        val mp3 = retag("mp3", title, artist, fakeAudio())
        val (frames, _) = parseId3(mp3)
        assertEquals(title, decodeText(frames.getValue("TIT2")))
        assertEquals(artist, decodeText(frames.getValue("TPE1")))

        val flac = retag("flac", title, artist, flacFile(listOf(STREAMINFO to streamInfo()), fakeAudio()))
        val (blocks, _) = parseFlac(flac)
        val (_, fields) = vorbisComment(blocks.first { it.first == COMMENT }.second)
        assertEquals(title, fieldOf(fields, "TITLE"))
        assertEquals(artist, fieldOf(fields, "ARTIST"))
    }

    @Test
    fun newlineAndNulAreFlattened() {
        val mp3 = retag("mp3", "晴\n天", "周\u0000杰伦", fakeAudio())
        val (frames, _) = parseId3(mp3)
        assertEquals("晴 天", decodeText(frames.getValue("TIT2")))
        assertEquals("周 杰伦", decodeText(frames.getValue("TPE1")))

        val flac = retag("flac", "晴\r\n天", "周杰伦", flacFile(listOf(STREAMINFO to streamInfo()), fakeAudio()))
        val (blocks, _) = parseFlac(flac)
        val (_, fields) = vorbisComment(blocks.first { it.first == COMMENT }.second)
        assertEquals("晴  天", fieldOf(fields, "TITLE"))
    }

    // ==================== fixture / 独立解析 ====================

    private fun fakeAudio(size: Int = 64): ByteArray = ByteArray(size) { (it % 251).toByte() }

    private fun be32(value: Int) = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun syncSafe(value: Int) = byteArrayOf(
        ((value ushr 21) and 0x7F).toByte(),
        ((value ushr 14) and 0x7F).toByte(),
        ((value ushr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )

    private fun textPayload(text: String, encoding: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        when (encoding) {
            0 -> {
                out.write(0)
                out.write(text.toByteArray(Charsets.ISO_8859_1))
            }
            1 -> {
                out.write(1)
                out.write(0xFF)
                out.write(0xFE)
                out.write(text.toByteArray(Charsets.UTF_16LE))
            }
            else -> {
                out.write(3)
                out.write(text.toByteArray(Charsets.UTF_8))
            }
        }
        return out.toByteArray()
    }

    private fun id3Frame(id: String, payload: ByteArray, major: Int = 3, flags: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        out.write(if (major >= 4) syncSafe(payload.size) else be32(payload.size))
        out.write((flags ushr 8) and 0xFF)
        out.write(flags and 0xFF)
        out.write(payload)
        return out.toByteArray()
    }

    private fun id3Tag(frames: List<ByteArray>, major: Int = 3, flags: Int = 0): ByteArray {
        val body = ByteArrayOutputStream().apply { frames.forEach { write(it) } }.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(major)
        out.write(0)
        out.write(flags)
        out.write(syncSafe(body.size))
        out.write(body)
        return out.toByteArray()
    }

    /** 返回 帧ID→载荷 与标签结束偏移 */
    private fun parseId3(bytes: ByteArray): Pair<Map<String, ByteArray>, Int> {
        assertEquals("ID3", String(bytes, 0, 3, Charsets.ISO_8859_1))
        val major = bytes[3].toInt() and 0xFF
        val total = 10 + syncSafeAt(bytes, 6)
        val frames = LinkedHashMap<String, ByteArray>()
        var offset = 10
        while (offset + 10 <= total) {
            if (bytes[offset].toInt() == 0) break
            val id = String(bytes, offset, 4, Charsets.ISO_8859_1)
            val size = if (major >= 4) syncSafeAt(bytes, offset + 4) else be32At(bytes, offset + 4)
            frames[id] = bytes.copyOfRange(offset + 10, offset + 10 + size)
            offset += 10 + size
        }
        return frames to total
    }

    private fun syncSafeAt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)

    private fun be32At(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun decodeText(payload: ByteArray): String {
        assertEquals("文本帧编码应为 UTF-16", 1, payload[0].toInt())
        val littleEndian = payload[1] == 0xFF.toByte() && payload[2] == 0xFE.toByte()
        val body = payload.copyOfRange(3, payload.size)
        return String(body, if (littleEndian) Charsets.UTF_16LE else Charsets.UTF_16BE)
    }

    private fun streamInfo(): ByteArray = ByteArray(34) { (it + 1).toByte() }

    private fun flacBlock(type: Int, data: ByteArray, last: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((if (last) 0x80 else 0x00) or (type and 0x7F))
        out.write((data.size ushr 16) and 0xFF)
        out.write((data.size ushr 8) and 0xFF)
        out.write(data.size and 0xFF)
        out.write(data)
        return out.toByteArray()
    }

    private fun flacFile(blocks: List<Pair<Int, ByteArray>>, audio: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.ISO_8859_1))
        blocks.forEachIndexed { index, (type, data) ->
            out.write(flacBlock(type, data, index == blocks.lastIndex))
        }
        out.write(audio)
        return out.toByteArray()
    }

    private fun vorbisComment(vendor: String, fields: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        fun le32(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
            out.write((value ushr 16) and 0xFF)
            out.write((value ushr 24) and 0xFF)
        }
        val vendorBytes = vendor.toByteArray(Charsets.UTF_8)
        le32(vendorBytes.size)
        out.write(vendorBytes)
        le32(fields.size)
        fields.forEach { (key, value) ->
            val bytes = "$key=$value".toByteArray(Charsets.UTF_8)
            le32(bytes.size)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    /** 返回 块列表 与音频起始偏移 */
    private fun parseFlac(bytes: ByteArray): Pair<List<Pair<Int, ByteArray>>, Int> {
        assertEquals("fLaC", String(bytes, 0, 4, Charsets.ISO_8859_1))
        val blocks = mutableListOf<Pair<Int, ByteArray>>()
        var offset = 4
        while (true) {
            val header = bytes[offset].toInt() and 0xFF
            val length = ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
            blocks += (header and 0x7F) to bytes.copyOfRange(offset + 4, offset + 4 + length)
            offset += 4 + length
            if ((header and 0x80) != 0) break
        }
        return blocks to offset
    }

    private fun vorbisComment(data: ByteArray): Pair<String, List<Pair<String, String>>> {
        var offset = 0

        fun le32(): Int {
            val value = (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
            offset += 4
            return value
        }

        val vendorLength = le32()
        val vendor = String(data.copyOfRange(offset, offset + vendorLength), Charsets.UTF_8)
        offset += vendorLength
        val fields = mutableListOf<Pair<String, String>>()
        repeat(le32()) {
            val length = le32()
            val text = String(data.copyOfRange(offset, offset + length), Charsets.UTF_8)
            offset += length
            val split = text.indexOf('=')
            fields += text.substring(0, split) to text.substring(split + 1)
        }
        return vendor to fields
    }

    private fun fieldOf(fields: List<Pair<String, String>>, name: String): String? =
        fields.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    private fun retag(ext: String, title: String, artist: String, source: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        AudioTagWriter.retag(ext, title, artist, ByteArrayInputStream(source), out)
        return out.toByteArray()
    }

    private inline fun <reified T : Throwable> expectThrows(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return
            throw AssertionError("期望抛 ${T::class.simpleName},实际抛了 ${e::class.simpleName}", e)
        }
        throw AssertionError("期望抛 ${T::class.simpleName},但什么都没抛")
    }

    private companion object {
        const val COMMENT = 4
        const val PICTURE = 6
        const val PADDING = 1
        const val STREAMINFO = 0
        const val DEFAULT_VENDOR = "WatchMusic"
    }
}
