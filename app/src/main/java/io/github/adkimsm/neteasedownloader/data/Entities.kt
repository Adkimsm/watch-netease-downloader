package io.github.adkimsm.neteasedownloader.data

enum class SongState { PENDING, DOWNLOADING, OK, FAILED, MISSING_URL }

data class PlaylistEntity(
    val id: Long,
    val name: String,
    val cover: String?,
    val trackCount: Int,
    val enabled: Boolean = false,
    val lastSyncAt: Long? = null,
    /** 歌单创建者 uid;与当前 uid 相等才允许从远端增删曲目/重命名/删除 */
    val creatorId: Long = 0L,
    /** 网易云的歌单类型;5 = 「我喜欢的音乐」 */
    val specialType: Int = 0,
) {
    val isLikedPlaylist: Boolean get() = specialType == SPECIAL_TYPE_LIKED

    companion object {
        const val SPECIAL_TYPE_LIKED = 5
    }
}

/** 该歌单是否归 [uid] 所有(可远端编辑)。「我喜欢的音乐」永远走红心接口,不算普通歌单。 */
fun PlaylistEntity.isOwnedBy(uid: Long): Boolean =
    !isLikedPlaylist && uid != 0L && creatorId == uid

/**
 * 是否是「我喜欢的音乐」。
 *
 * 两个判据都要:网易云用 uid 直接当该歌单的 id(`specialType` 5),
 * 但历史/异常响应里 `specialType` 可能缺失,而 id == uid 这个事实更稳。
 */
fun isLikedPlaylistId(playlistId: Long, specialType: Int, uid: Long): Boolean =
    specialType == PlaylistEntity.SPECIAL_TYPE_LIKED || (uid != 0L && playlistId == uid)

data class SongEntity(
    val songId: Long,
    val name: String,
    val artist: String,
    val album: String?,
    val duration: Long,
    val md5: String?,
    val size: Long,
    val br: Long,
    val type: String?,
    val state: String, // SongState 名
    val errorCode: String? = null,
    val localUri: String? = null,
    val updatedAt: Long,
) {
    /** 本地文件可用(播放时"本地优先"的唯一判据) */
    val hasLocalFile: Boolean get() = state == SongState.OK.name && !localUri.isNullOrEmpty()
}

data class PlaylistSongEntity(
    val playlistId: Long,
    val songId: Long,
    val sortIndex: Int,
)

/** 红心歌曲。与 playlist_song 分开:红心的增删走 radio/like,不走歌单曲目接口。 */
data class LikedSongEntity(
    val songId: Long,
    val likedAt: Long,
)
