package io.github.adkimsm.neteasedownloader.net

/** 提供请求 Cookie 头;实现侧持久化 MUSIC_U 等凭证 */
fun interface CookieProvider {
    fun cookieHeader(): String
}
