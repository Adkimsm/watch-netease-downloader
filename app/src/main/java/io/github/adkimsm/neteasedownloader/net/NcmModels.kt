package io.github.adkimsm.neteasedownloader.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 全局 JSON 配置:忽略未知字段、默认值参与序列化 */
val NcmJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
}

/** 扫码登录 unikey 响应 */
@Serializable
data class UnikeyResp(
    val code: Int = 0,
    val unikey: String = "",
)

/** 扫码状态轮询响应:801 待扫码 / 802 已扫码待确认 / 803 登录成功 / 800 key 过期 */
@Serializable
data class QrcodeCheckResp(
    val code: Int = 0,
    val message: String? = null,
    val account: NcmAccount? = null,
)

/** 账号信息(/api/w/nuser/account/get) */
@Serializable
data class NcmAccount(
    val id: Long = 0,
    val userName: String? = null,
    val nickname: String? = null,
    val avatarUrl: String? = null,
)

enum class QrcodeStatus {
    WAITING_SCAN, // 801
    SCANNED, // 802
    SUCCESS, // 803
    EXPIRED, // 800
    UNKNOWN,
}

fun QrcodeCheckResp.toStatus(): QrcodeStatus = when (code) {
    801 -> QrcodeStatus.WAITING_SCAN
    802 -> QrcodeStatus.SCANNED
    803 -> QrcodeStatus.SUCCESS
    800 -> QrcodeStatus.EXPIRED
    else -> QrcodeStatus.UNKNOWN
}

@Serializable
data class QrcodeUnikeyReq(val type: Int = 3)

@Serializable
data class QrcodeCheckReq(val key: String, val type: Int = 3)
