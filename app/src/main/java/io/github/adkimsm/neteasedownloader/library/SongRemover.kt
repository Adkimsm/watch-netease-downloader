package io.github.adkimsm.neteasedownloader.library

import io.github.adkimsm.neteasedownloader.data.LikedSongDao
import io.github.adkimsm.neteasedownloader.data.MediaStoreWriter
import io.github.adkimsm.neteasedownloader.data.PlaylistDao
import io.github.adkimsm.neteasedownloader.data.PlaylistSongDao
import io.github.adkimsm.neteasedownloader.data.SongDao
import io.github.adkimsm.neteasedownloader.data.isLikedPlaylistId
import io.github.adkimsm.neteasedownloader.data.isOwnedBy
import io.github.adkimsm.neteasedownloader.diag.Diag
import io.github.adkimsm.neteasedownloader.net.NcmApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** 删除过程中需要播放器配合的两件事,接口化以免 library 层依赖 player 层 */
interface PlaybackControl {
    fun currentSongIdOrNull(): Long?

    /** 正在播这首歌时停下并跳到下一首 */
    fun stopIfPlaying(songId: Long)

    fun removeFromQueue(songId: Long)
}

/**
 * 「删除这首歌」的执行者。
 *
 * 顺序:远端歌单 → 红心 → 本地文件。
 *
 * **远端失败不回滚本地删除**:用户的首要目标是"这首歌别再出现",本地删成功才算达成,
 * 远端某个歌单没删掉是次要问题,报告出来让他重试即可 —— 反过来(本地保留)会让用户觉得
 * "点了没反应"。
 */
class SongRemover(
    private val api: NcmApi,
    private val playlistDao: PlaylistDao,
    private val playlistSongDao: PlaylistSongDao,
    private val songDao: SongDao,
    private val likedSongDao: LikedSongDao,
    private val mediaStoreWriter: MediaStoreWriter,
    private val playback: PlaybackControl,
    private val uid: () -> Long,
) {

    /**
     * 这首歌当前的处境。用于删除面板与后果预览。
     *
     * 「我喜欢的音乐」在这里被剔除:它虽然在远端是一个歌单,但增删走 `radio/like`,
     * 混进可勾选列表里会让用户以为能"只从我喜欢里移除、保留红心"。
     */
    suspend fun presenceOf(songId: Long): SongPresence {
        val song = songDao.getByIds(listOf(songId)).firstOrNull()
        val currentUid = uid()
        val all = playlistDao.getAll().associateBy { it.id }

        val entries = playlistSongDao.playlistsContaining(songId)
            .mapNotNull { (playlistId, nameFromJoin) ->
                val playlist = all[playlistId]
                if (isLikedPlaylistId(playlistId, playlist?.specialType ?: 0, currentUid)) {
                    return@mapNotNull null
                }
                PresenceEntry(
                    playlistId = playlistId,
                    name = playlist?.name ?: nameFromJoin,
                    owned = playlist?.isOwnedBy(currentUid) == true,
                    enabled = playlist?.enabled == true,
                )
            }
            // 本人歌单在前:面板上能勾的都在上面,置灰的沉底
            .sortedWith(compareByDescending<PresenceEntry> { it.owned }.thenBy { it.name })

        return SongPresence(
            songId = songId,
            songName = song?.name.orEmpty(),
            playlists = entries,
            liked = likedSongDao.isLiked(songId),
        )
    }

    suspend fun remove(songId: Long, outcome: RemoveOutcome): RemoveReport {
        Diag.i(
            TAG,
            "删除 songId=$songId 远端歌单=${outcome.remoteTargets.size} " +
                "取消红心=${outcome.unlike} 删本地=${outcome.deleteLocal}",
        )

        val ok = mutableListOf<Long>()
        val failed = mutableListOf<RemoteFailure>()
        if (outcome.remoteTargets.isNotEmpty()) {
            // 并发但逐个收集失败:一个歌单删不掉不该拖累其它歌单
            val results = coroutineScope {
                outcome.remoteTargets.map { playlistId ->
                    async {
                        runCatching { api.removeTracksFromPlaylist(playlistId, listOf(songId)) }
                            .fold(
                                onSuccess = { playlistId to null },
                                onFailure = { playlistId to (it.message ?: it.javaClass.simpleName) },
                            )
                    }
                }.awaitAll()
            }
            results.forEach { (playlistId, error) ->
                if (error == null) ok += playlistId else failed += RemoteFailure(playlistId, error)
            }
        }

        var unlikeOk: Boolean? = null
        if (outcome.unlike) {
            unlikeOk = runCatching { api.setLiked(songId, false) }.isSuccess
            if (unlikeOk) {
                likedSongDao.setLiked(songId, false)
            } else {
                // 本地红心记录保留:宁可两边不一致时以服务端为准,也不要本地先"假装"取消
                Diag.w(TAG, "取消红心失败 songId=$songId,本地红心记录保留")
            }
        }

        var fileDeleted = false
        if (outcome.deleteLocal) {
            // 先停播再删文件:正在听的歌被删掉时播放器只会报一个看不懂的错
            playback.stopIfPlaying(songId)
            val song = songDao.getByIds(listOf(songId)).firstOrNull()
            if (song?.localUri != null) {
                mediaStoreWriter.deleteByUriString(song.localUri)
                fileDeleted = true
            }
            songDao.clearLocal(listOf(songId))
            playback.removeFromQueue(songId)
        }

        ok.forEach { playlistId -> playlistSongDao.delete(playlistId, listOf(songId)) }

        // 写后读回:这个接口返回 200 却不生效是常事
        val stale = mutableListOf<Long>()
        ok.forEach { playlistId ->
            val stillThere = runCatching {
                api.fetchPlaylistTrackIds(playlistId).trackIds.any { it.id == songId }
            }.getOrDefault(false)
            if (stillThere) {
                stale += playlistId
                Diag.w(TAG, "写后读回不一致:歌单 $playlistId 仍含 songId=$songId")
            }
        }

        return RemoveReport(
            songId = songId,
            fileDeleted = fileDeleted,
            remoteOk = ok,
            remoteFailed = failed,
            unlikeOk = unlikeOk,
            remoteStale = stale,
        )
    }

    /**
     * 撤销:把刚移除的歌加回歌单、恢复红心。
     *
     * 本地文件不在这里恢复 —— 下一次同步会把它下回来。当场重新下载会让"撤销"变成一个
     * 需要等几十秒的按钮,那不是撤销该有的手感。
     */
    suspend fun undo(songId: Long, plan: UndoPlan): UndoReport {
        if (plan.isEmpty) return UndoReport()

        val restored = mutableListOf<Long>()
        val failures = mutableListOf<RemoteFailure>()
        for (playlistId in plan.playlistIds) {
            runCatching { api.addTracksToPlaylist(playlistId, listOf(songId)) }
                .onSuccess { restored += playlistId }
                .onFailure { failures += RemoteFailure(playlistId, it.message ?: it.javaClass.simpleName) }
        }

        var reliked = false
        if (plan.relike) {
            reliked = runCatching { api.setLiked(songId, true) }.isSuccess
            if (reliked) likedSongDao.setLiked(songId, true)
        }

        Diag.i(TAG, "撤销 songId=$songId 恢复歌单=${restored.size} 恢复红心=$reliked")
        return UndoReport(restored, reliked, failures)
    }

    private companion object {
        const val TAG = "SongRemover"
    }
}
