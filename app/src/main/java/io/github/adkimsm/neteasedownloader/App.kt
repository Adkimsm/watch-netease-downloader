package io.github.adkimsm.neteasedownloader

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import io.github.adkimsm.neteasedownloader.data.AppDatabase
import io.github.adkimsm.neteasedownloader.data.CookieStore
import io.github.adkimsm.neteasedownloader.data.LikedSongDao
import io.github.adkimsm.neteasedownloader.data.MediaStoreWriter
import io.github.adkimsm.neteasedownloader.data.PlaylistCache
import io.github.adkimsm.neteasedownloader.data.PlaylistDao
import io.github.adkimsm.neteasedownloader.data.PlaylistSongDao
import io.github.adkimsm.neteasedownloader.data.SettingsStore
import io.github.adkimsm.neteasedownloader.data.SongDao
import io.github.adkimsm.neteasedownloader.diag.Diag
import io.github.adkimsm.neteasedownloader.net.NcmApi
import io.github.adkimsm.neteasedownloader.sync.SyncEngine
import io.github.adkimsm.neteasedownloader.player.LocalFirstResolver
import io.github.adkimsm.neteasedownloader.player.NcmPlaybackSource
import io.github.adkimsm.neteasedownloader.player.PlaybackRepository
import io.github.adkimsm.neteasedownloader.player.QueueStore
import io.github.adkimsm.neteasedownloader.player.PlaybackUri
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
    val likedSongDao: LikedSongDao by lazy { LikedSongDao(database) }
    /** 远端歌单/曲目缓存:同步与浏览层共用 */
    val playlistCache: PlaylistCache by lazy {
        PlaylistCache(ncmApi, playlistDao, songDao, playlistSongDao)
    }
    val mediaStoreWriter: MediaStoreWriter by lazy { MediaStoreWriter(this) }
    val settingsStore: SettingsStore by lazy { SettingsStore(this, appScope) }
    val syncEngine: SyncEngine by lazy {
        SyncEngine(
            context = this,
            api = ncmApi,
            cookieStore = cookieStore,
            settingsStore = settingsStore,
            playlistDao = playlistDao,
            songDao = songDao,
            playlistSongDao = playlistSongDao,
            mediaStoreWriter = mediaStoreWriter,
            // 正在播放的歌在同步删除时豁免(自愈:下一轮不再受保护)
            playingSongId = { playbackRepository.state.value.songId },
        )
    }

    // ---------- 播放 ----------

    val queueStore: QueueStore by lazy { QueueStore(this) }

    val playbackSource: NcmPlaybackSource by lazy {
        NcmPlaybackSource(songDao = songDao, api = ncmApi, settingsStore = settingsStore)
    }

    val localFirstResolver: LocalFirstResolver by lazy {
        LocalFirstResolver(source = playbackSource, hasNetwork = ::isNetworkAvailable)
    }

    val playbackRepository: PlaybackRepository by lazy {
        PlaybackRepository(
            appContext = this,
            songDao = songDao,
            queueStore = queueStore,
            source = playbackSource,
            resolver = localFirstResolver,
            scope = appScope,
        )
    }

    /**
     * 解析当前账号 uid:优先用已持久化的,否则现拉 account 并回写。
     * 扫码登录的 803 响应通常没有 account,uid 常为 0,必须在这里兜底。
     */
    suspend fun resolveUid(): Long {
        val stored = cookieStore.uidState.value
        if (stored != 0L) return stored
        val uid = runCatching { ncmApi.fetchAccount()?.id ?: 0L }.getOrDefault(0L)
        if (uid != 0L) cookieStore.setUid(uid)
        return uid
    }

    /**
     * 队列里所有曲目都写成 [PlaybackUri] 的自定义 scheme,
     * 真实地址(本地 content:// 或串流 https://)由 [localFirstResolver] 在打开前解析。
     */
    fun playbackMediaSourceFactory(): MediaSource.Factory =
        DefaultMediaSourceFactory(
            ResolvingDataSource.Factory(DefaultDataSource.Factory(this), localFirstResolver),
        )

    /** 串流兜底用:无网时未下载的歌直接判为不可播 */
    fun isNetworkAvailable(): Boolean = runCatching {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return@runCatching false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return@runCatching false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)
    // ---------- 远端歌单管理(删除这首歌) ----------

    val songRemover: io.github.adkimsm.neteasedownloader.library.SongRemover by lazy {
        io.github.adkimsm.neteasedownloader.library.SongRemover(
            api = ncmApi,
            playlistDao = playlistDao,
            playlistSongDao = playlistSongDao,
            songDao = songDao,
            likedSongDao = likedSongDao,
            mediaStoreWriter = mediaStoreWriter,
            playback = playbackRepository,
            uid = { cookieStore.uidState.value },
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Diag.initialize(this)
        installCrashHandler()
        cookieStore = CookieStore.fromContext(this, appScope)
        ncmApi = NcmApi(cookieStore)
        appScope.launch {
            cookieStore.init()
            Diag.i("App", "cookie init 完成,已登录=${cookieStore.isLoggedIn()}")
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Diag.e("CRASH", "FATAL EXCEPTION in ${thread.name}", throwable)
            Diag.e("CRASH", "文件=${Diag.logFilePath()}")
            // 给异步文件写留一点时间
            Thread.sleep(200)
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
