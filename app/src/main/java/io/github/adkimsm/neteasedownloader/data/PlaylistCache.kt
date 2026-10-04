package io.github.adkimsm.neteasedownloader.data

import io.github.adkimsm.neteasedownloader.diag.Diag
import io.github.adkimsm.neteasedownloader.net.NcmApi
import io.github.adkimsm.neteasedownloader.net.PlaylistDto
import io.github.adkimsm.neteasedownloader.net.SongDto

/**
 * 远端歌单/曲目 ↔ 本地缓存的**唯一入口**。
 *
 * 同步(勾选歌单的全量比对)与浏览(点进歌单看曲目)共用这一份实现:
 * 两边各写一遍的话,一首歌的"本地是否已有文件"逐渐就会得出不同答案。
 */
class PlaylistCache(
    private val api: NcmApi,
    private val playlistDao: PlaylistDao,
    private val songDao: SongDao,
    private val playlistSongDao: PlaylistSongDao,
) {

    /**
     * 把远端歌单列表合并进本地表。
     *
     * - 已存在:保留用户设置的 `enabled` 与 `lastSyncAt`,只刷新名称/封面/曲数/归属;
     * - 远端已删除:连关联一起清掉(歌曲引用计数随之下降,交给差量处理)。
     */
    suspend fun mergePlaylistTable(remote: List<PlaylistDto>) {
        val existing = playlistDao.getAll().associateBy { it.id }
        val merged = remote.map { p ->
            PlaylistEntity(
                id = p.id,
                name = p.name,
                cover = p.coverImgUrl,
                trackCount = p.trackCount,
                enabled = existing[p.id]?.enabled ?: false,
                lastSyncAt = existing[p.id]?.lastSyncAt,
                creatorId = p.creator?.userId ?: existing[p.id]?.creatorId ?: 0L,
                specialType = if (p.specialType != 0) {
                    p.specialType
                } else {
                    existing[p.id]?.specialType ?: 0
                },
            )
        }
        playlistDao.upsertAll(merged)

        val remoteIds = remote.map { it.id }.toSet()
        existing.keys.filter { it !in remoteIds }.forEach { id ->
            playlistSongDao.deleteByPlaylist(id)
            playlistDao.delete(id)
        }
    }

    /**
     * 拉取某个歌单的全部曲目并写入缓存,返回合并后的本地行。
     *
     * **已下载的信息必须原样保留**(state/localUri/md5/size/br/type):这里只更新
     * 歌名、歌手、专辑、时长这类远端元数据,否则每次进歌单详情都会把下载状态抹掉。
     */
    suspend fun loadTracks(playlistId: Long): List<SongEntity> {
        val detail = api.fetchPlaylistTrackIds(playlistId)
        val remoteIds = detail.trackIds.map { it.id }
        if (remoteIds.isEmpty()) {
            Diag.i(TAG, "歌单 $playlistId 远端无曲目,清空本地关联")
            playlistSongDao.deleteByPlaylist(playlistId)
            return emptyList()
        }
        Diag.i(TAG, "歌单 $playlistId 曲目 ${remoteIds.size} 首")

        val songs = api.fetchSongDetails(remoteIds)
        val existing = songDao.getByIds(remoteIds).associateBy { it.songId }
        val now = System.currentTimeMillis()
        val merged = songs.map { s -> merge(existing[s.id], s, now) }

        songDao.upsertAll(merged)
        playlistSongDao.deleteByPlaylist(playlistId)
        playlistSongDao.insertAll(
            merged.mapIndexed { index, song -> PlaylistSongEntity(playlistId, song.songId, index) },
        )
        playlistDao.setLastSyncAt(playlistId, now)
        return merged
    }

    private fun merge(old: SongEntity?, dto: SongDto, now: Long): SongEntity = if (old != null) {
        old.copy(
            name = dto.name,
            artist = dto.ar.joinToString("/") { it.name },
            album = dto.al?.name,
            duration = dto.dt,
            updatedAt = now,
        )
    } else {
        SongEntity(
            songId = dto.id,
            name = dto.name,
            artist = dto.ar.joinToString("/") { it.name },
            album = dto.al?.name,
            duration = dto.dt,
            md5 = null,
            size = 0,
            br = 0,
            type = null,
            state = SongState.PENDING.name,
            updatedAt = now,
        )
    }

    /**
     * 补齐红心歌曲的元数据(歌单没有、但红心列表里有的歌也要能显示歌名)。
     * 返回补到的 song 行。
     */
    suspend fun ensureSongMetadata(songIds: List<Long>): List<SongEntity> {
        if (songIds.isEmpty()) return emptyList()
        val known = songDao.getByIds(songIds)
        val missing = songIds.filter { id -> known.none { it.songId == id } }
        if (missing.isEmpty()) return known

        val now = System.currentTimeMillis()
        val fetched = api.fetchSongDetails(missing).map { merge(null, it, now) }
        if (fetched.isNotEmpty()) songDao.upsertAll(fetched)
        return known + fetched
    }

    private companion object {
        const val TAG = "PlaylistCache"
    }
}
