package io.github.adkimsm.neteasedownloader.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 直连 live 接口验证 eapi 全链路(加密 + 请求头 + 解析)。
 * 匿名调用即可覆盖:unikey 申请、扫码状态轮询(必为 801 待扫码)。
 */
class NcmApiTest {
    private val api = NcmApi(CookieProvider { "" })

    @Test
    fun unikey_and_check_roundtrip() = runBlocking {
        val unikey = api.createQrcodeUnikey()
        assertNotNull(unikey)
        assertTrue("unikey 应为非空 UUID", unikey.isNotEmpty())

        val resp = api.checkQrcodeLogin(unikey)
        assertEquals("刚申请的 key 应处于待扫码状态", 801, resp.code)
    }
}
