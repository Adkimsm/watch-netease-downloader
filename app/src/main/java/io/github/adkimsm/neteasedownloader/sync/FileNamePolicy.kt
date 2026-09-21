package io.github.adkimsm.neteasedownloader.sync

/**
 * 下载文件名规范:「歌名 - 歌手.ext」,如「晴天 - 周杰伦.mp3」。
 *
 * 规则:
 * - 非法字符(\ / : * ? " < > | 与控制符)替换为「_」;
 * - 歌手字段里的「/」是网易多歌手分隔符,归一为「、」(原始「/」本身不能进文件名);
 * - 各部分去首尾空白与点、连续空白压成一个空格,避免「 . 」「末尾空格/点」这类脏尾巴;
 * - 总长超限时截断主体、保住扩展名(旧实现的 take 会把 ".flac" 一起截掉);
 * - 同名冲突交给 MediaStore 自动加「 (n)」后缀,数据库按 uri 跟踪不受影响。
 *
 * 纯函数、无 Android 依赖,便于单测。
 */
object FileNamePolicy {

    /** MediaStore 建议的完整文件名(含扩展名)长度上限 */
    const val MAX_FILE_NAME = 160

    private const val MAX_PART = 60
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private val WHITESPACE = Regex("\\s+")

    /** 生成规范文件名;type 为空或无法识别时回退 mp3 */
    fun build(songId: Long, name: String, artist: String, type: String?): String {
        val ext = extOf(type)
        val title = clean(name).ifEmpty { "未知歌名" }
        val artistPart = clean(artist.replace('/', '、')).ifEmpty { "未知歌手" }
        val base = when {
            title == "未知歌名" && artistPart == "未知歌手" -> "song_$songId"
            else -> "$title - $artistPart"
        }
        // 保住 ".ext":先按总长截断主体,再去掉截断残尾的连接符/空白/点
        val maxBase = (MAX_FILE_NAME - ext.length - 1).coerceAtLeast(1)
        val trimmed = base.take(maxBase).trimEnd(' ', '-', '_', '.')
        return "$trimmed.$ext"
    }

    private fun extOf(type: String?): String =
        type?.lowercase()?.filter { it in 'a'..'z' || it in '0'..'9' }?.take(8)?.takeIf { it.isNotEmpty() }
            ?: "mp3"

    /** 单个字段净化:非法字符→_,压缩空白,去首尾空白/点/连接符 */
    private fun clean(text: String): String =
        text.replace(ILLEGAL, "_")
            .replace(WHITESPACE, " ")
            .trim(' ', '.', '_')
            .take(MAX_PART)
            .trimEnd(' ', '-', '_', '.')
}
