package io.github.adkimsm.neteasedownloader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.adkimsm.neteasedownloader.App
import io.github.adkimsm.neteasedownloader.R
import io.github.adkimsm.neteasedownloader.data.PlaylistEntity
import io.github.adkimsm.neteasedownloader.data.isLikedPlaylistId
import io.github.adkimsm.neteasedownloader.data.isOwnedBy
import io.github.adkimsm.neteasedownloader.library.AddTarget
import io.github.adkimsm.neteasedownloader.library.RemoveOutcome
import io.github.adkimsm.neteasedownloader.library.addToPlaylistTargets
import io.github.adkimsm.neteasedownloader.library.RemoveReport
import io.github.adkimsm.neteasedownloader.library.RemoveScope
import io.github.adkimsm.neteasedownloader.library.RemoveSelection
import io.github.adkimsm.neteasedownloader.library.SongPresence
import io.github.adkimsm.neteasedownloader.library.defaultSelection
import io.github.adkimsm.neteasedownloader.library.planFor
import io.github.adkimsm.neteasedownloader.library.undoPlanFor
import io.github.adkimsm.neteasedownloader.diag.Diag
import io.github.adkimsm.neteasedownloader.sync.SyncEngine
import io.github.adkimsm.neteasedownloader.sync.SyncService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 界面根 ViewModel:导航栈 + 歌单列表 + 同步动作 + 设置/诊断。
 *
 * 页面数据(曲目列表、播放态)分别由 [PlaylistDetailViewModel]、[PlayerViewModel] 负责,
 * 避免这里膨胀成一个什么都知道的巨型 VM。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val appRef = app as App

    val loggedIn = appRef.cookieStore.musicUState
    val level = appRef.settingsStore.level
    val streamLevel = appRef.settingsStore.streamLevel
    val progress = appRef.syncEngine.progress

    // 灰色歌曲解锁:下载与在线播放各自一个开关,音源与地区解锁各一个
    val unlockDownload = appRef.settingsStore.unlockDownload
    val unlockStream = appRef.settingsStore.unlockStream
    val providerKuwo = appRef.settingsStore.providerKuwo
    val providerKugou = appRef.settingsStore.providerKugou
    val spoofRealIp = appRef.settingsStore.spoofRealIp

    private val _playlists = MutableStateFlow<List<PlaylistEntity>>(emptyList())
    val playlists = _playlists.asStateFlow()

    /** 导航栈。栈空 = 根页面(歌单列表)。 */
    private val _stack = MutableStateFlow<List<Dest>>(emptyList())
    val stack = _stack.asStateFlow()

    // ---- UI 级等待状态 ----
    // 这些属于瞬时交互(写库/写设置/清 cookie),不放进 SyncEngine —— 引擎的 Progress
    // 是跨进程保活的进度,不该被这些短操作污染。

    /** 歌单列表加载中(初次拉取 / 刷新) */
    private val _playlistsLoading = MutableStateFlow(false)
    val playlistsLoading = _playlistsLoading.asStateFlow()

    /** 正在写库的勾选行 id 集合,用于该行内联 loading */
    private val _pendingToggleIds = MutableStateFlow<Set<Long>>(emptySet())
    val pendingToggleIds = _pendingToggleIds.asStateFlow()

    /** 正在进行的设置类动作:"logout" / "level" / "streamLevel" */
    private val _actionInFlight = MutableStateFlow<String?>(null)
    val actionInFlight = _actionInFlight.asStateFlow()

    /** 一次性错误提示 */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    /** 音质切换后的短暂确认反馈 */
    private val _levelJustChanged = MutableStateFlow(false)
    val levelJustChanged = _levelJustChanged.asStateFlow()

    /**
     * 一次路由结果:页面 + 该页面要用的差量 + 是否显示 mini 播放条。
     *
     * 页面与差量必须同源产出 —— 否则预览页可能在差量还没读到时就先渲染出来(白屏)。
     */
    /** 删除流程的界面状态 */
    data class DeleteState(
        val presence: SongPresence? = null,
        val selection: RemoveSelection = RemoveSelection(),
        val loading: Boolean = false,
        val inFlight: Boolean = false,
        val banner: DeleteBanner? = null,
    )

    /** 删除后的结果条(3 秒内可撤销) */
    data class DeleteBanner(
        val songId: Long,
        val songName: String,
        val outcome: RemoveOutcome,
        val report: RemoveReport,
    )

    data class UiState(
        val dest: Dest,
        val diff: SyncEngine.Diff?,
        val showMiniPlayer: Boolean,
    )

    companion object {
        const val ACTION_LOGOUT = "logout"
        private const val TAG = "MainViewModel"
        const val ACTION_REMOTE = "remote"
        const val ACTION_LEVEL = "level"
        const val ACTION_STREAM_LEVEL = "streamLevel"
        private const val LEVEL_FEEDBACK_MS = 900L

        /**
         * 「正在忙」的阶段:这些阶段进进度屏,歌单页的同步按钮也据此转圈。
         * FAILED 刻意不在其中 —— 失败走歌单页顶部的错误条。
         */
        val ACTIVE_STAGES = setOf(
            SyncEngine.Stage.REFRESHING,
            SyncEngine.Stage.DOWNLOADING,
            SyncEngine.Stage.DELETING,
            SyncEngine.Stage.TAGGING,
            SyncEngine.Stage.NORMALIZING,
        )
    }

    /**
     * 当前页面。路由规则见 [routeDest](纯函数,已单测)。
     *
     * 播放态必须是 combine 的输入而非临时读快照:媒体通知里切歌、播放结束都会让
     * mini 播放条自行更新,不需要别的 UI 事件来"顺带"重算一次。
     */
    val uiState: StateFlow<UiState> = routeUiState(
        loggedIn = loggedIn,
        progress = progress,
        lastDiff = appRef.syncEngine.lastDiff,
        stack = stack,
        playerActive = appRef.playbackRepository.state.map { it.songId != null },
    ).stateIn(viewModelScope, SharingStarted.Eagerly, UiState(Dest.Login, null, false))

    init {
        // 首次登录成功后自动拉取歌单列表(只拉列表,不跑 diff),避免歌单页空转
        viewModelScope.launch {
            loggedIn.first { it.isNotEmpty() }
            _playlistsLoading.value = true
            runCatching {
                appRef.syncEngine.refreshPlaylistsOnly()
            }.onFailure { e ->
                Diag.e("MainViewModel", "自动拉取歌单失败", e)
                _errorMessage.value = e.message
                    ?: getApplication<Application>().getString(R.string.error_refresh_failed)
            }
            refreshPlaylists()
            _playlistsLoading.value = false
        }
        // 登录态变化时刷新歌单列表;退登时清空导航栈(否则会停在上一个账号的页面)
        viewModelScope.launch {
            loggedIn.collect { musicU ->
                if (musicU.isEmpty()) {
                    _stack.value = emptyList()
                } else {
                    refreshPlaylists()
                    refreshLikes()
                }
            }
        }
        viewModelScope.launch {
            progress.collect { p ->
                if (p.stage == SyncEngine.Stage.DONE) refreshPlaylists()
            }
        }
        // 应用起来就把控制器连上:媒体通知里的"继续播放"与 mini 播放条都依赖它
        appRef.playbackRepository.ensureConnected()
    }

    suspend fun refreshPlaylists() {
        _playlists.value = appRef.playlistDao.getAll()
    }

    // ---------- 导航 ----------

    fun push(dest: Dest) {
        _stack.update { if (it.lastOrNull() == dest) it else it + dest }
    }

    fun pop() {
        _stack.update { if (it.isEmpty()) it else it.dropLast(1) }
    }

    fun popToRoot() {
        _stack.value = emptyList()
    }

    fun openPlaylistDetail(playlistId: Long) = push(Dest.PlaylistDetail(playlistId))
    fun openNowPlaying() = push(Dest.NowPlaying)
    fun openQueue() = push(Dest.Queue)
    fun openSongActions(songId: Long) = push(Dest.SongActions(songId))
    fun openSettings() = push(Dest.Settings)
    fun openDiagnostics() = push(Dest.Diagnostics)

    // ---------- 同步 ----------

    fun togglePlaylist(playlist: PlaylistEntity, enabled: Boolean) {
        if (playlist.id in _pendingToggleIds.value) return
        viewModelScope.launch {
            _pendingToggleIds.value = _pendingToggleIds.value + playlist.id
            try {
                appRef.playlistDao.setEnabled(playlist.id, enabled)
                refreshPlaylists()
            } catch (e: Exception) {
                Diag.e("MainViewModel", "切换歌单勾选失败", e)
                _errorMessage.value = e.message
                    ?: getApplication<Application>().getString(R.string.error_operation_failed)
            } finally {
                _pendingToggleIds.value = _pendingToggleIds.value - playlist.id
            }
        }
    }

    fun startSync() {
        appRef.syncEngine.clearDiff()
        SyncService.refresh(appRef)
    }

    fun confirmSync() {
        SyncService.execute(appRef)
    }

    fun stopSync() {
        SyncService.stop(appRef)
    }

    fun discardPreview() {
        appRef.syncEngine.clearPreview()
    }

    fun clearDiagnostics() {
        io.github.adkimsm.neteasedownloader.diag.Diag.clearMemory()
    }

    fun setLevel(level: String) {
        viewModelScope.launch {
            _actionInFlight.value = ACTION_LEVEL
            try {
                appRef.settingsStore.setLevel(level)
                _levelJustChanged.value = true
                kotlinx.coroutines.delay(LEVEL_FEEDBACK_MS)
                _levelJustChanged.value = false
            } finally {
                _actionInFlight.value = null
            }
        }
    }

    fun setStreamLevel(level: String) {
        viewModelScope.launch {
            _actionInFlight.value = ACTION_STREAM_LEVEL
            try {
                appRef.settingsStore.setStreamLevel(level)
                _levelJustChanged.value = true
                kotlinx.coroutines.delay(LEVEL_FEEDBACK_MS)
                _levelJustChanged.value = false
            } finally {
                _actionInFlight.value = null
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            _actionInFlight.value = ACTION_LOGOUT
            try {
                appRef.cookieStore.clear()
            } catch (e: Exception) {
                Diag.e("MainViewModel", "退出登录失败", e)
                _errorMessage.value = e.message
                    ?: getApplication<Application>().getString(R.string.error_logout_failed)
            } finally {
                _actionInFlight.value = null
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
    // ---------- 删除这首歌 ----------

    val removeScope = appRef.settingsStore.removeScope

    private val _delete = MutableStateFlow(DeleteState())
    val delete = _delete.asStateFlow()

    /** 曲库变动计数:详情页据此从缓存重读,不重新拉网络 */
    private val _libraryVersion = MutableStateFlow(0)
    val libraryVersion = _libraryVersion.asStateFlow()

    /**
     * 删除入口。
     *
     * `每次询问` → 先算清"这首歌在哪些歌单里"再进面板;
     * `全部删除` / `只删本地` → **算完直接执行,不弹任何东西**(D8 的"点了就删")。
     */
    fun startRemove(songId: Long) {
        viewModelScope.launch {
            _delete.update { it.copy(loading = true, presence = null, banner = null) }
            val scope = removeScope.value
            val presence = runCatching { appRef.songRemover.presenceOf(songId) }
                .getOrElse { e ->
                    Diag.e(TAG, "读取歌曲归属失败 songId=$songId", e)
                    SongPresence(songId = songId, songName = "")
                }
            _delete.update {
                it.copy(
                    loading = false,
                    presence = presence,
                    selection = defaultSelection(presence),
                )
            }

            if (scope == RemoveScope.ASK) {
                push(Dest.RemoveSong(songId))
            } else {
                executeRemove(presence, planFor(scope, presence))
            }
        }
    }

    /** 面板上确认后调用 */
    fun confirmRemove() {
        val presence = _delete.value.presence ?: return
        val scope = removeScope.value
        val selection = _delete.value.selection
        executeRemove(presence, planFor(scope, presence, selection))
    }


    /** 重试失败的部分:只重发没成功的歌单/红心,不去重删已经删掉的本地文件 */
    fun retryRemove() {
        val banner = _delete.value.banner ?: return
        val remaining = banner.report.remoteFailed.map { it.playlistId } + banner.report.remoteStale
        val retryUnlike = banner.outcome.unlike && banner.report.unlikeOk != true
        if (remaining.isEmpty() && !retryUnlike) {
            dismissDeleteBanner()
            return
        }
        executeRemove(
            presence = SongPresence(banner.songId, banner.songName),
            outcome = banner.outcome.copy(
                remoteTargets = remaining,
                unlike = retryUnlike,
                deleteLocal = false,
            ),
        )
    }


    private fun executeRemove(presence: SongPresence, outcome: RemoveOutcome) {
        viewModelScope.launch {
            _delete.update { it.copy(inFlight = true) }
            val report = runCatching { appRef.songRemover.remove(presence.songId, outcome) }
                .getOrElse { e ->
                    Diag.e(TAG, "删除歌曲失败 songId=${presence.songId}", e)
                    RemoveReport(songId = presence.songId, fileDeleted = false)
                }
            _delete.update {
                it.copy(
                    inFlight = false,
                    banner = DeleteBanner(
                        songId = presence.songId,
                        songName = presence.songName,
                        outcome = outcome,
                        report = report,
                    ),
                )
            }
            _libraryVersion.update { it + 1 }
            // 面板用完就退,返回时看到的是删除后的列表
            if (_stack.value.lastOrNull() == Dest.RemoveSong(presence.songId)) pop()
        }
    }

    fun updateSelection(selection: RemoveSelection) {
        _delete.update { it.copy(selection = selection) }
    }

    fun dismissDeleteBanner() {
        _delete.update { it.copy(banner = null) }
    }

    /** 撤销:把刚移除的歌加回歌单与红心。本地文件等下次同步自然下回。 */
    fun undoRemove() {
        val banner = _delete.value.banner ?: return
        viewModelScope.launch {
            _delete.update { it.copy(banner = null) }
            appRef.songRemover.undo(banner.songId, undoPlanFor(banner.outcome, banner.report))
            _libraryVersion.update { it + 1 }
            refreshPlaylists()
        }
    }

    fun setRemoveScope(scope: RemoveScope) {
        viewModelScope.launch { appRef.settingsStore.setRemoveScope(scope) }
    }

    fun setUnlockDownload(value: Boolean) {
        viewModelScope.launch { appRef.settingsStore.setUnlockDownload(value) }
    }

    fun setUnlockStream(value: Boolean) {
        viewModelScope.launch { appRef.settingsStore.setUnlockStream(value) }
    }

    fun setProviderKuwo(value: Boolean) {
        viewModelScope.launch { appRef.settingsStore.setProviderKuwo(value) }
    }

    fun setProviderKugou(value: Boolean) {
        viewModelScope.launch { appRef.settingsStore.setProviderKugou(value) }
    }

    fun setSpoofRealIp(value: Boolean) {
        viewModelScope.launch { appRef.settingsStore.setSpoofRealIp(value) }
    }

    // ---------- 远端歌单管理与红心 ----------

    /** 正在进行中的远端写操作,供按钮转圈 */
    private val _remoteAction = MutableStateFlow<String?>(null)
    val remoteAction = _remoteAction.asStateFlow()

    /** 「加入歌单」的候选(本人歌单,排除我喜欢的音乐) */
    private val _addTargets = MutableStateFlow<List<AddTarget>>(emptyList())
    val addTargets = _addTargets.asStateFlow()

    /** 红心集合,供二级菜单显示当前状态 */
    private val _likedIds = MutableStateFlow<Set<Long>>(emptySet())
    val likedIds = _likedIds.asStateFlow()

    /** 歌单列表首行「我喜欢的音乐」的曲目数 */
    private val _likedCount = MutableStateFlow(0)
    val likedCount = _likedCount.asStateFlow()

    fun showRemoteError(message: String) {
        _errorMessage.value = message
    }

    fun refreshLikes() {
        viewModelScope.launch {
            val ids = runCatching { appRef.likedSongDao.likedSet() }.getOrDefault(emptySet())
            _likedIds.value = ids
            _likedCount.value = ids.size
        }
    }

    fun loadAddTargets() {
        viewModelScope.launch {
            val uid = appRef.resolveUid()
            _addTargets.value = addToPlaylistTargets(
                appRef.playlistDao.getAll().map { p ->
                    AddTarget(
                        playlistId = p.id,
                        name = p.name,
                        owned = p.isOwnedBy(uid),
                        liked = isLikedPlaylistId(p.id, p.specialType, uid),
                    )
                },
            )
        }
    }

    fun openLikedSongs() = push(Dest.LikedSongs)
    fun openPlaylistMenu(playlistId: Long) = push(Dest.PlaylistMenu(playlistId))
    fun openPlaylistCreate() = push(Dest.PlaylistEdit(null))
    fun openPlaylistEdit(playlistId: Long) = push(Dest.PlaylistEdit(playlistId))
    fun openAddToPlaylist(songId: Long) = push(Dest.AddToPlaylist(songId))

    fun createPlaylist(name: String, onDone: (String?) -> Unit) =
        runRemote(onDone) { appRef.ncmApi.createPlaylist(name) }

    fun renamePlaylist(playlistId: Long, name: String, onDone: (String?) -> Unit) =
        runRemote(onDone) { appRef.ncmApi.renamePlaylist(playlistId, name) }

    fun deletePlaylist(playlistId: Long, onDone: (String?) -> Unit) = runRemote(onDone) {
        appRef.ncmApi.removePlaylists(listOf(playlistId))
        // 本地缓存跟着清,否则歌单页还会列出这个已经不存在的歌单
        appRef.playlistSongDao.deleteByPlaylist(playlistId)
        appRef.playlistDao.delete(playlistId)
    }

    fun addSongToPlaylists(songId: Long, playlistIds: List<Long>, onDone: (String?) -> Unit) =
        runRemote(onDone) {
            playlistIds.forEach { playlistId ->
                appRef.ncmApi.addTracksToPlaylist(playlistId, listOf(songId))
            }
        }

    /**
     * 红心:乐观更新 + 失败翻回。
     *
     * 红心是高频的轻操作,等网络往返再改界面会让按钮"点不动";
     * 而失败翻回是必须的 —— 否则本地显示已红心、服务端其实没有,删除流程里的
     * 「自动取消红心」就会搞错对象。
     */
    fun toggleLike(songId: Long) {
        val target = songId !in _likedIds.value
        _likedIds.value = if (target) _likedIds.value + songId else _likedIds.value - songId
        _likedCount.value = _likedIds.value.size
        viewModelScope.launch {
            val ok = runCatching { appRef.ncmApi.setLiked(songId, target) }.isSuccess
            if (ok) {
                appRef.likedSongDao.setLiked(songId, target)
            } else {
                _likedIds.value = if (target) _likedIds.value - songId else _likedIds.value + songId
                _likedCount.value = _likedIds.value.size
                _errorMessage.value = getApplication<Application>()
                    .getString(R.string.error_operation_failed)
            }
            _libraryVersion.update { it + 1 }
        }
    }

    /** 写操作统一收口:转圈、成功后刷新歌单表、把失败原因交回界面 */
    private fun runRemote(onDone: (String?) -> Unit, block: suspend () -> Unit) {
        viewModelScope.launch {
            _remoteAction.value = ACTION_REMOTE
            val error = runCatching { block() }.exceptionOrNull()?.message
            _remoteAction.value = null
            if (error == null) {
                runCatching { appRef.syncEngine.refreshPlaylistsOnly() }
                refreshPlaylists()
                _libraryVersion.update { it + 1 }
            }
            onDone(error)
        }
    }
}
