package io.github.adkimsm.neteasedownloader.sync

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** 文件里已存在的标签(字段缺失为 null);容器无法解析时调用方从 [AudioTagWriter.probe] 拿到 null */
data class ExistingTag(val title: String?, val artist: String?)

/**
 * 容器损坏,或用了无法安全保留的标签特性。
 * 抛出即代表「这个文件别动」——调用方必须保持原文件不变。
 */
class AudioTagUnsupportedException(message: String) : Exception(message)

/**
 * 把歌名/歌手写进音频文件自身的标签:
 *  - mp3  → ID3v2 文本帧 `TIT2`(歌名)/ `TPE1`(歌手)
 *  - flac → VORBIS_COMMENT 的 `TITLE=` / `ARTIST=`
 *
 * **只动这两个字段**:其余已有帧/字段(封面 APIC、专辑 TALB、音轨号、FLAC 的 PICTURE…)
 * 与音频正文原样保留,既不新增也不删除。
 *
 * 无法安全处理时抛 [AudioTagUnsupportedException],绝不写出半成品:
 * 调用方总是先写到临时文件,失败时原文件仍是完整的。
 *
 * 纯函数、无 Android 依赖,便于单测(与 FileNamePolicy 同风格)。
 */
object AudioTagWriter {

    /** ID3 标签体 / FLAC 元数据区的读取上限:畸形长度不至于把内存拖爆 */
    private const val MAX_METADATA_BYTES = 8 * 1024 * 1024
    private const val MAX_VORBIS_FIELDS = 100_000
    private const val COPY_BUFFER = 8 * 1024
    private const val FLAC_MAGIC = "fLaC"
    private const val DEFAULT_VENDOR = "WatchMusic"

    private val FRAME_ID = Regex("[A-Z0-9]{4}")

    /** 该扩展名是否有写入器;m4a/aac/ogg 等一律跳过 */
    fun supports(ext: String?): Boolean = when (ext?.lowercase()) {
        "mp3", "flac" -> true
        else -> false
    }

    /**
     * 只读容器头部,解析已有的歌名/歌手。
     * 返回 null 表示「无法安全处理」(非预期容器、截断、特性不支持)——调用方据此跳过该文件。
     * 支持但无标签时返回 `ExistingTag(null, null)`。
     */
    fun probe(ext: String, source: InputStream): ExistingTag? = runCatching {
        when (ext.lowercase()) {
            "mp3" -> probeMp3(source)
            "flac" -> probeFlac(source)
            else -> null
        }
    }.getOrNull()

    /**
     * 重写容器头部:替换歌名/歌手,其余元数据与音频正文原样搬运到 sink。
     * 返回写入 sink 的字节数。无法安全处理时抛 [AudioTagUnsupportedException]。
     */
    fun retag(
        ext: String,
        title: String,
        artist: String,
        source: InputStream,
        sink: OutputStream,
    ): Long {
        val counter = CountingOutputStream(sink)
        when (ext.lowercase()) {
            "mp3" -> retagMp3(title, artist, source, counter)
            "flac" -> retagFlac(title, artist, source, counter)
            else -> throw AudioTagUnsupportedException("不支持的扩展名:$ext")
        }
        counter.flush()
        return counter.count
    }

    // ==================== MP3 / ID3v2 ====================

    private class Id3Frame(val id: String, val raw: ByteArray)

    private fun probeMp3(source: InputStream): ExistingTag? {
        val head = source.readUpTo(10)
        if (head.size < 10 || !head.startsWithId3()) return ExistingTag(null, null)
        val major = head[3].toInt() and 0xFF
        expectPlainId3Header(head, major)
        val body = source.readMetadataBody(syncSafeToInt(head, 6))
        var title: String? = null
        var artist: String? = null
        parseId3Frames(body, major).forEach { frame ->
            when (frame.id) {
                "TIT2" -> title = decodeTextFrame(frame.raw)
                "TPE1" -> artist = decodeTextFrame(frame.raw)
            }
        }
        return ExistingTag(title, artist)
    }

    private fun retagMp3(title: String, artist: String, source: InputStream, sink: OutputStream) {
        val head = source.readUpTo(10)
        if (head.size == 10 && head.startsWithId3()) {
            val major = head[3].toInt() and 0xFF
            expectPlainId3Header(head, major)
            val body = source.readMetadataBody(syncSafeToInt(head, 6))
            // 只摘掉要替换的两帧,封面/专辑等原样保留
            val kept = parseId3Frames(body, major).filter { it.id != "TIT2" && it.id != "TPE1" }
            sink.write(buildId3Tag(major, kept, title, artist))
        } else {
            // 无标签(或不足 10 字节的极小文件):前置新标签,已读到的字节原样补齐
            sink.write(buildId3Tag(3, emptyList(), title, artist))
            sink.write(head)
        }
        copyAll(source, sink)
    }

    /** unsynchronisation / extended header / footer 都会改变标签体布局,碰到就整体放弃 */
    private fun expectPlainId3Header(head: ByteArray, major: Int) {
        if ((head[5].toInt() and 0xFF) != 0) {
            throw AudioTagUnsupportedException("ID3 header 带标志位:0x${(head[5].toInt() and 0xFF).toString(16)}")
        }
        if (major != 3 && major != 4) {
            throw AudioTagUnsupportedException("ID3v2.$major 不支持")
        }
    }

    /**
     * 解析帧区直到 padding。
     * 帧长在 v2.3 是大端原值、v2.4 是 28-bit syncsafe —— 弄混会把帧切开,这里按版本分开处理。
     */
    private fun parseId3Frames(body: ByteArray, major: Int): List<Id3Frame> {
        val frames = ArrayList<Id3Frame>()
        var offset = 0
        while (offset + 10 <= body.size) {
            if (body[offset].toInt() == 0) break // padding 开始
            val id = String(body, offset, 4, Charsets.ISO_8859_1)
            if (!FRAME_ID.matches(id)) break
            val size = if (major >= 4) syncSafeToInt(body, offset + 4) else readInt32Be(body, offset + 4)
            val flags = ((body[offset + 8].toInt() and 0xFF) shl 8) or (body[offset + 9].toInt() and 0xFF)
            // 低字节是格式标志(压缩/加密/分组/数据长度指示/帧内 unsync):原样搬移语义不明确,放弃
            if (flags and 0x00FF != 0) {
                throw AudioTagUnsupportedException("ID3 帧标志不支持:0x${flags.toString(16)}")
            }
            if (size < 0 || 10L + size > (body.size - offset).toLong()) {
                throw AudioTagUnsupportedException("ID3 帧长度越界")
            }
            frames += Id3Frame(id, body.copyOfRange(offset, offset + 10 + size))
            offset += 10 + size
        }
        return frames
    }

    /**
     * 按原标签的主版本重建(v2.3 帧长大端原值 / v2.4 syncsafe)。
     * 保留的帧是原样搬过来的,版本必须跟着原文件走,否则播放器会把帧长读错。
     * 无标签时新建 v2.3:帧长大端原值,播放器兼容性最好。
     */
    private fun buildId3Tag(major: Int, kept: List<Id3Frame>, title: String, artist: String): ByteArray {
        val payload = ByteArrayOutputStream()
        payload.write(buildTextFrame("TIT2", title, major))
        payload.write(buildTextFrame("TPE1", artist, major))
        kept.forEach { payload.write(it.raw) }
        val frames = payload.toByteArray()

        val out = ByteArrayOutputStream(frames.size + 10)
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(major) // 主版本:跟原标签一致
        out.write(0) // 修订号
        out.write(0) // 标志位
        out.write(intToSyncSafe(frames.size))
        out.write(frames)
        return out.toByteArray()
    }

    private fun buildTextFrame(id: String, text: String, major: Int): ByteArray {
        val value = sanitize(text)
        val payload = ByteArrayOutputStream(value.length * 2 + 3)
        payload.write(0x01) // 编码 0x01 = UTF-16 带 BOM:中文必须是这个
        payload.write(0xFF) // 显式小端 BOM,兼容性比 Java 默认的大端更好
        payload.write(0xFE)
        payload.write(value.toByteArray(Charsets.UTF_16LE))
        val body = payload.toByteArray()

        val out = ByteArrayOutputStream(body.size + 10)
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        out.write(if (major >= 4) intToSyncSafe(body.size) else intToBe32(body.size))
        out.write(0) // 帧标志(状态)
        out.write(0) // 帧标志(格式)
        out.write(body)
        return out.toByteArray()
    }

    /** raw 是整个帧(帧头 + 载荷) */
    private fun decodeTextFrame(raw: ByteArray): String? {
        if (raw.size <= 10) return null
        val payload = raw.copyOfRange(11, raw.size)
        val decoded = when (raw[10].toInt() and 0xFF) {
            0 -> String(payload, Charsets.ISO_8859_1)
            1 -> decodeUtf16(payload)
            2 -> String(payload, Charsets.UTF_16BE)
            3 -> String(payload, Charsets.UTF_8)
            else -> return null
        }
        return decoded.trimEnd('\u0000').trim().ifEmpty { null }
    }

    private fun decodeUtf16(bytes: ByteArray): String {
        if (bytes.size < 2) return ""
        val littleEndian = bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()
        val bigEndian = bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()
        val body = bytes.copyOfRange(2, bytes.size)
        return when {
            littleEndian -> String(body, Charsets.UTF_16LE)
            bigEndian -> String(body, Charsets.UTF_16BE)
            else -> String(body, Charsets.UTF_16LE) // 无 BOM:按最常见的 LE 兜底
        }
    }

    // ==================== FLAC / VORBIS_COMMENT ====================

    private class FlacBlock(val type: Int, val data: ByteArray)

    private class VorbisComment(val vendor: String, val fields: List<Pair<String, String>>)

    /**
     * 读到最后一个 metadata block 为止,流正好停在音频帧起点。
     * STREAMINFO 必须是第一块,否则视为容器损坏。
     */
    private fun parseFlacBlocks(source: InputStream): List<FlacBlock> {
        val magic = source.readUpTo(4)
        if (magic.size < 4 || String(magic, Charsets.ISO_8859_1) != FLAC_MAGIC) {
            throw AudioTagUnsupportedException("不是 FLAC 容器")
        }
        val blocks = ArrayList<FlacBlock>()
        var total = 0
        while (true) {
            val header = source.readExactly(4)
            val last = (header[0].toInt() and 0x80) != 0
            val type = header[0].toInt() and 0x7F
            val length = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)
            if (total.toLong() + length > MAX_METADATA_BYTES) {
                throw AudioTagUnsupportedException("FLAC 元数据过大")
            }
            blocks += FlacBlock(type, source.readExactly(length))
            total += length
            if (last) break
        }
        if (blocks[0].type != 0) throw AudioTagUnsupportedException("FLAC 第一块不是 STREAMINFO")
        return blocks
    }

    private fun probeFlac(source: InputStream): ExistingTag? {
        val fields = collectCommentFields(parseFlacBlocks(source))
        if (fields.isEmpty()) return ExistingTag(null, null)
        return ExistingTag(fieldValue(fields, "TITLE"), fieldValue(fields, "ARTIST"))
    }

    private fun retagFlac(title: String, artist: String, source: InputStream, sink: OutputStream) {
        val blocks = parseFlacBlocks(source)
        val vendor = blocks.firstOrNull { it.type == COMMENT_BLOCK }
            ?.let { parseVorbisComment(it.data).vendor }
            ?: DEFAULT_VENDOR

        // 只替换 TITLE/ARTIST,其余字段原样保留;已有 comment 块合并成一个,避免出现重复块
        val preserved = collectCommentFields(blocks).filter {
            !it.first.equals("TITLE", ignoreCase = true) && !it.first.equals("ARTIST", ignoreCase = true)
        }
        val comment = FlacBlock(
            COMMENT_BLOCK,
            buildVorbisComment(vendor, preserved + ("TITLE" to sanitize(title)) + ("ARTIST" to sanitize(artist))),
        )

        val emitted = ArrayList<FlacBlock>(blocks.size + 1)
        emitted += blocks[0] // STREAMINFO 必须仍是第一块
        emitted += comment
        blocks.drop(1).filter { it.type != COMMENT_BLOCK }.forEach { emitted += it }

        sink.write(FLAC_MAGIC.toByteArray(Charsets.ISO_8859_1))
        emitted.forEachIndexed { index, block ->
            val last = if (index == emitted.lastIndex) 0x80 else 0x00
            sink.write(
                byteArrayOf(
                    (last or (block.type and 0x7F)).toByte(),
                    ((block.data.size ushr 16) and 0xFF).toByte(),
                    ((block.data.size ushr 8) and 0xFF).toByte(),
                    (block.data.size and 0xFF).toByte(),
                ),
            )
            sink.write(block.data)
        }
        copyAll(source, sink) // 音频帧原样搬运
    }

    /** 合并全部 comment 块的字段(规范上只该有一个,遇到多个也不丢信息) */
    private fun collectCommentFields(blocks: List<FlacBlock>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        blocks.filter { it.type == COMMENT_BLOCK }.forEach { out += parseVorbisComment(it.data).fields }
        return out
    }

    private fun fieldValue(fields: List<Pair<String, String>>, name: String): String? =
        fields.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second?.ifEmpty { null }

    private fun parseVorbisComment(data: ByteArray): VorbisComment {
        var offset = 0

        fun le32(): Int {
            if (offset + 4 > data.size) throw AudioTagUnsupportedException("VORBIS_COMMENT 截断")
            val value = (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
            offset += 4
            return value
        }

        fun take(length: Int): ByteArray {
            if (length < 0 || offset.toLong() + length > data.size) {
                throw AudioTagUnsupportedException("VORBIS_COMMENT 字段越界")
            }
            val out = data.copyOfRange(offset, offset + length)
            offset += length
            return out
        }

        val vendor = String(take(le32()), Charsets.UTF_8)
        val count = le32()
        if (count < 0 || count > MAX_VORBIS_FIELDS) {
            throw AudioTagUnsupportedException("VORBIS_COMMENT 字段数非法:$count")
        }
        val fields = ArrayList<Pair<String, String>>(count)
        repeat(count) {
            val text = String(take(le32()), Charsets.UTF_8)
            val split = text.indexOf('=')
            if (split > 0) fields += text.substring(0, split) to text.substring(split + 1)
        }
        return VorbisComment(vendor, fields)
    }

    /** 载荷内部的长度字段是**小端**,与 FLAC 块头的大端长度不是一回事 */
    private fun buildVorbisComment(vendor: String, fields: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        fun writeLe32(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
            out.write((value ushr 16) and 0xFF)
            out.write((value ushr 24) and 0xFF)
        }

        val vendorBytes = vendor.toByteArray(Charsets.UTF_8)
        writeLe32(vendorBytes.size)
        out.write(vendorBytes)
        writeLe32(fields.size)
        fields.forEach { (key, value) ->
            val bytes = "$key=$value".toByteArray(Charsets.UTF_8)
            writeLe32(bytes.size)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    // ==================== 通用 ====================

    /** NUL 会截断文本帧,换行会破坏 Vorbis 字段语义,统一压平 */
    private fun sanitize(text: String): String =
        text.replace('\u0000', ' ').replace('\r', ' ').replace('\n', ' ').trim()

    private fun copyAll(source: InputStream, sink: OutputStream): Long {
        val buffer = ByteArray(COPY_BUFFER)
        var total = 0L
        while (true) {
            val read = source.read(buffer)
            if (read <= 0) break
            sink.write(buffer, 0, read)
            total += read
        }
        return total
    }

    private fun ByteArray.startsWithId3(): Boolean =
        size >= 3 &&
            this[0] == 'I'.code.toByte() &&
            this[1] == 'D'.code.toByte() &&
            this[2] == '3'.code.toByte()

    /** 只读取标签体,长度上限挡住畸形尺寸造成的巨额分配 */
    private fun InputStream.readMetadataBody(length: Int): ByteArray {
        if (length < 0 || length > MAX_METADATA_BYTES) {
            throw AudioTagUnsupportedException("ID3 标签体过大:$length")
        }
        return readExactly(length)
    }

    private fun InputStream.readUpTo(length: Int): ByteArray {
        val out = ByteArrayOutputStream(length)
        val buffer = ByteArray(length)
        while (out.size() < length) {
            val read = read(buffer, 0, length - out.size())
            if (read <= 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun InputStream.readExactly(length: Int): ByteArray {
        if (length < 0) throw AudioTagUnsupportedException("长度非法:$length")
        val out = ByteArrayOutputStream(length)
        val buffer = ByteArray(minOf(length.coerceAtLeast(1), COPY_BUFFER))
        while (out.size() < length) {
            val read = read(buffer, 0, minOf(buffer.size, length - out.size()))
            if (read <= 0) throw AudioTagUnsupportedException("数据截断")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** 28-bit syncsafe:每字节只用低 7 位(ID3 的 size 字段) */
    private fun syncSafeToInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)

    private fun intToSyncSafe(value: Int): ByteArray = byteArrayOf(
        ((value ushr 21) and 0x7F).toByte(),
        ((value ushr 14) and 0x7F).toByte(),
        ((value ushr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )

    private fun intToBe32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun readInt32Be(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    /** 只统计写入量,不接管 delegate 的生命周期 */
    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            count++
        }

        override fun write(b: ByteArray) {
            delegate.write(b)
            count += b.size
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            count += len
        }

        override fun flush() {
            delegate.flush()
        }

        override fun close() {
            delegate.flush()
        }
    }

    private const val COMMENT_BLOCK = 4
}
