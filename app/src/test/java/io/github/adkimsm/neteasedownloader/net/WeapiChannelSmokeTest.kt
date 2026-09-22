package io.github.adkimsm.neteasedownloader.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * weapi 通道自身的**匿名冒烟**。
 *
 * 不登录也调一个 weapi 端点:请求应当被服务端接受(证明 weapi 加密、URL、
 * Referer、Cookie 头的组合是对的),然后因为"未登录"被业务层拒绝 ——
 * 拒绝的理由不应该是"看不懂这个请求"。
 *
 * 这是 Phase 9/10 里风险最大的一块(weapi 与 eapi 的差别不只是加密),
 * 但它不需要任何账号就能在 CI 里常驻验证。
 */
class WeapiChannelSmokeTest {

    private val api = NcmApi(CookieProvider { "" })

    @Test
    fun weapiRequest_isAcceptedAtTransportLevel() = runBlocking {
        val error = runCatching {
            api.weapiRaw("/api/song/like/get", RemoteWritePayload.likedIds(1L))
        }.exceptionOrNull()

        if (error == null) {
            // 匿名也通了 —— 那更好,不用管;这个测试要拦的是"weapi 通道根本走不通"
            return@runBlocking
        }

        assertTrue(
            "失败信息应带服务端的 HTTP 状态码(而不是" +
                "解析失败/空响应这类传输层问题): ${error.message}",
            error.message?.contains("HTTP") == true,
        )
    }
}