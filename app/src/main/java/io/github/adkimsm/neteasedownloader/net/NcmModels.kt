package io.github.adkimsm.neteasedownloader.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

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

// ---------- 歌单与歌曲 ----------

@Serializable
data class UserPlaylistReq(val uid: Long, val limit: Int = 100, val offset: Int = 0, val includeVideo: Boolean = true)

@Serializable
data class UserPlaylistResp(val code: Int = 0, val playlist: List<PlaylistDto> = emptyList())

@Serializable
data class PlaylistDto(
    val id: Long,
    val name: String,
    val coverImgUrl: String? = null,
    val trackCount: Int = 0,
    val creator: CreatorDto? = null,
    /** 5 = 「我喜欢的音乐」;其他类型见网易云文档 */
    val specialType: Int = 0,
    /** 是否收藏(他人歌单)。收藏来的歌单不可编辑。 */
    val subscribed: Boolean = false,
)

@Serializable
data class CreatorDto(val userId: Long, val nickname: String? = null)

@Serializable
data class PlaylistDetailReq(val id: Long, val n: Int = 100000, val s: Int = 8)

@Serializable
data class PlaylistDetailResp(val code: Int = 0, val playlist: PlaylistDetailDto? = null)

@Serializable
data class PlaylistDetailDto(
    val id: Long,
    val name: String,
    val trackIds: List<TrackIdDto> = emptyList(),
    val trackCount: Int = 0,
)

@Serializable
data class TrackIdDto(val id: Long)

/** v3/song/detail 的 c 参数是 JSON 字符串,序列化时需保持内部转义 */
@Serializable
data class SongDetailReq(val c: String)

@Serializable
data class SongDetailResp(val code: Int = 0, val songs: List<SongDto> = emptyList())

@Serializable
data class SongDto(
    val id: Long,
    val name: String,
    val ar: List<ArtistDto> = emptyList(),
    val al: AlbumDto? = null,
    val dt: Long = 0,
)

@Serializable
data class ArtistDto(val id: Long = 0, val name: String = "")

@Serializable
data class AlbumDto(val id: Long = 0, val name: String = "", val picUrl: String? = null)

@Serializable
data class SongUrlReq(val ids: String, val level: String, val encodeType: String = "flac")

@Serializable
data class SongUrlResp(val code: Int = 0, val data: List<SongUrlDto> = emptyList())

@Serializable
data class SongUrlDto(
    val id: Long,
    val url: String? = null,
    val br: Long = 0,
    val size: Long = 0,
    val md5: String? = null,
    val type: String? = null,
    val fee: Int = 0,
    val freeTrialInfo: JsonElement? = null,
    val level: String? = null,
)

/** 红心歌曲 id 列表(/api/song/like/get) */
@Serializable
data class LikedIdsResp(
    val code: Int = 0,
    val ids: List<Long> = emptyList(),
)
