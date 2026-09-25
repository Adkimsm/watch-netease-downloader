package io.github.adkimsm.neteasedownloader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.adkimsm.neteasedownloader.library.RemoveScope
import io.github.adkimsm.neteasedownloader.library.removeScopeFrom
import io.github.adkimsm.neteasedownloader.net.unlock.ProviderId
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
    // ---------- 灰色歌曲解锁 ----------
    // 下载与在线播放**各自独立开关**:串流不落盘、关掉立即可恢复;而下载会产出
    // 永久的本地文件,后果不对等。两个替换开关默认都开(与用户选的"全部范围"一致),
    // X-Real-IP 默认关(它伪造来源 IP,要不要用由用户自己决定)。
    val unlockDownload = ctx.settingsDataStore.data
        .map { it[UNLOCK_DOWNLOAD_KEY] ?: true }
        .stateIn(appScope, SharingStarted.Eagerly, true)

    /** 在线播放路径:这类歌曲是否直接串流第三方音源(不落盘) */
    val unlockStream = ctx.settingsDataStore.data
        .map { it[UNLOCK_STREAM_KEY] ?: true }
        .stateIn(appScope, SharingStarted.Eagerly, true)

    /** 音源:酷我 */
    val providerKuwo = ctx.settingsDataStore.data
        .map { it[PROVIDER_KUWO_KEY] ?: true }
        .stateIn(appScope, SharingStarted.Eagerly, true)

    /** 音源:酷狗 */
    val providerKugou = ctx.settingsDataStore.data
        .map { it[PROVIDER_KUGOU_KEY] ?: true }
        .stateIn(appScope, SharingStarted.Eagerly, true)

    /**
     * 地区解锁:给网易云请求加 `X-Real-IP`。
     * 默认关,由用户决定是否开启。
     */
    val spoofRealIp = ctx.settingsDataStore.data
        .map { it[SPOOF_REAL_IP_KEY] ?: false }
        .stateIn(appScope, SharingStarted.Eagerly, false)

    suspend fun setUnlockDownload(value: Boolean) {
        ctx.settingsDataStore.edit { it[UNLOCK_DOWNLOAD_KEY] = value }
    }

    suspend fun setUnlockStream(value: Boolean) {
        ctx.settingsDataStore.edit { it[UNLOCK_STREAM_KEY] = value }
    }

    suspend fun setProviderKuwo(value: Boolean) {
        ctx.settingsDataStore.edit { it[PROVIDER_KUWO_KEY] = value }
    }

    suspend fun setProviderKugou(value: Boolean) {
        ctx.settingsDataStore.edit { it[PROVIDER_KUGOU_KEY] = value }
    }

    suspend fun setSpoofRealIp(value: Boolean) {
        ctx.settingsDataStore.edit { it[SPOOF_REAL_IP_KEY] = value }
    }

    /** 当前启用的音源(按优先级 酷我→酷狗)。只反映音源开关,与两个替换开关无关。 */
    fun activeProviderIds(): List<String> = buildList {
        if (providerKuwo.value) add(ProviderId.KUWO)
        if (providerKugou.value) add(ProviderId.KUGOU)
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

        private val UNLOCK_DOWNLOAD_KEY = booleanPreferencesKey("unlock_download")
        private val UNLOCK_STREAM_KEY = booleanPreferencesKey("unlock_stream")
        private val PROVIDER_KUWO_KEY = booleanPreferencesKey("provider_kuwo")
        private val PROVIDER_KUGOU_KEY = booleanPreferencesKey("provider_kugou")
        private val SPOOF_REAL_IP_KEY = booleanPreferencesKey("spoof_real_ip")
    }
}
