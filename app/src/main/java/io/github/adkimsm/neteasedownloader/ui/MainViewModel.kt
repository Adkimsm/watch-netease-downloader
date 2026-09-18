package io.github.adkimsm.neteasedownloader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.adkimsm.neteasedownloader.App
import io.github.adkimsm.neteasedownloader.data.PlaylistEntity
import io.github.adkimsm.neteasedownloader.sync.SyncEngine
import io.github.adkimsm.neteasedownloader.sync.SyncService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

    val lastDiff: SyncEngine.Diff? get() = appRef.syncEngine.lastDiff

    enum class Screen { LOGIN, PLAYLISTS, PREVIEW, SYNCING, SETTINGS }

    val screen = combine(loggedIn, progress, settingsOpen) { musicU, p, settings ->
        val diff = appRef.syncEngine.lastDiff
        when {
            musicU.isEmpty() -> Screen.LOGIN
            settings -> Screen.SETTINGS
            p.stage == SyncEngine.Stage.DOWNLOADING ||
                p.stage == SyncEngine.Stage.DELETING -> Screen.SYNCING

            p.stage == SyncEngine.Stage.READY &&
                (diff?.toDownload?.isNotEmpty() == true ||
                    diff?.toDelete?.isNotEmpty() == true) -> Screen.PREVIEW

            else -> Screen.PLAYLISTS
        }
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, Screen.LOGIN)

    init {
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
        viewModelScope.launch {
            appRef.playlistDao.setEnabled(playlist.id, enabled)
            refreshPlaylists()
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

    fun setLevel(level: String) {
        viewModelScope.launch { appRef.settingsStore.setLevel(level) }
    }

    fun logout() {
        viewModelScope.launch { appRef.cookieStore.clear() }
    }
}
