package io.github.adkimsm.neteasedownloader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.adkimsm.neteasedownloader.net.CookieProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom

private val Context.cookieDataStore: DataStore<Preferences> by preferencesDataStore(name = "ncm_cookies")

/**
 * 持久化登录凭证与设备标识,并按 eapi 客户端样式拼装 Cookie 头。
 */
class CookieStore(private val context: Context) : CookieProvider {
    private val secureRandom = SecureRandom()

    private val musicUFlow = context.cookieDataStore.data.map { it[MUSIC_U_KEY].orEmpty() }
    private val csrfFlow = context.cookieDataStore.data.map { it[CSRF_KEY].orEmpty() }

    @Volatile private var musicU: String = ""
    @Volatile private var csrf: String = ""
    @Volatile private var deviceId: String = ""

    suspend fun init() {
        musicU = musicUFlow.first()
        csrf = csrfFlow.first()
        deviceId = context.cookieDataStore.data.map { it[DEVICE_ID_KEY].orEmpty() }.first()
        if (deviceId.isEmpty()) {
            deviceId = randomDeviceId()
            context.cookieDataStore.edit { it[DEVICE_ID_KEY] = deviceId }
        }
    }

    suspend fun setLogin(musicU: String, csrf: String) {
        this.musicU = musicU
        this.csrf = csrf
        context.cookieDataStore.edit {
            it[MUSIC_U_KEY] = musicU
            it[CSRF_KEY] = csrf
        }
    }

    suspend fun clear() = setLogin("", "")

    fun isLoggedIn(): Boolean = musicU.isNotEmpty()

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
    }
}
