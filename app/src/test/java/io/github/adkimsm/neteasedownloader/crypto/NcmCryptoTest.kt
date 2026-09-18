package io.github.adkimsm.neteasedownloader.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 向量由参考实现(Python + openssl,对照 NeteaseCloudMusicApi util/crypto.js)生成,
 * 见 /tmp/opencode/ncmref/ncm.py。固定 secretKey 保证 weapi 可复现。
 */
class NcmCryptoTest {
    private val fixedSecretKey = "abcdefghijklmnop"
    private val payload = """{"type":3}"""

    @Test
    fun weapi_matchesReferenceVector() {
        val form = NcmCrypto.weapi(payload, fixedSecretKey)
        assertEquals(
            "KvY3feRTl6uX5m5cjNDgaQOWOzCp89c7N5+8+KFFOZ4=",
            form.params,
        )
        assertEquals(
            "b545cd89ddac8bca0f903a64f6aab5de62aab5384bcc615ec76e822caa5c08205" +
                "05a02fd8b3707e9a5af49fba00d46c9233b8ade55815a09e140f3d46e83cd68" +
                "272266d2f1862a36d902ed67451400bec2679320b1",
            form.encSecKey,
        )
    }

    @Test
    fun eapi_matchesReferenceVector() {
        val form = NcmCrypto.eapi("/api/login/qrcode/unikey", payload)
        assertEquals(
            "1CA81A97B7BAEB099F29A9B99A25CD6E58E7BA5B35F25231033065C776830C0A" +
                "38A128CF8B3EAF44635E50E0B9800A4A037CBDA1E04BD460C02768E3ECD5E66E" +
                "905A5BCAE52009FB0933EFB373A62E834048DEBDABCCC627F25820A8D94E0A8C",
            form.params,
        )
    }

    @Test
    fun weapi_isDeterministicForFixedSecretKey() {
        val a = NcmCrypto.weapi(payload, fixedSecretKey)
        val b = NcmCrypto.weapi(payload, fixedSecretKey)
        assertEquals(a, b)
    }
}
