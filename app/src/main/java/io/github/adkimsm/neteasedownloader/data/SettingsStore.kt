package io.github.adkimsm.neteasedownloader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

    suspend fun setLevel(level: String) {
        ctx.settingsDataStore.edit { it[LEVEL_KEY] = level }
    }

    companion object {
        const val LEVEL_STANDARD = "standard"
        const val LEVEL_HIGHER = "higher"
        const val LEVEL_EXHIGH = "exhigh"
        const val LEVEL_LOSSLESS = "lossless"
        val LEVELS = listOf(LEVEL_STANDARD, LEVEL_HIGHER, LEVEL_EXHIGH, LEVEL_LOSSLESS)
        private val LEVEL_KEY = stringPreferencesKey("level")
    }
}
