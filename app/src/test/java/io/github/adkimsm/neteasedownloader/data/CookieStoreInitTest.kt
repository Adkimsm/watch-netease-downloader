package io.github.adkimsm.neteasedownloader.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 回归:冷启动时 CookieStore 必须正确读回已持久化的登录凭证。
 * 旧实现的 init() 取的是 stateIn 后的 StateFlow.first(),在磁盘读完成前
 * 就返回初值 "",导致杀后台重进后 MUSIC_U 恒为空、所有鉴权接口 301。
 */
class CookieStoreInitTest {
    private val musicUKey = stringPreferencesKey("MUSIC_U")
    private val csrfKey = stringPreferencesKey("__csrf")
    private val uidKey = longPreferencesKey("uid")

    private data class Fixture(
        val store: CookieStore,
        val scope: CoroutineScope,
    )

    private fun newFixture(): Fixture {
        // 用真实调度器(非虚拟时间):才能真实复现"磁盘读慢于函数调用"的竞态
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return Fixture(CookieStore(newDataStore(), scope), scope)
    }

    private fun newDataStore() = PreferenceDataStoreFactory.create(
        produceFile = {
            File.createTempFile("ncm_cookies", ".preferences_pb").also { it.delete() }
        },
    )

    @Test
    fun init_readsPersistedCredentials() = runBlocking {
        val (store, scope) = newFixture()
        try {
            // 模拟上次登录落盘的凭证,然后冷启动(重新构造一个读同一文件的实例)
            val ds = newDataStore()
            ds.edit {
                it[musicUKey] = "TOKEN_ABC"
                it[csrfKey] = "CSRF_XYZ"
                it[uidKey] = 12345L
            }
            val cold = CookieStore(ds, scope)

            // init 走冷流 first(),必须拿到持久化值而非 stateIn 初值 ""
            cold.init()
            assertTrue("init 后应为已登录", cold.isLoggedIn())
            val header = cold.cookieHeader()
            assertTrue("cookie 头应含 MUSIC_U", header.contains("MUSIC_U=TOKEN_ABC"))
            assertTrue("cookie 头应含 __csrf", header.contains("__csrf=CSRF_XYZ"))
            assertEquals("uid 应能读回", 12345L, cold.uidState.first { it != 0L })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun init_withoutLogin_staysLoggedOut() = runBlocking {
        val (store, scope) = newFixture()
        try {
            store.init()
            assertFalse(store.isLoggedIn())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun init_generatesDeviceId_whenMissing() = runBlocking {
        val (store, scope) = newFixture()
        try {
            store.init()
            val inHeader = store.cookieHeader().substringAfter("deviceId=").substringBefore(";")
            assertTrue("cookie 头应含已生成的 deviceId", inHeader.isNotEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun clear_wipesUid() = runBlocking {
        val (store, scope) = newFixture()
        try {
            store.setLogin("TOKEN", "CSRF", 99L)
            store.init()
            assertTrue(store.isLoggedIn())
            // 先确认 uid 已回流到 StateFlow
            assertEquals(99L, store.uidState.first { it == 99L })

            store.clear()
            assertFalse(store.isLoggedIn())
            // uid 应被移除而非残留 99
            assertEquals(0L, store.uidState.first { it == 0L })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun externalWrite_reflectsInCookieHeader() = runBlocking {
        val (store, scope) = newFixture()
        try {
            store.init()
            assertFalse(store.isLoggedIn())
            // 外部直接落盘(模拟另一进程/手动修复 cookie),常驻收集应回流到内存字段
            val ds = newDataStore()
            ds.edit {
                it[musicUKey] = "EXTERNAL_TOKEN"
                it[csrfKey] = "EXTERNAL_CSRF"
            }
            val external = CookieStore(ds, scope)
            // 等待断言真正依赖的状态:musicUState 与 init{} 里的常驻收集器是
            // dataStore.data 上两个独立收集器,前者就绪并不代表后者已把 csrf 回填到内存字段。
            withTimeout(5_000) {
                while (!external.cookieHeader().contains("__csrf=EXTERNAL_CSRF")) {
                    delay(10)
                }
            }
            assertTrue(
                "常驻收集应把外部写入同步到 cookieHeader",
                external.cookieHeader().contains("MUSIC_U=EXTERNAL_TOKEN"),
            )
            assertTrue(external.cookieHeader().contains("__csrf=EXTERNAL_CSRF"))
        } finally {
            scope.cancel()
        }
    }
}
