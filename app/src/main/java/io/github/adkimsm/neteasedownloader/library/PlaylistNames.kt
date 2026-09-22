package io.github.adkimsm.neteasedownloader.library

/**
 * 歌单名与「加入歌单」这类写操作的**前置校验**(纯函数,可单测)。
 *
 * 为什么要在这里拦而不是等服务端报错:手表上敲字本来就不方便,
 * 提交后才告诉用户"名字不能为空"是最差的一种反馈。能本地判定的就先本地判定。
 */

/** 歌单名长度上限。网易云没有公开的硬限制,这里取一个保守值避免超长名字把 UI 撑坏。 */
const val PLAYLIST_NAME_MAX = 40

sealed interface NameCheck {
    data class Ok(val name: String) : NameCheck

    /** 空 / 全是空白 */
    data object Blank : NameCheck

    /** 太长 */
    data object TooLong : NameCheck
}

/**
 * 校验并规范化歌单名:去掉首尾空白与换行。
 *
 * 全角空格也要去掉 —— 手表输入法很容易打出全角空格,`trim()` 默认不处理它。
 */
fun checkPlaylistName(raw: String): NameCheck {
    val name = raw.trim().trim('\u3000')
    return when {
        name.isEmpty() -> NameCheck.Blank
        name.length > PLAYLIST_NAME_MAX -> NameCheck.TooLong
        else -> NameCheck.Ok(name)
    }
}

/** 重命名时名字没变就没必要发请求 */
fun renameIsNoop(currentName: String, input: String): Boolean =
    (checkPlaylistName(input) as? NameCheck.Ok)?.name == currentName

/**
 * 「加入歌单」的目标候选:本人歌单,排除「我喜欢的音乐」。
 *
 * 红心走 radio/like,如果它出现在"加入歌单"的候选里,用户会以为自己能通过
 * 加歌单来红心一首歌 —— 那是一条走不通的路。
 */
fun addToPlaylistTargets(playlists: List<AddTarget>): List<AddTarget> =
    playlists.filter { it.owned && !it.liked }

data class AddTarget(
    val playlistId: Long,
    val name: String,
    val owned: Boolean,
    val liked: Boolean,
)
