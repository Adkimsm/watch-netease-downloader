package io.github.adkimsm.neteasedownloader.library

import io.github.adkimsm.neteasedownloader.data.SongEntity

/**
 * 这首歌当前的处境:出现在哪些歌单里、哪些是自己能改的、是不是红心。
 *
 * 「我喜欢的音乐」**不在 [playlists] 里**:它的增删走 `radio/like`,与歌单曲目接口
 * 不是一回事。混在一起会让人以为可以"只从我喜欢里移除、但保留红心"这种自相矛盾的操作。
 */
data class PresenceEntry(
    val playlistId: Long,
    val name: String,
    val owned: Boolean,
    val enabled: Boolean,
)

data class SongPresence(
    val songId: Long,
    val songName: String,
    val playlists: List<PresenceEntry> = emptyList(),
    val liked: Boolean = false,
) {
    /** 本人歌单:可以远端移除 */
    val ownedPlaylists: List<PresenceEntry> get() = playlists.filter { it.owned }

    /** 他人歌单:接口会拒绝,只能看着 */
    val lockedPlaylists: List<PresenceEntry> get() = playlists.filter { !it.owned }

    /** 除了本地文件之外还有没有可做的事(决定面板是否值得弹) */
    val hasRemoteWork: Boolean get() = ownedPlaylists.isNotEmpty() || liked

    companion object {
        fun of(song: SongEntity): SongPresence = SongPresence(songId = song.songId, songName = song.name)
    }
}
