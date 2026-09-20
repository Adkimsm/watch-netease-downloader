package io.github.adkimsm.neteasedownloader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.adkimsm.neteasedownloader.App
import io.github.adkimsm.neteasedownloader.R
import io.github.adkimsm.neteasedownloader.data.PlaylistEntity
import io.github.adkimsm.neteasedownloader.diag.Diag
import io.github.adkimsm.neteasedownloader.sync.SyncEngine
import io.github.adkimsm.neteasedownloader.sync.SyncService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val appRef = app as App

    val loggedIn = appRef.cookieStore.musicUState
    val level = appRef.settingsStore.level
    val progress = appRef.syncEngine.progress

    private val _playlists = MutableStateFlow<List<PlaylistEntity>>(emptyList())
    val playlists = _playlists.asStateFlow()

    private val _settingsOpen = MutableStateFlow(false)
    val settingsOpen = _settingsOpen.asStateFlow()

    private val _diagnosticsOpen = MutableStateFlow(false)
    val diagnosticsOpen = _diagnosticsOpen.asStateFlow()

    // ---- UI 级等待状态 ----
    // 这些属于瞬时交互(写库/写设置/清 cookie),不放进 SyncEngine —— 引擎的 Progress 是跨进程保活的进度,
    // 不该被这些短操作污染。

    /** 歌单列表加载中(初次拉取 / 刷新) */
    private val _playlistsLoading = MutableStateFlow(false)
    val playlistsLoading = _playlistsLoading.asStateFlow()

    /** 正在写库的勾选行 id 集合,用于该行内联 loading */
    private val _pendingToggleIds = MutableStateFlow<Set<Long>>(emptySet())
    val pendingToggleIds = _pendingToggleIds.asStateFlow()

    /** 正在进行的设置类动作:"logout" / "level" */
    private val _actionInFlight = MutableStateFlow<String?>(null)
    val actionInFlight = _actionInFlight.asStateFlow()

    /** 一次性错误提示(引擎失败原因原实现无处显示) */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    /** 音质切换后的短暂确认反馈 */
    private val _levelJustChanged = MutableStateFlow(false)
    val levelJustChanged = _levelJustChanged.asStateFlow()

    val lastDiff: SyncEngine.Diff? get() = appRef.syncEngine.lastDiff

    enum class Screen { LOGIN, PLAYLISTS, PREVIEW, SYNCING, SETTINGS, DIAGNOSTICS }

    companion object {
        const val ACTION_LOGOUT = "logout"
        const val ACTION_LEVEL = "level"
        private const val LEVEL_FEEDBACK_MS = 900L
    }

    val screen = combine(loggedIn, progress, settingsOpen, diagnosticsOpen) { musicU, p, settings, diag ->
        val diff = appRef.syncEngine.lastDiff
        when {
            musicU.isEmpty() -> Screen.LOGIN
            diag -> Screen.DIAGNOSTICS
            settings -> Screen.SETTINGS
            // 拉取+差量阶段也进进度屏:大歌单(3742 首)可能持续数分钟,
            // 原先留在歌单页只有底部一个小转圈,提示粒度过粗。
            p.stage == SyncEngine.Stage.REFRESHING ||
                p.stage == SyncEngine.Stage.DOWNLOADING ||
                p.stage == SyncEngine.Stage.DELETING -> Screen.SYNCING

            p.stage == SyncEngine.Stage.READY &&
                (diff?.toDownload?.isNotEmpty() == true ||
                    diff?.toDelete?.isNotEmpty() == true) -> Screen.PREVIEW

            else -> Screen.PLAYLISTS
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Screen.LOGIN)

    init {
        // 首次登录成功后自动拉取歌单列表(只拉列表,不跑 diff),避免歌单页空转
        viewModelScope.launch {
            loggedIn.first { it.isNotEmpty() }
            _playlistsLoading.value = true
            runCatching {
                appRef.syncEngine.refreshPlaylistsOnly()
            }.onFailure { e ->
                Diag.e("MainViewModel", "自动拉取歌单失败", e)
                _errorMessage.value = e.message ?: getApplication<Application>().getString(R.string.error_refresh_failed)
            }
            refreshPlaylists()
            _playlistsLoading.value = false
        }
        // 登录态变化时刷新歌单列表;同步完成后也刷一次
        viewModelScope.launch {
            loggedIn.collect { if (it.isNotEmpty()) refreshPlaylists() }
        }
        viewModelScope.launch {
            progress.collect { p ->
                if (p.stage == SyncEngine.Stage.DONE) refreshPlaylists()
            }
        }
    }

    suspend fun refreshPlaylists() {
        _playlists.value = appRef.playlistDao.getAll()
    }

    fun togglePlaylist(playlist: PlaylistEntity, enabled: Boolean) {
        if (playlist.id in _pendingToggleIds.value) return
        viewModelScope.launch {
            _pendingToggleIds.value = _pendingToggleIds.value + playlist.id
            try {
                appRef.playlistDao.setEnabled(playlist.id, enabled)
                refreshPlaylists()
            } catch (e: Exception) {
                Diag.e("MainViewModel", "切换歌单勾选失败", e)
                _errorMessage.value = e.message ?: getApplication<Application>().getString(R.string.error_operation_failed)
            } finally {
                _pendingToggleIds.value = _pendingToggleIds.value - playlist.id
            }
        }
    }

    fun startSync() {
        appRef.syncEngine.lastDiff = null
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

    fun openSettings() {
        _settingsOpen.value = true
    }

    fun closeSettings() {
        _settingsOpen.value = false
    }

    fun openDiagnostics() {
        _diagnosticsOpen.value = true
    }

    fun closeDiagnostics() {
        _diagnosticsOpen.value = false
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

    fun logout() {
        viewModelScope.launch {
            _actionInFlight.value = ACTION_LOGOUT
            try {
                appRef.cookieStore.clear()
            } catch (e: Exception) {
                Diag.e("MainViewModel", "退出登录失败", e)
                _errorMessage.value = e.message ?: getApplication<Application>().getString(R.string.error_logout_failed)
            } finally {
                _actionInFlight.value = null
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
