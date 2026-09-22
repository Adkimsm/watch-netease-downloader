package io.github.adkimsm.neteasedownloader.net

/**
 * 提供请求 Cookie 头与 csrf。
 *
 * `csrfToken` 带默认实现,所以它**不是**抽象成员 —— 这个接口仍然是 `fun interface`,
 * 测试里的 `CookieProvider { "" }` 写法照旧可用。
 */
fun interface CookieProvider {
    fun cookieHeader(): String

    /**
     * weapi 的请求体里要带 `csrf_token`(eapi 只把它放在 Cookie 头里,所以此前用不到)。
     * 未登录或测试替身返回空串即可。
     */
    fun csrfToken(): String = ""
}
