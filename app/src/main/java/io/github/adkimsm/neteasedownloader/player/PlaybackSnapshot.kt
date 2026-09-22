package io.github.adkimsm.neteasedownloader.player

import kotlinx.serialization.Serializable

/**
 * 播放状态快照 —— 进程被系统杀掉后重启,能接着上次的位置播。
 *
 * 只存恢复播放必需的字段。恢复时**必须与本地库求交**:歌单取消勾选后本地文件会被
 * 同步删掉,快照里那些 songId 已经不存在,直接丢掉(见 [restoreQueue])。
 */
@Serializable
data class PlaybackSnapshot(
    val songIds: List<Long> = emptyList(),
    val index: Int = 0,
    val positionMs: Long = 0L,
    val repeat: String = Repeat.OFF.name,
    val shuffle: Boolean = false,
    val sourcePlaylistId: Long? = null,
    val savedAt: Long = 0L,
) {
    val isEmpty: Boolean get() = songIds.isEmpty()

    companion object {
        val EMPTY = PlaybackSnapshot()
    }
}

/** 快照里的 repeat 是字符串;未知值(降级/损坏)一律回落 [Repeat.OFF] */
fun repeatFrom(raw: String?): Repeat =
    Repeat.entries.firstOrNull { it.name == raw } ?: Repeat.OFF

/** 可用于重建播放的队列 */
data class RestoredQueue(
    val songIds: List<Long>,
    val index: Int,
    val positionMs: Long,
)

/**
 * 把快照与"本地库里仍存在的歌"求交,得到可重建的队列。
 *
 * 返回 null 表示没有可恢复的内容(快照为空、或歌全没了)。
 * 当前曲若已被删掉,则退回求交后的第一首 —— 位置也就没有保留的意义,置 0。
 */
fun restoreQueue(snapshot: PlaybackSnapshot, availableIds: Set<Long>): RestoredQueue? {
    if (snapshot.songIds.isEmpty()) return null
    val kept = snapshot.songIds.filter { it in availableIds }
    if (kept.isEmpty()) return null

    val safeIndex = snapshot.index.coerceIn(0, snapshot.songIds.lastIndex)
    val currentId = snapshot.songIds[safeIndex]
    val newIndex = kept.indexOf(currentId)

    return if (newIndex >= 0) {
        RestoredQueue(kept, newIndex, snapshot.positionMs.coerceAtLeast(0L))
    } else {
        RestoredQueue(kept, 0, 0L)
    }
}
