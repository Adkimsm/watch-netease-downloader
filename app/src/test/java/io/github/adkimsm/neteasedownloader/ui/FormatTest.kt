package io.github.adkimsm.neteasedownloader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * formatBytes 的回归测试。
 *
 * 重点覆盖原实现的缺陷:整数除法 bytes / MB 在小于 1MB 时一律得到 0,
 * 导致一首约 500KB 的歌曲在预览页显示 "0MB"。
 */
class FormatTest {

    @Test
    fun bytesBelowOneKb() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1023 B", formatBytes(1023))
    }

    @Test
    fun kbAndMbCarryBoundary() {
        assertEquals("1 KB", formatBytes(1024))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("1024 KB", formatBytes(1024L * 1024 - 1))
        assertEquals("1 MB", formatBytes(1024L * 1024))
    }

    /** 旧实现 500_000 / (1024*1024) == 0,会显示 "0 MB" */
    @Test
    fun subMegabyteIsNotRoundedToZero() {
        val text = formatBytes(500_000L)
        assertTrue("应显示为非 0 的 KB/MB,实际=$text", text != "0 MB" && text != "0 B")
        assertEquals("488.3 KB", text)
    }

    @Test
    fun subMegabyteDoesNotDegradeToZero() {
        assertEquals("293 KB", formatBytes(300_000))
        assertFalse(formatBytes(300_000).startsWith("0 "))
    }

    @Test
    fun gigaByteAndExtremeValues() {
        assertEquals("1 GB", formatBytes(1024L * 1024 * 1024))
        assertEquals("3.4 GB", formatBytes((3.4 * 1024 * 1024 * 1024).toLong()))
        assertTrue(formatBytes(Long.MAX_VALUE).endsWith("GB"))
    }

    @Test
    fun negativeBytesClampToZero() {
        assertEquals("0 B", formatBytes(-1))
    }

    @Test
    fun durationFormatting() {
        assertEquals("--:--", formatDuration(0))
        assertEquals("--:--", formatDuration(-5))
        assertEquals("0:05", formatDuration(5_000))
        assertEquals("3:20", formatDuration(200_000))
        assertEquals("61:05", formatDuration(3_665_000))
    }

    @Test
    fun etaFormatting() {
        assertEquals("即将完成", formatEta(0))
        assertEquals("不到 1 分钟", formatEta(30))
        assertEquals("剩余约 5 分钟", formatEta(5 * 60))
        assertEquals("剩余约 1 小时", formatEta(3600))
        assertEquals("剩余约 3.2 小时", formatEta((3.2 * 3600).toLong()))
    }

    @Test
    fun percentIsNullWhenTotalIsZero() {
        assertNull(progressPercent(0, 0))
        assertNull(progressPercent(5, 0))
        assertEquals(0, progressPercent(0, 10))
        assertEquals(50, progressPercent(5, 10))
        assertEquals(100, progressPercent(10, 10))
        // 超出总量时封顶,避免进度条越界
        assertEquals(100, progressPercent(11, 10))
    }

    @Test
    fun fractionIsClamped() {
        assertNull(progressFraction(0, 0))
        assertEquals(0.5f, progressFraction(5, 10)!!, 0.0001f)
        assertEquals(1f, progressFraction(20, 10)!!, 0.0001f)
    }

    @Test
    fun storageShortageMatchesEngineMargin() {
        // 需要超过可用空间的 95% 才算不足
        assertTrue(isStorageShort(needed = 1000, available = 1000))
        assertFalse(isStorageShort(needed = 900, available = 1000))
        assertFalse(isStorageShort(needed = 0, available = 1000))
    }
}
