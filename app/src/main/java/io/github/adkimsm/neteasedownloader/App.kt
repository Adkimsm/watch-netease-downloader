package io.github.adkimsm.neteasedownloader

import android.app.Application
import io.github.adkimsm.neteasedownloader.data.AppDatabase
import io.github.adkimsm.neteasedownloader.data.CookieStore
import io.github.adkimsm.neteasedownloader.data.MediaStoreWriter
import io.github.adkimsm.neteasedownloader.data.PlaylistDao
import io.github.adkimsm.neteasedownloader.data.PlaylistSongDao
import io.github.adkimsm.neteasedownloader.data.SettingsStore
import io.github.adkimsm.neteasedownloader.data.SongDao
import io.github.adkimsm.neteasedownloader.net.NcmApi
import io.github.adkimsm.neteasedownloader.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var cookieStore: CookieStore
        private set
    lateinit var ncmApi: NcmApi
        private set
    val database: AppDatabase by lazy { AppDatabase(this) }
    val playlistDao: PlaylistDao by lazy { PlaylistDao(database) }
    val songDao: SongDao by lazy { SongDao(database) }
    val playlistSongDao: PlaylistSongDao by lazy { PlaylistSongDao(database) }
    val mediaStoreWriter: MediaStoreWriter by lazy { MediaStoreWriter(this) }
    val settingsStore: SettingsStore by lazy { SettingsStore(this, appScope) }
    val syncEngine: SyncEngine by lazy {
        SyncEngine(
            context = this,
            api = ncmApi,
            settingsStore = settingsStore,
            playlistDao = playlistDao,
            songDao = songDao,
            playlistSongDao = playlistSongDao,
            mediaStoreWriter = mediaStoreWriter,
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        cookieStore = CookieStore(this, appScope)
        ncmApi = NcmApi(cookieStore)
        appScope.launch { cookieStore.init() }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
