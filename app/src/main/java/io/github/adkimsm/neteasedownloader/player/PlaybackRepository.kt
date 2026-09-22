package io.github.adkimsm.neteasedownloader.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.adkimsm.neteasedownloader.data.SongDao
import io.github.adkimsm.neteasedownloader.data.SongEntity
import io.github.adkimsm.neteasedownloader.library.PlaybackControl
import io.github.adkimsm.neteasedownloader.diag.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/** 播放失败分类,供 UI 映射成中文短句 */
enum class PlaybackError { NO_URL, FILE_GONE, NETWORK, UNKNOWN }

/**
 * 播放器对外状态。
 *
 * **刻意不含标题/歌手/是否在线** —— 那些按 `songId` 查本地库即可得到,
 * 放在这里就会出现两个真相来源(曲目行显示本地、播放页显示在线)。
 */
data class PlaybackState(
    val songId: Long? = null,
    val queueIndex: Int = 0,
    val queueSize: Int = 0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val repeat: Repeat = Repeat.OFF,
    val shuffle: Boolean = false,
    val error: PlaybackError? = null,
)

/**
 * 播放控制入口(UI 只跟它打交道,不直接碰 MediaController)。
 *
 * - 连接是**异步且懒**的:Activity 起来时不启动服务,只有真的要播/要操作时才连;
 * - 连接完成前发起的操作会排队,连上后按序执行(避免"第一次点播放没反应");
 * - 位置用 500ms 轮询,**只在播放中**轮询,暂停即停(手表省电);
 * - 队列与位置持久化到 [QueueStore],进程被杀后可恢复。
 */
class PlaybackRepository(
    private val appContext: Context,
    private val songDao: SongDao,
    private val queueStore: QueueStore,
    private val source: NcmPlaybackSource,
    private val resolver: LocalFirstResolver,
    private val scope: CoroutineScope,
) : PlaybackControl {

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _queue = MutableStateFlow<List<Long>>(emptyList())
    val queue: StateFlow<List<Long>> = _queue.asStateFlow()

    @Volatile
    private var controller: MediaController? = null
    private var connecting = false
    private var restoreAttempted = false
    private var tickJob: Job? = null
    private var lastPersistAt = 0L

    /** 最近一次组队的来源歌单(仅用于快照记录) */
    private var lastSourcePlaylistId: Long? = null

    /** 连接建立前排队等待执行的操作 */
    private val pendingOps = CopyOnWriteArrayList<(MediaController) -> Unit>()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFromController()
            persistIfDue(force = false)
        }

        override fun onPlayerError(error: PlaybackException) {
            handlePlayerError(error)
        }
    }

    // ---------- 连接 ----------

    /** 幂等;连上后回调在主线程执行 */
    fun ensureConnected(then: (() -> Unit)? = null) {
        if (controller != null) {
            then?.invoke()
            return
        }
        if (then != null) pendingOps += { _ -> then() }
        if (connecting) return
        connecting = true

        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        future.addListener(
            {
                connecting = false
                runCatching { future.get() }
                    .onSuccess { c ->
                        controller = c
                        c.addListener(listener)
                        syncFromController()
                        drainPending(c)
                        restoreQueueIfNeeded(c)
                        Diag.i(TAG, "MediaController 已连接")
                    }
                    .onFailure { Diag.e(TAG, "MediaController 连接失败", it) }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    private fun drainPending(c: MediaController) {
        val ops = pendingOps.toList()
        pendingOps.clear()
        ops.forEach { runCatching { it(c) }.onFailure { Diag.w(TAG, "排队操作失败:${it.message}") } }
    }

    private fun onController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) {
            block(c)
            return
        }
        pendingOps += block
        ensureConnected()
    }

    // ---------- 播放控制 ----------

    /**
     * 用给定歌曲替换队列并从 [startIndex] 开始播。
     * [sourcePlaylistId] 只用于恢复时的来源记录。
     */
    fun play(songs: List<SongEntity>, startIndex: Int, sourcePlaylistId: Long? = null) {
        if (songs.isEmpty()) return
        val index = startIndex.coerceIn(0, songs.lastIndex)
        onController { c ->
            val items = songs.map(::toMediaItem)
            lastSourcePlaylistId = sourcePlaylistId
            c.setMediaItems(items, index, 0L)
            c.prepare()
            c.play()
            syncFromController()
            persist(force = true, sourcePlaylistId = sourcePlaylistId)
        }
    }
    fun togglePlayPause() = onController { c ->
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    /**
     * 手动"下一首"。
     *
     * 单曲循环下 `seekToNextMediaItem()` 会重播当前曲(它遵循 repeatMode),
     * 而手动切歌的预期是前进 —— 所以先临时把 repeatMode 置 OFF,切完再恢复。
     */
    fun skipNext() = onController { c ->
        val repeat = c.repeatMode.toRepeat()
        if (needsRepeatBypassForManualSkip(repeat)) {
            c.repeatMode = Player.REPEAT_MODE_OFF
            c.seekToNextMediaItem()
            c.repeatMode = Player.REPEAT_MODE_ONE
        } else {
            c.seekToNextMediaItem()
        }
    }

    /** 手动"上一首"。同样绕开单曲循环;首曲时由 ExoPlayer 重播当前曲。 */
    fun skipPrevious() = onController { c ->
        val repeat = c.repeatMode.toRepeat()
        if (needsRepeatBypassForManualSkip(repeat)) {
            c.repeatMode = Player.REPEAT_MODE_OFF
            c.seekToPreviousMediaItem()
            c.repeatMode = Player.REPEAT_MODE_ONE
        } else {
            c.seekToPreviousMediaItem()
        }
    }

    fun seekTo(positionMs: Long) = onController { c ->
        c.seekTo(positionMs.coerceAtLeast(0L))
        syncFromController()
    }

    fun cycleRepeat() = onController { c ->
        c.repeatMode = nextRepeat(c.repeatMode.toRepeat()).toPlayerRepeat()
        syncFromController()
    }

    fun toggleShuffle() = onController { c ->
        c.shuffleModeEnabled = !c.shuffleModeEnabled
        syncFromController()
    }

    fun playAt(index: Int) = onController { c ->
        if (index in 0 until c.mediaItemCount) {
            c.seekTo(index, 0L)
            c.play()
        }
    }

    /** 从队列里移除一首(删除歌曲时也要调用)。返回是否命中。 */
    override fun removeFromQueue(songId: Long) = onController { c ->
        for (i in 0 until c.mediaItemCount) {
            if (c.getMediaItemAt(i).mediaId == songId.toString()) {
                c.removeMediaItem(i)
                return@onController
            }
        }
    }

    /** 正在播这首歌时:停下并跳到下一首(删除歌曲前调用) */
    override fun stopIfPlaying(songId: Long) = onController { c ->
        val current = c.currentMediaItem?.mediaId?.toLongOrNull()
        if (current == songId) {
            if (c.mediaItemCount > 1) c.seekToNextMediaItem() else c.stop()
        }
        removeFromQueue(songId)
    }

    override fun currentSongIdOrNull(): Long? = _state.value.songId

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    // ---------- 状态同步 ----------

    private fun syncFromController() {
        val c = controller ?: return

        val ids = (0 until c.mediaItemCount).mapNotNull { c.getMediaItemAt(it).mediaId.toLongOrNull() }
        if (ids != _queue.value) _queue.value = ids

        val duration = c.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET && it > 0 } ?: 0L
        _state.value = _state.value.copy(
            songId = c.currentMediaItem?.mediaId?.toLongOrNull(),
            queueIndex = c.currentMediaItemIndex.coerceAtLeast(0),
            queueSize = c.mediaItemCount,
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            positionMs = c.currentPosition.coerceAtLeast(0L),
            durationMs = duration,
            repeat = c.repeatMode.toRepeat(),
            shuffle = c.shuffleModeEnabled,
        )

        if (c.isPlaying) startTicking() else stopTicking()
    }

    private fun startTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                delay(POSITION_POLL_MS)
                val c = controller ?: break
                if (!c.isPlaying) break
                _state.value = _state.value.copy(
                    positionMs = c.currentPosition.coerceAtLeast(0L),
                    durationMs = c.duration.takeIf {
                        it != androidx.media3.common.C.TIME_UNSET && it > 0
                    } ?: 0L,
                )
                persistIfDue(force = false)
            }
        }
    }

    private fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
    }

    private fun handlePlayerError(error: PlaybackException) {
        val songId = _state.value.songId
        val mapped = when {
            error.cause is StreamUnavailableException -> PlaybackError.NO_URL
            error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> PlaybackError.FILE_GONE
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> PlaybackError.NETWORK
            else -> PlaybackError.UNKNOWN
        }
        Diag.w(TAG, "播放失败 songId=$songId code=${error.errorCode} 归类=$mapped 原因=${error.message}")
        _state.value = _state.value.copy(error = mapped)

        if (mapped == PlaybackError.FILE_GONE && songId != null) {
            // 本地文件被删了(同步清理/用户删除):清本地态并重试一次,自动降级为串流
            scope.launch {
                runCatching {
                    songDao.clearLocal(listOf(songId))
                    resolver.invalidate(songId)
                    source.invalidate(songId)
                    retryCurrent()
                }.onFailure { Diag.w(TAG, "本地文件失效降级失败:${it.message}") }
            }
        }
    }

    private fun retryCurrent() {
        onController { c ->
            val position = _state.value.positionMs
            c.prepare()
            if (position > 0) c.seekTo(position)
            c.play()
        }
    }

    // ---------- 持久化与恢复 ----------

    private fun persistIfDue(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistAt < PERSIST_INTERVAL_MS) return
        persist(force = force, sourcePlaylistId = null)
    }

    private fun persist(force: Boolean, sourcePlaylistId: Long?) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        lastPersistAt = System.currentTimeMillis()
        val snapshot = PlaybackSnapshot(
            songIds = _queue.value,
            index = c.currentMediaItemIndex.coerceAtLeast(0),
            positionMs = c.currentPosition.coerceAtLeast(0L),
            repeat = c.repeatMode.toRepeat().name,
            shuffle = c.shuffleModeEnabled,
            sourcePlaylistId = sourcePlaylistId ?: lastSourcePlaylistId,
            savedAt = System.currentTimeMillis(),
        )
        scope.launch { queueStore.save(snapshot) }
    }

    /** App 退到后台时落盘一次,避免只靠轮询间隔丢失最后位置 */
    fun persistNow() = persist(force = true, sourcePlaylistId = null)

    private fun restoreQueueIfNeeded(c: MediaController) {
        if (restoreAttempted) return
        restoreAttempted = true
        if (c.mediaItemCount > 0) return

        scope.launch {
            val snapshot = queueStore.load()
            if (snapshot.isEmpty) return@launch

            val songs = songDao.getByIds(snapshot.songIds).associateBy { it.songId }
            val restored = restoreQueue(snapshot, songs.keys) ?: return@launch
            val items = restored.songIds.mapNotNull { songs[it] }.map(::toMediaItem)
            if (items.isEmpty()) return@launch

            onController { cc ->
                if (cc.mediaItemCount > 0) return@onController
                cc.repeatMode = repeatFrom(snapshot.repeat).toPlayerRepeat()
                cc.shuffleModeEnabled = snapshot.shuffle
                // 不自动播放:只恢复到"准备好了",是否继续由用户决定
                cc.setMediaItems(items, restored.index, restored.positionMs)
                cc.prepare()
                syncFromController()
            }
            Diag.i(TAG, "已恢复播放队列 ${items.size} 首,续播位置=${restored.positionMs}ms")
        }
    }

    // ---------- 工具 ----------

    private fun toMediaItem(song: SongEntity): MediaItem = MediaItem.Builder()
        .setMediaId(song.songId.toString())
        .setUri(PlaybackUri.forSong(song.songId))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(song.name)
                .setArtist(song.artist)
                .setAlbumTitle(song.album)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
        )
        .build()

    private companion object {
        const val TAG = "PlaybackRepository"

        /** 播放中的位置轮询间隔。手表上再密就是白耗电,再疏进度条会顿。 */
        const val POSITION_POLL_MS = 500L

        /** 位置落盘间隔:只在播放中按这个节奏写一次 DataStore */
        const val PERSIST_INTERVAL_MS = 10_000L
    }
}

internal fun Int.toRepeat(): Repeat = when (this) {
    Player.REPEAT_MODE_ONE -> Repeat.ONE
    Player.REPEAT_MODE_ALL -> Repeat.ALL
    else -> Repeat.OFF
}

internal fun Repeat.toPlayerRepeat(): Int = when (this) {
    Repeat.ONE -> Player.REPEAT_MODE_ONE
    Repeat.ALL -> Player.REPEAT_MODE_ALL
    Repeat.OFF -> Player.REPEAT_MODE_OFF
}
