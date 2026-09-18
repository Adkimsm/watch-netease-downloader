package io.github.adkimsm.neteasedownloader.data

enum class SongState { PENDING, DOWNLOADING, OK, FAILED, MISSING_URL }

data class PlaylistEntity(
    val id: Long,
    val name: String,
    val cover: String?,
    val trackCount: Int,
    val enabled: Boolean = false,
    val lastSyncAt: Long? = null,
)

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
)

data class PlaylistSongEntity(
    val playlistId: Long,
    val songId: Long,
    val sortIndex: Int,
)
