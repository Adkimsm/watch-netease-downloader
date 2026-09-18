package io.github.adkimsm.neteasedownloader.ui

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.adkimsm.neteasedownloader.App
import io.github.adkimsm.neteasedownloader.net.NcmAccount
import io.github.adkimsm.neteasedownloader.net.QrcodeCheckResp
import io.github.adkimsm.neteasedownloader.net.QrcodeStatus
import io.github.adkimsm.neteasedownloader.net.toStatus
import io.github.adkimsm.neteasedownloader.net.NcmApiException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface LoginUiState {
    data object Loading : LoginUiState
    data class Waiting(
        val qrBitmap: Bitmap,
        val status: QrcodeStatus,
        val message: String,
    ) : LoginUiState
    data class Success(val account: NcmAccount?) : LoginUiState
    data class Error(val message: String) : LoginUiState
}

class LoginViewModel(app: Application) : AndroidViewModel(app) {
    private val api = (app as App).ncmApi
    private val cookieStore = (app as App).cookieStore

    private val _state = MutableStateFlow<LoginUiState>(LoginUiState.Loading)
    val stateFlow = _state.asStateFlow()

    var state: LoginUiState
        get() = _state.value
        private set(value) { _state.value = value }

    private var pollJob: Job? = null

    fun startLogin() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            state = LoginUiState.Loading
            try {
                val unikey = api.createQrcodeUnikey()
                val qrUrl = "https://music.163.com/login?codekey=$unikey"
                val bitmap = QrRenderer.render(qrUrl, QR_BITMAP_SIZE)
                state = LoginUiState.Waiting(bitmap, QrcodeStatus.WAITING_SCAN, "等待扫码")
                while (isActive) {
                    delay(POLL_INTERVAL_MS)
                    val resp = api.checkQrcodeLogin(unikey)
                    val parsed = resp.decode<QrcodeCheckResp>()
                    when (parsed.toStatus()) {
                        QrcodeStatus.WAITING_SCAN ->
                            updateWaiting(QrcodeStatus.WAITING_SCAN, parsed.message ?: "等待扫码")
                        QrcodeStatus.SCANNED ->
                            updateWaiting(QrcodeStatus.SCANNED, parsed.message ?: "已扫码,请在手机上确认")
                        QrcodeStatus.SUCCESS -> {
                            val musicU = api.extractMusicU(resp.setCookies)
                                ?: throw NcmApiException("登录响应缺少 MUSIC_U")
                            val csrf = extractCookie(resp.setCookies, "__csrf")
                            cookieStore.setLogin(musicU, csrf)
                            val account = api.fetchAccount()
                            state = LoginUiState.Success(account)
                            return@launch
                        }
                        QrcodeStatus.EXPIRED -> {
                            state = LoginUiState.Error("二维码已过期,点击刷新重试")
                            return@launch
                        }
                        QrcodeStatus.UNKNOWN ->
                            updateWaiting(QrcodeStatus.UNKNOWN, parsed.message ?: "状态:${parsed.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "login flow failed", e)
                state = LoginUiState.Error(e.message ?: "登录失败")
            }
        }
    }

    private fun updateWaiting(status: QrcodeStatus, message: String) {
        val current = state
        if (current is LoginUiState.Waiting) {
            state = current.copy(status = status, message = message)
        }
    }

    private fun extractCookie(cookies: List<String>, name: String): String =
        cookies.firstNotNullOfOrNull { cookie ->
            cookie.substringBefore(";").split("=", limit = 2)
                .takeIf { it.size == 2 && it[0].trim() == name }
                ?.get(1)
        }.orEmpty()

    companion object {
        private const val TAG = "LoginViewModel"
        private const val QR_BITMAP_SIZE = 320
        private const val POLL_INTERVAL_MS = 2500L
    }
}
