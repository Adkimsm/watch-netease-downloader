package io.github.adkimsm.neteasedownloader.net

/**
 * 提供请求 Cookie 头与设备 header(供 eapi 写请求内嵌 payload)。
 *
 * 两个成员都带默认实现,所以它们**不是**抽象成员 —— 这个接口仍然是 `fun interface`,
 * 测试里的 `CookieProvider { "" }` 写法照旧可用。
 */
fun interface CookieProvider {
    fun cookieHeader(): String

    /**
     * eapi 写端点按参考实现要在 payload 里内嵌设备 header(与 Cookie 头同源)。
     * 未登录或测试替身返回空 map 即可。
     */
    fun deviceHeader(): Map<String, String> = emptyMap()
}
