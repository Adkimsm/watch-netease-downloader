package io.github.adkimsm.neteasedownloader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.adkimsm.neteasedownloader.App
import io.github.adkimsm.neteasedownloader.data.SongEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 播放页 / mini 播放条 / 队列页共用的 ViewModel。
 *
 * [PlaybackState] 里刻意只有 songId 与传输状态:标题、歌手、"本地还是在线"都按 songId
 * 查本地库得到。两个来源分开存的话,曲目行显示"已下载"而播放页显示"在线"这种偏差
 * 迟早会出现。
 */
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val appRef = app as App
    private val repo = appRef.playbackRepository

    val state = repo.state
    val queue = repo.queue

    private val _song = MutableStateFlow<SongEntity?>(null)
    val song = _song.asStateFlow()

    private val _queueSongs = MutableStateFlow<List<SongEntity>>(emptyList())
    val queueSongs = _queueSongs.asStateFlow()

    init {
        repo.ensureConnected()

        viewModelScope.launch {
            repo.state.map { it.songId }.distinctUntilChanged().collect { id ->
                _song.value = id?.let { appRef.songDao.getByIds(listOf(it)).firstOrNull() }
            }
        }
        viewModelScope.launch {
            repo.queue.collect { ids ->
                if (ids.isEmpty()) {
                    _queueSongs.value = emptyList()
                    return@collect
                }
                val byId = appRef.songDao.getByIds(ids).associateBy { it.songId }
                _queueSongs.value = ids.mapNotNull { byId[it] }
            }
        }
    }

    fun togglePlayPause() = repo.togglePlayPause()
    fun skipNext() = repo.skipNext()
    fun skipPrevious() = repo.skipPrevious()
    fun seekTo(positionMs: Long) = repo.seekTo(positionMs)
    fun cycleRepeat() = repo.cycleRepeat()
    fun toggleShuffle() = repo.toggleShuffle()
    fun playAt(index: Int) = repo.playAt(index)
    fun removeFromQueue(songId: Long) = repo.removeFromQueue(songId)

    /** 从曲目列表组队开播(点哪首从哪首开始) */
    fun playList(songs: List<SongEntity>, startIndex: Int, sourcePlaylistId: Long?) =
        repo.play(songs, startIndex, sourcePlaylistId)
    fun clearError() = repo.clearError()
}
