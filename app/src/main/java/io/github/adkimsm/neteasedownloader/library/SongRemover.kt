package io.github.adkimsm.neteasedownloader.library

import io.github.adkimsm.neteasedownloader.data.LikedSongDao
import io.github.adkimsm.neteasedownloader.data.MediaStoreWriter
import io.github.adkimsm.neteasedownloader.data.PendingRemovalStore
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val pendingRemovals: PendingRemovalStore,
    private val uid: () -> Long,
) {

    /**
     * 串行化队列执行:网络恢复的自动执行与「同步前兜底」可能同时到达。
     * 并发跑会对同一个歌单删两次,也会让两条路径的队列回写互相覆盖。
     */
    private val flushMutex = Mutex()

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

        val fileDeleted = outcome.deleteLocal && deleteLocalPart(songId)

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
     * 离线删除:本地部分**当场**做完,远端部分(歌单移除 + 取消红心)写进待删除队列,
     * 联网后由 [flushPending] 统一执行。
     *
     * 刻意**不删** `playlist_song` 关联:远端还没动,这首歌此刻确实还在那些歌单里。
     * 本地关联一删,下一轮同步就会把它当成「缺文件」重新下载 —— 那正是用户要避免的事。
     * 关联由 [remove] 在真正删成功之后清理。
     */
    suspend fun removeOffline(songId: Long, outcome: RemoveOutcome): RemoveReport {
        Diag.i(
            TAG,
            "离线删除 songId=$songId 排队歌单=${outcome.remoteTargets.size} " +
                "取消红心=${outcome.unlike} 删本地=${outcome.deleteLocal}",
        )
        val fileDeleted = outcome.deleteLocal && deleteLocalPart(songId)
        pendingRemovals.enqueue(songId, outcome.remoteTargets, outcome.unlike)
        return RemoveReport(songId = songId, fileDeleted = fileDeleted, queued = true)
    }

    /**
     * 统一执行排队中的远端删除。
     *
     * 逐条复用 [remove](deleteLocal = false),所以写后读回校验、失败分类、本地关联清理
     * 与在线删除是同一套逻辑;每条执行完立刻回写队列,中途被杀重跑是幂等的。
     *
     * 已经消失的歌单先剪掉:那条「从它移除」永远不可能成功,留着就是每次联网白跑一次请求、
     * 还让这首歌的「待删除」徽标永远不消失。
     */
    suspend fun flushPending(): FlushReport = flushMutex.withLock {
        val entries = pendingRemovals.snapshot()
        if (entries.isEmpty()) return@withLock FlushReport()

        val knownPlaylists = playlistDao.getAll().mapTo(HashSet()) { it.id }
        var cleared = 0
        var failed = 0

        entries.forEach { queued ->
            val entry = pruneTargets(queued, knownPlaylists)
            if (entry == null) {
                Diag.i(TAG, "待删除 songId=${queued.songId} 的目标歌单都已不存在,直接出队")
                pendingRemovals.apply(queued.songId, null)
                cleared++
                return@forEach
            }
            if (entry != queued) {
                // 先把剪掉的结果落库,免得下次又白跑一遍已经不存在的歌单
                pendingRemovals.apply(queued.songId, entry)
            }

            val report = runCatching { remove(entry.songId, flushOutcomeFor(entry)) }
                .getOrElse { e ->
                    Diag.w(TAG, "待删除执行异常 songId=${entry.songId}: ${e.message}")
                    null
                }
            if (report == null) {
                failed++
                return@forEach
            }

            // 只把确实成功的部分出队;失败与写后读回不一致的留着下次再来
            val remaining = remainingAfterFlush(entry, report)
            pendingRemovals.apply(entry.songId, remaining)
            if (remaining == null) cleared++ else failed++
        }

        Diag.i(TAG, "待删除队列执行完毕:共 ${entries.size} 条,完成 $cleared,待重试 $failed")
        FlushReport(total = entries.size, cleared = cleared, failed = failed)
    }

    /**
     * 本地文件那一半:停播 → 删 MediaStore 条目 → 清本地态 → 出队。
     * 返回是否真的删掉了文件(本来就没有本地文件的歌返回 false)。
     */
    private suspend fun deleteLocalPart(songId: Long): Boolean {
        // 先停播再删文件:正在听的歌被删掉时播放器只会报一个看不懂的错
        playback.stopIfPlaying(songId)
        val song = songDao.getByIds(listOf(songId)).firstOrNull()
        val deleted = if (song?.localUri != null) {
            mediaStoreWriter.deleteByUriString(song.localUri)
            true
        } else {
            false
        }
        songDao.clearLocal(listOf(songId))
        playback.removeFromQueue(songId)
        return deleted
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
