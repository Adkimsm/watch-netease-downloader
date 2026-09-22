package io.github.adkimsm.neteasedownloader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.adkimsm.neteasedownloader.net.CookieProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import java.security.SecureRandom

private val Context.cookieDataStore: DataStore<Preferences> by preferencesDataStore(name = "ncm_cookies")

/**
 * 持久化登录凭证与设备标识,并按 eapi 客户端样式拼装 Cookie 头。
 */
class CookieStore(
    private val dataStore: DataStore<Preferences>,
    appScope: CoroutineScope,
) : CookieProvider {
    private val secureRandom = SecureRandom()

    /** 登录态(StateFlow,UI 观察) */
    val musicUState = dataStore.data
        .map { it[MUSIC_U_KEY].orEmpty() }
        .stateIn(appScope, SharingStarted.Eagerly, "")

    /** 用户 uid(扫码成功后持久化,用于拉歌单;fetchAccount 失败时不阻塞) */
    val uidState = dataStore.data
        .map { it[UID_KEY] ?: 0L }
        .stateIn(appScope, SharingStarted.Eagerly, 0L)

    @Volatile private var musicU: String = ""
    @Volatile private var csrf: String = ""
    @Volatile private var deviceId: String = ""

    init {
        // 内存常驻字段始终镜像磁盘:任何途径(本进程 setLogin/clear,或外部修改)
        // 的变更都会回流到 cookieHeader()/isLoggedIn(),杜绝两态分歧
        dataStore.data
            .onEach { prefs ->
                musicU = prefs[MUSIC_U_KEY].orEmpty()
                csrf = prefs[CSRF_KEY].orEmpty()
                // deviceId 是一次性生成的:只有磁盘上确实存在时才镜像。
                // 否则本收集器可能在 init() 的写盘落地前先收到一份空快照,
                // 把刚生成的 deviceId 抹回空串,导致 cookie 头缺 deviceId、
                // 服务端鉴权偶发失败(单测 init_generatesDeviceId_whenMissing 即复现此竞态)。
                prefs[DEVICE_ID_KEY]?.takeIf { it.isNotEmpty() }?.let { deviceId = it }
            }
            .launchIn(appScope)
    }

    /**
     * 启动时调用一次:同步读到磁盘首次加载的凭证,并在 deviceId 缺失时生成一个。
     * 注意必须取冷流 [first]——StateFlow 的 first() 可能返回 stateIn 的初值而漏掉真实数据。
     */
    suspend fun init() {
        val prefs = dataStore.data.first()
        musicU = prefs[MUSIC_U_KEY].orEmpty()
        csrf = prefs[CSRF_KEY].orEmpty()
        deviceId = prefs[DEVICE_ID_KEY].orEmpty()
        if (deviceId.isEmpty()) {
            deviceId = randomDeviceId()
            dataStore.edit { it[DEVICE_ID_KEY] = deviceId }
        }
    }

    suspend fun setLogin(musicU: String, csrf: String, uid: Long) {
        // 先写内存(立即生效),再落盘;落盘成功后上面的常驻收集会再确认一次
        this.musicU = musicU
        this.csrf = csrf
        dataStore.edit {
            it[MUSIC_U_KEY] = musicU
            it[CSRF_KEY] = csrf
            if (uid != 0L) it[UID_KEY] = uid
        }
    }

    /** 登出:MUSIC_U/csrf 置空并清掉 uid */
    suspend fun clear() {
        musicU = ""
        csrf = ""
        dataStore.edit {
            it[MUSIC_U_KEY] = ""
            it[CSRF_KEY] = ""
            it.remove(UID_KEY)
        }
    }

    fun isLoggedIn(): Boolean = musicU.isNotEmpty()

    override fun csrfToken(): String = csrf

    override fun cookieHeader(): String =
        listOf(
            "osver" to "16.2",
            "deviceId" to deviceId,
            "os" to "iPhone OS",
            "appver" to "9.0.90",
            "versioncode" to "140",
            "mobilename" to "",
            "buildver" to (System.currentTimeMillis() / 1000).toString(),
            "resolution" to "1920x1080",
            "__csrf" to csrf,
            "channel" to "distribution",
            "requestId" to "${System.currentTimeMillis()}_${secureRandom.nextInt(1000)}",
            "MUSIC_U" to musicU,
        ).joinToString("; ") { (k, v) -> "$k=$v" }

    private fun randomDeviceId(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val MUSIC_U_KEY = stringPreferencesKey("MUSIC_U")
        private val CSRF_KEY = stringPreferencesKey("__csrf")
        private val DEVICE_ID_KEY = stringPreferencesKey("deviceId")
        private val UID_KEY = longPreferencesKey("uid")

        /** 生产环境:基于 Context 创建单例 DataStore */
        fun fromContext(context: Context, appScope: CoroutineScope) =
            CookieStore(context.cookieDataStore, appScope)
    }
}
