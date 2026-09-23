package io.github.adkimsm.neteasedownloader.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * eapi 通道自身的**匿名冒烟**。
 *
 * 不登录也调一个 eapi 端点:请求应当被服务端接受并返回**可解析的 JSON**
 * (证明 eapi 加密、URL、Cookie 头的组合是对的),然后因为"未登录"被业务层
 * 拒绝(匿名应返回 code=301)。
 *
 * 这条断言同时是空响应回归的闸门:weapi 通道 2026-09 起对全部端点返回
 * HTTP 200 空 body,`body == null` 会让本测试直接失败 —— 正是本次故障的特征。
 */
class EapiChannelSmokeTest {

    private val api = NcmApi(CookieProvider { "" })

    @Test
    fun eapiLikedList_isAcceptedAtTransportLevel() = runBlocking {
        val resp = api.eapiRaw("/api/song/like/get", RemoteWritePayload.likedIds(1L))
        assertNotNull("响应体必须是可解析的 JSON(空 body 即 weapi 静默失败特征)", resp.body)
        assertEquals("匿名调用应被业务层拒绝(未登录)", 301, resp.code)
    }
}
