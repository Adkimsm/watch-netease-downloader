package io.github.adkimsm.neteasedownloader.player

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.adkimsm.neteasedownloader.diag.Diag
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private val Context.playbackDataStore: DataStore<Preferences> by preferencesDataStore(name = "playback")

/**
 * 播放队列与位置的持久化(单 key JSON)。
 *
 * 快照损坏/字段不兼容时**静默回落空快照**:恢复播放失败不该把 App 拖挂,
 * 大不了从头开始播。
 */
class QueueStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    suspend fun load(): PlaybackSnapshot {
        val raw = runCatching { context.playbackDataStore.data.first()[SNAPSHOT_KEY] }.getOrNull()
        if (raw.isNullOrEmpty()) return PlaybackSnapshot.EMPTY
        return runCatching { json.decodeFromString(PlaybackSnapshot.serializer(), raw) }
            .getOrElse { e ->
                Diag.w(TAG, "播放快照解析失败,按空处理:${e.message}")
                PlaybackSnapshot.EMPTY
            }
    }

    suspend fun save(snapshot: PlaybackSnapshot) {
        runCatching {
            val raw = json.encodeToString(PlaybackSnapshot.serializer(), snapshot)
            context.playbackDataStore.edit { it[SNAPSHOT_KEY] = raw }
        }.onFailure { Diag.w(TAG, "播放快照写入失败:${it.message}") }
    }

    suspend fun clear() {
        runCatching { context.playbackDataStore.edit { it.remove(SNAPSHOT_KEY) } }
            .onFailure { Diag.w(TAG, "播放快照清除失败:${it.message}") }
    }

    private companion object {
        const val TAG = "QueueStore"
        val SNAPSHOT_KEY = stringPreferencesKey("playback_snapshot")
    }
}
