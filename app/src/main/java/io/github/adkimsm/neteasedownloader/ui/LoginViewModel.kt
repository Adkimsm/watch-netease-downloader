package io.github.adkimsm.neteasedownloader.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.adkimsm.neteasedownloader.App
import io.github.adkimsm.neteasedownloader.diag.Diag
import io.github.adkimsm.neteasedownloader.net.NcmAccount
import io.github.adkimsm.neteasedownloader.net.NcmApiException
import io.github.adkimsm.neteasedownloader.net.QrcodeCheckResp
import io.github.adkimsm.neteasedownloader.net.QrcodeStatus
import io.github.adkimsm.neteasedownloader.net.toStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

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
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            state = LoginUiState.Loading
            try {
                Diag.i(TAG, "申请 unikey…")
                val unikey = withTimeout(UNIKEY_TIMEOUT_MS) { api.createQrcodeUnikey() }
                Diag.i(TAG, "unikey 获取成功: $unikey")

                val qrUrl = "https://music.163.com/login?codekey=$unikey"
                val bitmap = QrRenderer.render(qrUrl, QR_BITMAP_SIZE)
                Diag.i(TAG, "二维码渲染完成, size=$QR_BITMAP_SIZE")
                state = LoginUiState.Waiting(bitmap, QrcodeStatus.WAITING_SCAN, "等待扫码")

                while (isActive) {
                    delay(POLL_INTERVAL_MS)
                    val resp = try {
                        withTimeout(POLL_TIMEOUT_MS) { api.checkQrcodeLogin(unikey) }
                    } catch (e: TimeoutCancellationException) {
                        Diag.w(TAG, "轮询超时,重试")
                        updateWaiting(QrcodeStatus.UNKNOWN, "网络抖动,继续等待扫码…")
                        continue
                    }
                    val parsed = resp.decode<QrcodeCheckResp>()
                    Diag.i(TAG, "轮询返回 code=${parsed.code}")
                    when (parsed.toStatus()) {
                        QrcodeStatus.WAITING_SCAN ->
                            updateWaiting(QrcodeStatus.WAITING_SCAN, parsed.message ?: "等待扫码")
                        QrcodeStatus.SCANNED ->
                            updateWaiting(QrcodeStatus.SCANNED, parsed.message ?: "已扫码,请在手机上确认")
                        QrcodeStatus.SUCCESS -> {
                            val musicU = api.extractMusicU(resp.setCookies)
                                ?: throw NcmApiException("登录响应缺少 MUSIC_U")
                            val csrf = extractCookie(resp.setCookies, "__csrf")
                            Diag.i(TAG, "803 成功,MUSIC_U=${musicU.take(8)}…, csrf=${csrf.take(8)}")
                            val uid = parsed.account?.id ?: 0L
                            Diag.i(TAG, "登录 uid=$uid")
                            cookieStore.setLogin(musicU, csrf, uid)
                            Diag.i(TAG, "cookie 已持久化")
                            val account = runCatching { api.fetchAccount() }.getOrNull()
                            Diag.i(TAG, "fetchAccount=${account?.nickname ?: "null(降级忽略)"}")
                            state = LoginUiState.Success(account)
                            return@launch
                        }
                        QrcodeStatus.EXPIRED -> {
                            Diag.w(TAG, "二维码过期")
                            state = LoginUiState.Error("二维码已过期,点击刷新重试")
                            return@launch
                        }
                        QrcodeStatus.UNKNOWN ->
                            updateWaiting(QrcodeStatus.UNKNOWN, parsed.message ?: "状态:${parsed.code}")
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Diag.w(TAG, "unikey 超时")
                state = LoginUiState.Error("网络超时,请重试")
            } catch (e: CancellationException) {
                Diag.i(TAG, "登录协程被取消")
                throw e
            } catch (e: Exception) {
                Diag.e(TAG, "登录流程异常:${e.message}", e)
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
        private const val POLL_TIMEOUT_MS = 10_000L
        private const val UNIKEY_TIMEOUT_MS = 20_000L
    }
}