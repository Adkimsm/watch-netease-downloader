package io.github.adkimsm.neteasedownloader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.adkimsm.neteasedownloader.App
import io.github.adkimsm.neteasedownloader.data.SongEntity
import io.github.adkimsm.neteasedownloader.diag.Diag
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 歌曲搜索页的 ViewModel。
 *
 * 输入防抖 400ms:手表上逐字敲入,每个字符都打一次云端接口既费电也慢。
 * 空白(纯空格)关键词**不发请求**直接清空结果,与 NcmApi.searchSongs 的防御一致;
 * 两处都拦是为了让"清空输入"这个动作不等网络往返。
 *
 * 失败时保留上一次的结果并置 error(与 PlaylistDetailViewModel 的容错一致):
 * 断网时至少还能看到上次搜到的东西。
 */
class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val appRef = app as App

    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    private val _results = MutableStateFlow<List<SongEntity>>(emptyList())
    val results = _results.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChange(value: String) {
        _query.value = value
        searchJob?.cancel()
        val keyword = value.trim()
        if (keyword.isEmpty()) {
            _results.value = emptyList()
            _loading.value = false
            _error.value = null
            return
        }
        searchJob = viewModelScope.launch {
            // 防抖:停顿 DEBOUNCE_MS 才发请求
            delay(DEBOUNCE_MS)
            search(keyword)
        }
    }

    /** 错误条的重试入口:用当前关键词重搜一次 */
    fun retry() {
        val keyword = _query.value.trim()
        if (keyword.isEmpty()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { search(keyword) }
    }

    private suspend fun search(keyword: String) {
        _loading.value = true
        _error.value = null
        try {
            val dtos = appRef.ncmApi.searchSongs(keyword)
            _results.value = appRef.playlistCache.importSongs(dtos)
        } catch (e: Exception) {
            Diag.e(TAG, "搜索失败 keyword=$keyword", e)
            _error.value = e.message
        } finally {
            _loading.value = false
        }
    }

    fun clearError() {
        _error.value = null
    }

    private companion object {
        const val TAG = "SearchVM"
        const val DEBOUNCE_MS = 400L
    }
}
