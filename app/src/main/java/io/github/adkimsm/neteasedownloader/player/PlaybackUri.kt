package io.github.adkimsm.neteasedownloader.player

/**
 * 队列里所有曲目统一使用自定义 scheme:
 *
 * ```
 * watchmusic://song/<songId>
 * ```
 *
 * 真实地址在数据源打开前才解析([LocalFirstDataSource]):已下载 → `content://`,
 * 未下载 → 网易云签名 https 直链。这样"本地优先 + 串流兜底"只有一条代码路径,
 * 而且 seek / 重试 / 本地文件失效降级都走同一处判定。
 */
object PlaybackUri {
    const val SCHEME = "watchmusic"
    private const val HOST = "song"
    private const val PREFIX = "$SCHEME://$HOST/"

    fun forSong(songId: Long): String = "$PREFIX$songId"

    /** 解析出自定义 URI 里的 songId;不是本 scheme 或格式不对时返回 null */
    fun songIdOf(uri: String?): Long? {
        val raw = uri?.trim().orEmpty()
        if (!raw.startsWith(PREFIX, ignoreCase = true)) return null
        return raw.substring(PREFIX.length)
            .substringBefore('/')
            .substringBefore('?')
            .toLongOrNull()
            ?.takeIf { it > 0L }
    }

    fun isPlaybackUri(uri: String?): Boolean = songIdOf(uri) != null
}
