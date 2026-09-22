package io.github.adkimsm.neteasedownloader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.adkimsm.neteasedownloader.library.RemoveScope
import io.github.adkimsm.neteasedownloader.library.removeScopeFrom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** 应用设置:音质档位等 */
class SettingsStore(private val ctx: Context, appScope: CoroutineScope) {
    val level = ctx.settingsDataStore.data
        .map { it[LEVEL_KEY] ?: LEVEL_STANDARD }
        .stateIn(appScope, SharingStarted.Eagerly, LEVEL_STANDARD)

    /**
     * 在线串流音质。与下载音质分开:磁盘上可以存无损,但手表串流无损基本必卡,
     * 所以串流单独一档(默认极高 320kbps)。
     */
    val streamLevel = ctx.settingsDataStore.data
        .map { it[STREAM_LEVEL_KEY] ?: LEVEL_EXHIGH }
        .stateIn(appScope, SharingStarted.Eagerly, LEVEL_EXHIGH)

    suspend fun setLevel(level: String) {
        ctx.settingsDataStore.edit { it[LEVEL_KEY] = level }
    }

    suspend fun setStreamLevel(level: String) {
        ctx.settingsDataStore.edit { it[STREAM_LEVEL_KEY] = level }
    }

    /**
     * 删除歌曲时如何处理远端。默认 [RemoveScope.ASK] —— 第一次删除一定让用户看见
     * 到底发生了什么(哪些歌单、本地文件删不删),之后再按需要收紧成"点了就删"。
     */
    val removeScope = ctx.settingsDataStore.data
        .map { removeScopeFrom(it[REMOVE_SCOPE_KEY]) }
        .stateIn(appScope, SharingStarted.Eagerly, RemoveScope.ASK)

    suspend fun setRemoveScope(scope: RemoveScope) {
        ctx.settingsDataStore.edit { it[REMOVE_SCOPE_KEY] = scope.name }
    }

    companion object {
        const val LEVEL_STANDARD = "standard"
        const val LEVEL_HIGHER = "higher"
        const val LEVEL_EXHIGH = "exhigh"
        const val LEVEL_LOSSLESS = "lossless"
        val LEVELS = listOf(LEVEL_STANDARD, LEVEL_HIGHER, LEVEL_EXHIGH, LEVEL_LOSSLESS)
        private val LEVEL_KEY = stringPreferencesKey("level")
        private val STREAM_LEVEL_KEY = stringPreferencesKey("stream_level")
        private val REMOVE_SCOPE_KEY = stringPreferencesKey("remove_scope")
    }
}
