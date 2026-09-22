package io.github.adkimsm.neteasedownloader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.adkimsm.neteasedownloader.App
import io.github.adkimsm.neteasedownloader.data.SongEntity
import io.github.adkimsm.neteasedownloader.diag.Diag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「我喜欢的音乐」。
 *
 * 红心在远端是一个歌单(id == uid),但增删走 `radio/like` 而不是歌单曲目接口,
 * 所以这里也单独一份:先按 liked_song 的顺序取出 id,再补齐缺失的元数据。
 *
 * 拉不到红心列表时退回本地缓存 —— 断网至少还能看到上一次的红心,而不是一个空列表。
 */
class LikedSongsViewModel(app: Application) : AndroidViewModel(app) {

    private val appRef = app as App

    private val _tracks = MutableStateFlow<List<SongEntity>>(emptyList())
    val tracks = _tracks.asStateFlow()

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
                val uid = appRef.cookieStore.uidState.value
                if (uid != 0L) {
                    appRef.likedSongDao.replaceAll(appRef.ncmApi.fetchLikedSongIds(uid))
                }
                _tracks.value = orderedTracks()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diag.e(TAG, "拉取我喜欢的音乐失败", e)
                _error.value = e.message
                val ids = appRef.likedSongDao.allIds()
                val byId = appRef.songDao.getByIds(ids).associateBy { it.songId }
                _tracks.value = ids.mapNotNull { byId[it] }
            } finally {
                _loading.value = false
            }
        }
    }

    /** 取消红心后从缓存重读,不重新拉网络 */
    fun refreshFromCache() {
        viewModelScope.launch {
            val ids = appRef.likedSongDao.allIds()
            val byId = appRef.songDao.getByIds(ids).associateBy { it.songId }
            _tracks.value = ids.mapNotNull { byId[it] }
        }
    }

    fun clearError() {
        _error.value = null
    }

    private suspend fun orderedTracks(): List<SongEntity> {
        val ids = appRef.likedSongDao.allIds()
        if (ids.isEmpty()) return emptyList()
        val byId = appRef.playlistCache.ensureSongMetadata(ids).associateBy { it.songId }
        return ids.mapNotNull { byId[it] }
    }

    private companion object {
        const val TAG = "LikedSongsVM"
    }
}
