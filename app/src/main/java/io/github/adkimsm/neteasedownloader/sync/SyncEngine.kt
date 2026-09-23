package io.github.adkimsm.neteasedownloader.sync

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.util.Log
import io.github.adkimsm.neteasedownloader.data.CookieStore
import io.github.adkimsm.neteasedownloader.data.MediaStoreWriter
import io.github.adkimsm.neteasedownloader.data.PlaylistDao
import io.github.adkimsm.neteasedownloader.data.PlaylistCache
import io.github.adkimsm.neteasedownloader.data.PlaylistSongDao
import io.github.adkimsm.neteasedownloader.data.SettingsStore
import io.github.adkimsm.neteasedownloader.data.SongDao
import io.github.adkimsm.neteasedownloader.data.SongEntity
import io.github.adkimsm.neteasedownloader.data.SongState
import io.github.adkimsm.neteasedownloader.diag.Diag
import io.github.adkimsm.neteasedownloader.net.Downloader
import io.github.adkimsm.neteasedownloader.net.NcmApi
import io.github.adkimsm.neteasedownloader.net.SongUrlDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit

/** 单个文件补标签的结果 */
private enum class TagStatus { WRITTEN, UP_TO_DATE, UNSUPPORTED, FAILED }

/** [TagResult.bytes] 仅在 [TagStatus.WRITTEN] 时是新的落盘字节数,其余为 -1 */
private data class TagResult(val status: TagStatus, val bytes: Long = -1L)

class StorageShortageException(val estimated: Long, val available: Long) :
    RuntimeException("空间不足:需约 ${estimated / MB}MB,仅剩 ${available / MB}MB") {

    companion object {
        private const val MB = 1024 * 1024L
    }
}

class SyncEngine(
    private val context: Context,
    private val api: NcmApi,
    private val cookieStore: CookieStore,
    private val settingsStore: SettingsStore,
    private val playlistDao: PlaylistDao,
    private val songDao: SongDao,
    private val playlistSongDao: PlaylistSongDao,
    private val mediaStoreWriter: MediaStoreWriter,
    /** 正在播放的歌曲:同步删除时豁免它 */
    private val playingSongId: () -> Long? = { null },
) {
    /** 远端↔本地歌单/曲目的缓存入口,与浏览层共用同一实现 */
    private val cache = PlaylistCache(api, playlistDao, songDao, playlistSongDao)

    enum class Stage { IDLE, REFRESHING, READY, NORMALIZING, DOWNLOADING, TAGGING, DELETING, DONE, FAILED }

    data class Progress(
        val stage: Stage,
        val message: String,
        val total: Int = 0,
        val done: Int = 0,
        /**
         * 本轮 DOWNLOADING 的起始时刻(ms)。
         * 供 UI 计算速率与 ETA;其他阶段为 0(那些阶段没有分母,不显示 ETA)。
         * 有默认值,故所有旧构造调用无需修改。
         */
        val startedAt: Long = 0L,
    )

    data class Diff(
        val toDownload: List<SongEntity>,
        val toDelete: List<SongEntity>,
        val missingUrlCount: Int,
        val estimatedBytes: Long,
        val availableBytes: Long,
    )

    private val _progress = MutableStateFlow(Progress(Stage.IDLE, ""))
    val progress = _progress.asStateFlow()

    private val _lastDiff = MutableStateFlow<Diff?>(null)

    /**
     * 最近一次差量结果,供 UI 预览确认。
     *
     * 必须是可观察的 StateFlow:引擎先发 READY(见 [computeDiff]),差量则是在
     * [refreshAndDiff] 返回之后才赋值的。UI 若只在 READY 那一刻读一次快照,
     * 就有一整个窗口读到 null 而停在歌单页 —— 且之后没有任何事件再触发重算,
     * 表现为「同步完成后不弹预览,得点一下设置(改动别的 UI 状态)再返回才出现」。
     * 改成 StateFlow 后,迟到的差量本身就会再驱动一次页面对齐。
     */
    val lastDiff = _lastDiff.asStateFlow()

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
        val uid = resolveUid()
        if (uid == 0L) throw IllegalStateException("未登录,请重新扫码")
        Diag.i(TAG_ENGINE, "refreshAndDiff 开始, uid=$uid")

        _progress.value = Progress(Stage.REFRESHING, "正在拉取歌单…")
        val remotePlaylists = api.fetchUserPlaylists(uid)
        cache.mergePlaylistTable(remotePlaylists)
        Diag.i(TAG_ENGINE, "远端歌单 ${remotePlaylists.size} 个")

        val enabled = playlistDao.getEnabled()
        if (enabled.isEmpty()) {
            _progress.value = Progress(Stage.READY, "没有勾选的歌单")
            Diag.i(TAG_ENGINE, "没有勾选的歌单,diff 为空")
            return publishDiff(Diff(emptyList(), emptyList(), 0, 0L, availableBytes()))
        }

        songDao.resetStaleDownloading()
        urlCache.clear()

        enabled.forEachIndexed { index, playlist ->
            ensureActive()
            _progress.value = Progress(
                Stage.REFRESHING,
                "正在拉取「${playlist.name}」(${index + 1}/${enabled.size})…",
            )
            cache.loadTracks(playlist.id)
        }

        return publishDiff(computeDiff())
    }

    /** 执行下载与删除 */
    suspend fun execute(diff: Diff) {
        Diag.i(
            TAG_ENGINE,
            "execute: 下载 ${diff.toDownload.size} 首, 删除 ${diff.toDelete.size} 首, " +
                "跳过 ${diff.missingUrlCount} 首",
        )
        // 先把旧版带「{songId}_」前缀的文件名规范化(幂等),再开始下载
        normalizeStage(diff.toDelete.map { it.songId }.toSet())
        if (diff.toDownload.isNotEmpty()) {
            val downloadStartedAt = System.currentTimeMillis()
            _progress.value = Progress(
                Stage.DOWNLOADING,
                "开始下载 ${diff.toDownload.size} 首…",
                diff.toDownload.size,
                0,
                startedAt = downloadStartedAt,
            )
            diff.toDownload.forEachIndexed { index, song ->
                ensureActive()
                _progress.value = Progress(
                    Stage.DOWNLOADING,
                    "${song.artist} - ${song.name}",
                    diff.toDownload.size,
                    index,
                    startedAt = downloadStartedAt,
                )
                downloadSong(song)
            }
        }

        // 存量文件补标签(幂等);待删的不碰
        tagStage(diff.toDelete.map { it.songId }.toSet())

        if (diff.toDelete.isNotEmpty()) {
            _progress.value = Progress(
                Stage.DELETING,
                "正在清理 ${diff.toDelete.size} 首已移出歌单的文件…",
            )
            // 正在播放的那一首已在算差量时豁免(见 planLocalDeletions):
            // 正在听的歌被同步删掉是明确的体验缺陷,而且豁免是自愈的 —— 下一轮不再受保护。
            diff.toDelete.forEach { song ->
                song.localUri?.let { mediaStoreWriter.deleteByUriString(it) }
            }
            // 由"删行"改为"清本地态":未勾选歌单里的歌仍要能浏览、能串流
            songDao.clearLocal(diff.toDelete.map { it.songId })
        }

        // 回收既没有歌单引用、也不是红心的 song 行(浏览缓存不会无限增长)
        songDao.deleteUnreferenced()

        // 孤儿清理:MediaStore 里残留的 WatchMusic 条目,数据库已不跟踪
        val validUris = songDao.getAllIds()
            .let { songDao.getByIds(it) }
            .filter { it.state == SongState.OK.name }
            .mapNotNull { it.localUri }
            .toSet()
        val orphans = mediaStoreWriter.deleteOrphans(validUris)
        Diag.i(TAG_ENGINE, "孤儿清理 $orphans 项")

        _progress.value = Progress(Stage.DONE, "同步完成,清理孤儿 $orphans 项")
    }

    /** 一把梭:刷新 + 差量 + 执行 */
    suspend fun runSync() {
        val diff = refreshAndDiff()
        execute(diff)
    }

    /** 仅拉取用户歌单列表(不跑 diff),登录后自动刷新用;返回歌单数 */
    suspend fun refreshPlaylistsOnly(): Int {
        _progress.value = Progress(Stage.REFRESHING, "正在拉取歌单…")
        try {
            val uid = resolveUid()
            if (uid == 0L) throw IllegalStateException("未登录,请重新扫码")
            val remotePlaylists = api.fetchUserPlaylists(uid)
            cache.mergePlaylistTable(remotePlaylists)
            _progress.value = Progress(Stage.IDLE, "")
            Diag.i(TAG_ENGINE, "refreshPlaylistsOnly ok, ${remotePlaylists.size} 个")
            return remotePlaylists.size
        } catch (e: Exception) {
            // 失败也要复位进度,否则 UI 一直停在"拉取中"转圈
            if (e is kotlinx.coroutines.CancellationException) throw e
            _progress.value = Progress(Stage.IDLE, "")
            throw e
        }
    }

    private suspend fun resolveUid(): Long {
        val stored = cookieStore.uidState.value
        if (stored != 0L) return stored
        val uid = api.fetchAccount()?.id ?: 0L
        // 扫码登录常拿不到 uid:兜底取到后回写,供红心列表/加歌单等按 uid 判断的模块使用
        if (uid != 0L) cookieStore.setUid(uid)
        return uid
    }

    /** 供服务在异常时把失败状态推进度流 */
    fun emitError(message: String) {
        Diag.e(TAG_ENGINE, "emitError: $message")
        _progress.value = Progress(Stage.FAILED, message)
    }

    /** 预览页返回/开始新一轮同步时调用:丢弃上一轮差量 */
    fun clearDiff() {
        _lastDiff.value = null
    }

    /** 预览页返回时调用,回到空闲态 */
    fun clearPreview() {
        clearDiff()
        _progress.value = Progress(Stage.IDLE, "")
    }

    /**
     * 发布差量,保证「算出来的差量」与「UI 观察到的差量」不会脱节 ——
     * 调用方(服务)不再需要自己赋值,也就不会忘。
     */
    private fun publishDiff(diff: Diff): Diff {
        _lastDiff.value = diff
        return diff
    }


    private suspend fun computeDiff(): Diff {
        _progress.value = Progress(Stage.REFRESHING, "正在比对本地与歌单…")
        val remoteIds = playlistSongDao.enabledPlaylistSongIds().toHashSet()
        // 文件删除集合:本地有文件、但已不在任何勾选歌单里的歌。
        //
        // 这里**只看有没有本地文件**,而不是"所有 song 行"—— 浏览过的未勾选歌单会
        // 留下元数据缓存(未下载),那些行不该每轮同步都被判成待删。
        // 磁盘上的文件集合因此仍严格等于"已勾选歌单的并集",语义与改造前逐字一致。
        // 传全部 song 行进去:"只看有本地文件的"这条不变量由 planLocalDeletions 自己把关
        val allSongs = songDao.getByIds(songDao.getAllIds())
        val toDelete = planLocalDeletions(allSongs, remoteIds, playingSongId())

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
                    urlCache[song.songId] = urlDto
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
        Diag.i(
            TAG_ENGINE,
            "diff: 待下载=${withUrl.size}, 待删除=${toDelete.size}, " +
                "缺失url=${missingUrlCount}, 预估=${estimated / MB}MB, 可用=${available / MB}MB",
        )
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
            Diag.w(TAG_ENGINE, "下载跳过 ${song.songId} 缺url/试听")
            return
        }

        val displayName = FileNamePolicy.build(song.songId, song.name, song.artist, urlDto.type)
        Diag.i(TAG_ENGINE, "开始下载 ${song.songId} ${displayName}, 期望 ${urlDto.size}B")
        val mediaUri = mediaStoreWriter.insertPending(
            displayName = displayName,
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
                    val result = downloader.download(urlDto.url, output) { written ->
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
                    // 标签必须在 markDone 之前写:此时条目还是 IS_PENDING,外部播放器读不到半成品
                    val tagResult = tagOne(mediaUri, displayName.substringAfterLast('.', ""), song.name, song.artist)
                    val finalSize = if (tagResult.status == TagStatus.WRITTEN && tagResult.bytes > 0) {
                        tagResult.bytes
                    } else {
                        result.bytes
                    }
                    mediaStoreWriter.markDone(mediaUri, finalSize)
                    songDao.updateLocalResult(
                        songId = song.songId,
                        state = SongState.OK,
                        localUri = mediaUri.toString(),
                        size = finalSize,
                        md5 = result.md5 ?: urlDto.md5,
                        br = urlDto.br,
                        type = urlDto.type,
                    )
                    return
                } ?: throw FileNotFoundException("无法打开 MediaStore 输出流")
            } catch (e: Exception) {
                if (attempt == MAX_ATTEMPTS - 1) {
                    Log.e(TAG, "download failed: ${song.songId}", e)
                    Diag.e(TAG_ENGINE, "下载失败 ${song.songId}:${e.message}", e)
                    mediaStoreWriter.delete(mediaUri)
                    songDao.updateState(
                        song.songId,
                        SongState.FAILED,
                        e.javaClass.simpleName + ":" + (e.message ?: ""),
                    )
                } else {
                    Diag.w(TAG_ENGINE, "下载重试 ${song.songId} attempt=${attempt + 1}:${e.message}")
                    delay(RETRY_BACKOFF_MS shl attempt)
                }
            }
        }
    }

    /**
     * 旧版文件名带「{songId}_」前缀,此阶段把已下载歌曲的 MediaStore 文件名
     * 规范为 FileNamePolicy 格式(「歌名 - 歌手.ext」)。已是新格式的跳过,幂等;
     * 待删除的歌曲跳过(马上要删的没必要改名)。
     */
    private suspend fun normalizeStage(excludeIds: Set<Long>) {
        val candidates = songDao.getAllIds().let { songDao.getByIds(it) }
            .filter {
                it.state == SongState.OK.name &&
                    !it.localUri.isNullOrEmpty() &&
                    it.songId !in excludeIds
            }
        if (candidates.isEmpty()) return

        _progress.value = Progress(Stage.NORMALIZING, "规范文件名…")
        val uriToSong = candidates.associateBy { it.localUri!! }
        val currentNames = mediaStoreWriter.displayNameByUris(uriToSong.keys)
        var renamed = 0
        currentNames.forEach { (uriString, current) ->
            ensureActive()
            val song = uriToSong[uriString] ?: return@forEach
            val ext = current.substringAfterLast('.', "").ifEmpty { song.type }
            val target = FileNamePolicy.build(song.songId, song.name, song.artist, ext)
            if (current != target) {
                if (mediaStoreWriter.rename(Uri.parse(uriString), target)) {
                    renamed++
                } else {
                    Diag.w(TAG_ENGINE, "重命名失败 ${song.songId}: $current → $target")
                }
            }
        }
        Diag.i(TAG_ENGINE, "文件名规范化:重命名 $renamed/${currentNames.size} 项")
    }

    /**
     * 给单个已下载文件补写歌名/歌手标签。幂等:已是目标值就只读头部、不写盘。
     * 需要写盘时经临时文件重写,失败不会破坏原文件。
     */
    private suspend fun tagOne(uri: Uri, ext: String, name: String, artist: String): TagResult =
        withContext(Dispatchers.IO) {
            if (!AudioTagWriter.supports(ext)) return@withContext TagResult(TagStatus.UNSUPPORTED)
            // 探测失败 = 容器无法安全解析:不动文件,也不反复重试
            val existing = mediaStoreWriter.openRead(uri)?.use { AudioTagWriter.probe(ext, it) }
                ?: return@withContext TagResult(TagStatus.FAILED)
            if (existing.title == name && existing.artist == artist) {
                return@withContext TagResult(TagStatus.UP_TO_DATE)
            }
            val bytes = runCatching {
                mediaStoreWriter.rewrite(uri) { source, sink ->
                    AudioTagWriter.retag(ext, name, artist, source, sink)
                }
            }.getOrElse { e ->
                Diag.w(TAG_ENGINE, "标签写入失败 $uri:${e.message}")
                return@withContext TagResult(TagStatus.FAILED)
            }
            if (bytes <= 0) TagResult(TagStatus.FAILED) else TagResult(TagStatus.WRITTEN, bytes)
        }

    /**
     * 存量文件补标签阶段:幂等,已是目标值的不写盘。
     * 只处理 mp3/flac;待删除的文件跳过(马上要删的没必要写)。
     */
    private suspend fun tagStage(excludeIds: Set<Long>) {
        val candidates = songDao.getAllIds().let { songDao.getByIds(it) }
            .filter {
                it.state == SongState.OK.name &&
                    !it.localUri.isNullOrEmpty() &&
                    it.songId !in excludeIds
            }
        if (candidates.isEmpty()) return

        _progress.value = Progress(Stage.TAGGING, "填充歌曲信息…", candidates.size, 0)
        val uriToSong = candidates.associateBy { it.localUri!! }
        val names = mediaStoreWriter.displayNameByUris(uriToSong.keys)
        var written = 0
        var upToDate = 0
        var unsupported = 0
        var failed = 0
        var done = 0
        names.forEach { (uriString, current) ->
            ensureActive()
            done++
            val song = uriToSong[uriString] ?: return@forEach
            _progress.value = _progress.value.copy(
                message = "${song.artist} - ${song.name}",
                done = done,
            )
            val ext = current.substringAfterLast('.', "").ifEmpty { song.type ?: "" }
            when (tagOne(Uri.parse(uriString), ext, song.name, song.artist).status) {
                TagStatus.WRITTEN -> written++
                TagStatus.UP_TO_DATE -> upToDate++
                TagStatus.UNSUPPORTED -> unsupported++
                TagStatus.FAILED -> failed++
            }
        }
        Diag.i(
            TAG_ENGINE,
            "标签填充:写入 $written,已是最新 $upToDate,跳过 $unsupported,失败 $failed / ${names.size}",
        )
    }

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
        private const val TAG_ENGINE = "SyncEngine"
        private const val MB = 1024 * 1024L
        private const val KB = 1024L
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MS = 1000L
        private const val PROGRESS_EMIT_STEP = 200 * KB
        private const val SPACE_MARGIN = 0.95 // 可用空间需覆盖 95% 的预估占用才放行
    }
}
