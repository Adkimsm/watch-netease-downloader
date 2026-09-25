package io.github.adkimsm.neteasedownloader.net.unlock

/**
 * 单测 fixture 读取。
 *
 * 用真实抓取的响应体当 fixture(而不是手搓 JSON),因为这些第三方接口的怪癖
 * 恰恰在手搓样本里体现不出来 —— 比如酷我条目里嵌着 `audiobookpayinfo:{...}`,
 * 手搓的扁平样本会让"按 `},{` 硬切"这种错误实现照样通过。
 */
internal object Fixtures {
    fun text(name: String): String =
        checkNotNull(Fixtures::class.java.getResourceAsStream("/fixtures/$name")) {
            "fixture 缺失: $name"
        }.use { it.readBytes().toString(Charsets.UTF_8) }
}

internal fun hex(value: String): ByteArray {
    require(value.length % 2 == 0) { "hex 长度必须是偶数: $value" }
    return ByteArray(value.length / 2) { index ->
        ((Character.digit(value[index * 2], 16) shl 4) +
            Character.digit(value[index * 2 + 1], 16)).toByte()
    }
}
