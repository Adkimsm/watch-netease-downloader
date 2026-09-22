package io.github.adkimsm.neteasedownloader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.adkimsm.neteasedownloader.App
import io.github.adkimsm.neteasedownloader.data.PlaylistEntity
import io.github.adkimsm.neteasedownloader.data.SongEntity
import io.github.adkimsm.neteasedownloader.diag.Diag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 歌单详情页的曲目列表。
 *
 * 曲目以**远端为准**(点进歌单就拉一次并写库缓存),本地文件状态从同一张 song 表读出来 ——
 * 这样"已下载 / 在线"的角标与播放时真正的选择(from LocalFirstResolver)永远一致。
 *
 * 拉取失败时退回本地缓存而不是清空:断网进歌单至少还能看到上次的结果并播放已下载的歌。
 */
class PlaylistDetailViewModel(
    app: Application,
    private val playlistId: Long,
) : AndroidViewModel(app) {

    private val appRef = app as App

    private val _tracks = MutableStateFlow<List<SongEntity>>(emptyList())
    val tracks = _tracks.asStateFlow()

    private val _playlist = MutableStateFlow<PlaylistEntity?>(null)
    val playlist = _playlist.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                _tracks.value = appRef.playlistCache.loadTracks(playlistId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diag.e(TAG, "拉取歌单 $playlistId 曲目失败", e)
                _tracks.value = cachedTracks()
                _error.value = e.message
            } finally {
                refreshPlaylistRow()
                _loading.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    private suspend fun refreshPlaylistRow() {
        _playlist.value = appRef.playlistDao.getAll().firstOrNull { it.id == playlistId }
    }

    /** 删完之后只从本地缓存重读,不重新拉网络 —— 远端变化已由 SongRemover 写回库里 */
    fun refreshFromCache() {
        viewModelScope.launch { _tracks.value = cachedTracks() }
    }

    private suspend fun cachedTracks(): List<SongEntity> {
        val ids = appRef.playlistSongDao.songIdsForPlaylist(playlistId)
        if (ids.isEmpty()) return emptyList()
        val byId = appRef.songDao.getByIds(ids).associateBy { it.songId }
        return ids.mapNotNull { byId[it] }
    }

    companion object {
        private const val TAG = "PlaylistDetailVM"

        /** 每个歌单一个实例(按 playlistId 作为 key),避免来回切歌单时反复重拉 */
        fun factory(playlistId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                PlaylistDetailViewModel(app, playlistId)
            }
        }
    }
}
