package io.github.adkimsm.neteasedownloader.crypto

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云音乐请求加密,对照 NeteaseCloudMusicApi util/crypto.js:
 *
 *  - weapi: AES-CBC(presetKey) -> AES-CBC(secretKey);encSecKey = 原始 RSA(倒序 secretKey)
 *  - eapi:  AES-ECB(eapiKey),明文为 "{url}-36cd479b6b5-{text}-36cd479b6b5-{md5}"
 */
object NcmCrypto {
    private const val IV = "0102030405060708"
    private const val PRESET_KEY = "0CoJUm6Qyw8W8jud"
    private const val EAPI_KEY = "e82ckenh8dichen8"
    private const val BASE62 =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private const val EAPI_DIGEST_MAGIC = "36cd479b6b5"
    private const val PUBLIC_EXPONENT = 0x010001

    private val MODULUS = BigInteger(
        1,
        hexDecode(
            "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b7251" +
                "52b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312e" +
                "cbda92557c93870114af6c9d05c4f7f0c3685d7a4f8",
        ),
    )
    private val secureRandom = SecureRandom()

    data class WeapiForm(val params: String, val encSecKey: String)
    data class EapiForm(val params: String)

    /** weapi 加密;[payloadJson] 为序列化后的 JSON 字符串。 */
    fun weapi(payloadJson: String, secretKey: String = randomSecretKey()): WeapiForm {
        val inner = aesCbcBase64(payloadJson, PRESET_KEY)
        val params = aesCbcBase64(inner, secretKey)
        return WeapiForm(params, rsaHex(secretKey.reversed()))
    }

    /** eapi 加密;[uri] 形如 "/api/song/enhance/player/url/v1"。 */
    fun eapi(uri: String, payloadJson: String): EapiForm {
        val digest = md5Hex("nobody${uri}use${payloadJson}md5forencrypt")
        val data = "$uri-$EAPI_DIGEST_MAGIC-$payloadJson-$EAPI_DIGEST_MAGIC-$digest"
        return EapiForm(aesEcbHex(data, EAPI_KEY))
    }

    private fun aesCbcBase64(plain: String, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(IV.toByteArray(Charsets.UTF_8)),
        )
        return Base64.getEncoder()
            .encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    private fun aesEcbHex(plain: String, key: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
        )
        return cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }
    }

    private fun rsaHex(text: String): String {
        val message = BigInteger(1, text.toByteArray(Charsets.UTF_8))
        return message.modPow(PUBLIC_EXPONENT.toBigInteger(), MODULUS).toString(16)
    }

    private fun md5Hex(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun randomSecretKey(): String {
        val sb = StringBuilder(16)
        repeat(16) {
            sb.append(BASE62[secureRandom.nextInt(BASE62.length)])
        }
        return sb.toString()
    }

    private fun hexDecode(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) +
                Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }
}
