package io.github.adkimsm.neteasedownloader.sync

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import io.github.adkimsm.neteasedownloader.data.MediaStoreWriter
import io.github.adkimsm.neteasedownloader.data.PlaylistDao
import io.github.adkimsm.neteasedownloader.data.PlaylistEntity
import io.github.adkimsm.neteasedownloader.data.PlaylistSongDao
import io.github.adkimsm.neteasedownloader.data.PlaylistSongEntity
import io.github.adkimsm.neteasedownloader.data.SettingsStore
import io.github.adkimsm.neteasedownloader.data.SongDao
import io.github.adkimsm.neteasedownloader.data.SongEntity
import io.github.adkimsm.neteasedownloader.data.SongState
import io.github.adkimsm.neteasedownloader.net.Downloader
import io.github.adkimsm.neteasedownloader.net.NcmApi
import io.github.adkimsm.neteasedownloader.net.PlaylistDto
import io.github.adkimsm.neteasedownloader.net.SongUrlDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit

class StorageShortageException(val estimated: Long, val available: Long) :
    RuntimeException("空间不足:需约 ${estimated / MB}MB,仅剩 ${available / MB}MB") {

    companion object {
        private const val MB = 1024 * 1024L
    }
}

class SyncEngine(
    private val context: Context,
    private val api: NcmApi,
    private val settingsStore: SettingsStore,
    private val playlistDao: PlaylistDao,
    private val songDao: SongDao,
    private val playlistSongDao: PlaylistSongDao,
    private val mediaStoreWriter: MediaStoreWriter,
) {
    enum class Stage { REFRESHING, READY, DOWNLOADING, DELETING, DONE, FAILED }

    data class Progress(
        val stage: Stage,
        val message: String,
        val total: Int = 0,
        val done: Int = 0,
    )

    data class Diff(
        val toDownload: List<SongEntity>,
        val toDelete: List<SongEntity>,
        val missingUrlCount: Int,
        val estimatedBytes: Long,
        val availableBytes: Long,
    )

    private val _progress = MutableStateFlow(Progress(Stage.REFRESHING, ""))
    val progress = _progress.asStateFlow()

    private val urlCache = HashMap<Long, SongUrlDto>()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    private val downloader = Downloader(downloadClient)

    /** 刷新远端状态并算出差量 */
    suspend fun refreshAndDiff(): Diff {
        _progress.value = Progress(Stage.REFRESHING, "正在检查登录…")
        val account = api.fetchAccount() ?: throw IllegalStateException("未登录,请重新扫码")
        val uid = account.id

        _progress.value = Progress(Stage.REFRESHING, "正在拉取歌单…")
        val remotePlaylists = api.fetchUserPlaylists(uid)
        refreshPlaylistTable(remotePlaylists)

        val enabled = playlistDao.getEnabled()
        if (enabled.isEmpty()) {
            _progress.value = Progress(Stage.READY, "没有勾选的歌单")
            return Diff(emptyList(), emptyList(), 0, 0L, availableBytes())
        }

        songDao.resetStaleDownloading()
        urlCache.clear()

        enabled.forEachIndexed { index, playlist ->
            ensureActive()
            _progress.value = Progress(
                Stage.REFRESHING,
                "正在拉取「${playlist.name}」(${index + 1}/${enabled.size})…",
            )
            refreshPlaylistTracks(playlist)
        }

        return computeDiff()
    }

    /** 执行下载与删除 */
    suspend fun execute(diff: Diff) {
        if (diff.toDownload.isNotEmpty()) {
            _progress.value = Progress(
                Stage.DOWNLOADING,
                "开始下载 ${diff.toDownload.size} 首…",
                diff.toDownload.size,
                0,
            )
            diff.toDownload.forEachIndexed { index, song ->
                ensureActive()
                _progress.value = Progress(
                    Stage.DOWNLOADING,
                    "${song.artist} - ${song.name}",
                    diff.toDownload.size,
                    index,
                )
                downloadSong(song)
            }
        }

        if (diff.toDelete.isNotEmpty()) {
            _progress.value = Progress(
                Stage.DELETING,
                "正在清理 ${diff.toDelete.size} 首已移出歌单的文件…",
            )
            diff.toDelete.forEach { song ->
                song.localUri?.let { mediaStoreWriter.deleteByUriString(it) }
            }
            songDao.deleteByIds(diff.toDelete.map { it.songId })
        }

        // 孤儿清理:MediaStore 里残留的 WatchMusic 条目,数据库已不跟踪
        val validUris = songDao.getAllIds()
            .let { songDao.getByIds(it) }
            .filter { it.state == SongState.OK.name }
            .mapNotNull { it.localUri }
            .toSet()
        val orphans = mediaStoreWriter.deleteOrphans(validUris)

        _progress.value = Progress(Stage.DONE, "同步完成,清理孤儿 $orphans 项")
    }

    /** 一把梭:刷新 + 差量 + 执行 */
    suspend fun runSync() {
        val diff = refreshAndDiff()
        execute(diff)
    }

    private suspend fun refreshPlaylistTable(remote: List<PlaylistDto>) {
        val existing = playlistDao.getAll().associateBy { it.id }
        val merged = remote.map { p ->
            // 保留用户已设置的 enabled 标志
            PlaylistEntity(
                id = p.id,
                name = p.name,
                cover = p.coverImgUrl,
                trackCount = p.trackCount,
                enabled = existing[p.id]?.enabled ?: false,
            )
        }
        playlistDao.upsertAll(merged)
        // 远端已删除的歌单:清掉关联,歌曲引用计数随之下降,交给 diff 处理
        val remoteIds = remote.map { it.id }.toSet()
        existing.keys.filter { it !in remoteIds }.forEach { id ->
            playlistSongDao.deleteByPlaylist(id)
            playlistDao.delete(id)
        }
    }

    private suspend fun refreshPlaylistTracks(playlist: PlaylistEntity) {
        val detail = api.fetchPlaylistTrackIds(playlist.id)
        val remoteIds = detail.trackIds.map { it.id }
        if (remoteIds.isEmpty()) return

        val songs = api.fetchSongDetails(remoteIds)
        val existing = songDao.getByIds(remoteIds).associateBy { it.songId }
        val now = System.currentTimeMillis()
        val merged = songs.map { s ->
            val old = existing[s.id]
            if (old != null) {
                old.copy(
                    name = s.name,
                    artist = s.ar.joinToString("/") { it.name },
                    album = s.al?.name,
                    duration = s.dt,
                    updatedAt = now,
                )
            } else {
                SongEntity(
                    songId = s.id,
                    name = s.name,
                    artist = s.ar.joinToString("/") { it.name },
                    album = s.al?.name,
                    duration = s.dt,
                    md5 = null,
                    size = 0,
                    br = 0,
                    type = null,
                    state = SongState.PENDING.name,
                    updatedAt = now,
                )
            }
        }
        songDao.upsertAll(merged)
        playlistSongDao.deleteByPlaylist(playlist.id)
        playlistSongDao.insertAll(
            merged.mapIndexed { index, song ->
                PlaylistSongEntity(playlist.id, song.songId, index)
            },
        )
        playlistDao.setLastSyncAt(playlist.id, now)
    }

    private suspend fun computeDiff(): Diff {
        _progress.value = Progress(Stage.REFRESHING, "正在比对本地与歌单…")
        val remoteIds = playlistSongDao.enabledPlaylistSongIds().toHashSet()
        val localIds = songDao.getAllIds()
        val toDelete = songDao.getByIds(localIds.filter { it !in remoteIds })

        val pending = songDao.getByIds(remoteIds.toList())
            .filter { it.state != SongState.OK.name }

        // 批量取下载地址,不可用的(无 url / 仅试听)标记 MISSING_URL
        var missingUrlCount = 0
        val level = settingsStore.level.value
        val withUrl = mutableListOf<SongEntity>()
        if (pending.isNotEmpty()) {
            val urlMap = api.fetchSongUrls(pending.map { it.songId }, level).associateBy { it.id }
            val now = System.currentTimeMillis()
            pending.forEach { song ->
                val urlDto = urlMap[song.songId]
                val usable = urlDto?.url != null && urlDto.freeTrialInfo == null
                if (usable) {
                    urlCache[song.songId] = urlDto!!
                    withUrl += song
                    songDao.updateLocalResult(
                        songId = song.songId,
                        state = SongState.PENDING,
                        localUri = song.localUri,
                        size = urlDto.size,
                        md5 = urlDto.md5,
                        br = urlDto.br,
                        type = urlDto.type,
                    )
                } else {
                    missingUrlCount++
                    songDao.updateState(
                        song.songId,
                        SongState.MISSING_URL,
                        errorCode = if (urlDto?.url == null) "NO_URL" else "TRIAL_ONLY",
                    )
                }
            }
        }

        val estimated = urlCache.values.sumOf { it.size }
        val available = availableBytes()
        if (estimated > available * SPACE_MARGIN) {
            throw StorageShortageException(estimated, available)
        }

        _progress.value = Progress(Stage.READY, "比对完成")
        return Diff(withUrl, toDelete, missingUrlCount, estimated, available)
    }

    private suspend fun downloadSong(song: SongEntity) {
        val urlDto = urlCache[song.songId] ?: run {
            // 缓存丢失(进程被杀后断点续传),重新取这首的地址
            api.fetchSongUrls(listOf(song.songId), settingsStore.level.value)
                .firstOrNull { it.id == song.songId }
                ?.also { urlCache[song.songId] = it }
        }
        if (urlDto?.url == null || urlDto.freeTrialInfo != null) {
            songDao.updateState(song.songId, SongState.MISSING_URL, "NO_URL")
            return
        }

        val fileName = buildFileName(song, urlDto.type)
        val mediaUri = mediaStoreWriter.insertPending(
            fileName = fileName,
            title = song.name,
            artist = song.artist,
            album = song.album,
            durationMs = song.duration,
            mimeType = mimeFor(urlDto.type),
        )
        if (mediaUri == null) {
            songDao.updateState(song.songId, SongState.FAILED, "MEDIASTORE_FAIL")
            return
        }
        songDao.updateState(song.songId, SongState.DOWNLOADING)

        var lastEmit = 0L
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                context.contentResolver.openOutputStream(mediaUri, "w")?.use { output ->
                    val result = downloader.download(urlDto.url!!, output) { written ->
                        if (written - lastEmit > PROGRESS_EMIT_STEP) {
                            lastEmit = written
                            _progress.value = _progress.value.copy(
                                message = "${song.artist} - ${song.name} (${written / KB}KB)",
                            )
                        }
                    }
                    if (result.bytes != urlDto.size && urlDto.size > 0) {
                        throw IllegalStateException("大小不符:${result.bytes}/${urlDto.size}")
                    }
                    if (urlDto.md5 != null && result.md5 != null &&
                        result.md5.equals(urlDto.md5, ignoreCase = true).not()
                    ) {
                        throw IllegalStateException("md5 校验失败")
                    }
                    mediaStoreWriter.markDone(mediaUri, result.bytes)
                    songDao.updateLocalResult(
                        songId = song.songId,
                        state = SongState.OK,
                        localUri = mediaUri.toString(),
                        size = result.bytes,
                        md5 = result.md5 ?: urlDto.md5,
                        br = urlDto.br,
                        type = urlDto.type,
                    )
                    return
                } ?: throw FileNotFoundException("无法打开 MediaStore 输出流")
            } catch (e: Exception) {
                if (attempt == MAX_ATTEMPTS - 1) {
                    Log.e(TAG, "download failed: ${song.songId}", e)
                    mediaStoreWriter.delete(mediaUri)
                    songDao.updateState(
                        song.songId,
                        SongState.FAILED,
                        e.javaClass.simpleName + ":" + (e.message ?: ""),
                    )
                } else {
                    delay(RETRY_BACKOFF_MS shl attempt)
                }
            }
        }
    }

    private fun buildFileName(song: SongEntity, type: String?): String {
        val ext = (type ?: "mp3").lowercase()
        val safeArtist = sanitize(song.artist)
        val safeName = sanitize(song.name)
        return "${song.songId}_${safeArtist} - $safeName.$ext".take(MAX_FILE_NAME)
    }

    private fun sanitize(text: String): String =
        text.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(60)

    private fun mimeFor(type: String?): String = when (type?.lowercase()) {
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        else -> "audio/mpeg"
    }

    private fun availableBytes(): Long = runCatching {
        StatFs(Environment.getExternalStorageDirectory().absolutePath).availableBytes
    }.getOrDefault(0L)

    private suspend fun ensureActive() {
        // 协程取消检查:取消时抛 CancellationException,中断整个同步
        kotlin.coroutines.coroutineContext.ensureActive()
    }

    companion object {
        private const val TAG = "SyncEngine"
        private const val MB = 1024 * 1024L
        private const val KB = 1024L
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MS = 1000L
        private const val PROGRESS_EMIT_STEP = 200 * KB
        private const val MAX_FILE_NAME = 160
        private const val SPACE_MARGIN = 0.95 // 可用空间需覆盖 95% 的预估占用才放行
    }
}
