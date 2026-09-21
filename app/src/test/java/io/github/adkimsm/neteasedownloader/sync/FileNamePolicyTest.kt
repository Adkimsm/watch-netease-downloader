package io.github.adkimsm.neteasedownloader.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FileNamePolicy 的回归测试:重点盯旧实现的问题——
 * songId 前缀、take 把扩展名一起截掉、非法字符/空白/多歌手分隔符的处理。
 */
class FileNamePolicyTest {

    @Test
    fun basicFormatIsTitleDashArtist() {
        assertEquals("晴天 - 周杰伦.mp3", FileNamePolicy.build(186016L, "晴天", "周杰伦", "mp3"))
    }

    @Test
    fun nullTypeFallsBackToMp3() {
        assertEquals("晴天 - 周杰伦.mp3", FileNamePolicy.build(1L, "晴天", "周杰伦", null))
    }

    @Test
    fun typeIsLowercased() {
        assertEquals("晴天 - 周杰伦.flac", FileNamePolicy.build(1L, "晴天", "周杰伦", "FLAC"))
    }

    @Test
    fun multiArtistSlashBecomesEnumeration() {
        assertEquals(
            "千里之外 - 周杰伦、费玉清.mp3",
            FileNamePolicy.build(1L, "千里之外", "周杰伦/费玉清", "mp3"),
        )
    }

    @Test
    fun illegalCharsReplacedWithUnderscore() {
        val name = FileNamePolicy.build(1L, "A\\B/C:D*E?F\"G<H>I|J", "歌手", "mp3")
        listOf('\\', '/', ':', '*', '?', '"', '<', '>', '|').forEach {
            assertFalse("包含非法字符 $it: $name", name.contains(it))
        }
        assertTrue(name.startsWith("A_B_C_D_E_F_G_H_I_J - 歌手.mp3"))
    }

    @Test
    fun controlCharsReplaced() {
        val name = FileNamePolicy.build(1L, "晴\t天", "周\n杰伦", "mp3")
        assertEquals("晴_天 - 周_杰伦.mp3", name)
    }

    @Test
    fun emptyNameFallsBack() {
        assertEquals("未知歌名 - 周杰伦.mp3", FileNamePolicy.build(1L, "", "周杰伦", "mp3"))
        // 全非法字符净化后也算空
        assertEquals("未知歌名 - 周杰伦.mp3", FileNamePolicy.build(1L, "///", "周杰伦", "mp3"))
    }

    @Test
    fun emptyArtistFallsBack() {
        assertEquals("晴天 - 未知歌手.mp3", FileNamePolicy.build(1L, "晴天", "", "mp3"))
    }

    @Test
    fun bothEmptyUsesSongId() {
        assertEquals("song_123.mp3", FileNamePolicy.build(123L, "", "", null))
    }

    @Test
    fun trimsLeadingAndTrailingJunk() {
        assertEquals("晴天 - 周杰伦.mp3", FileNamePolicy.build(1L, "  晴天. ", " 周杰伦 ", "mp3"))
    }

    @Test
    fun longNameKeepsExtension() {
        val long = "歌".repeat(200)
        val name = FileNamePolicy.build(1L, long, long, "flac")
        assertTrue("长度超限: ${name.length}", name.length <= FileNamePolicy.MAX_FILE_NAME)
        assertTrue("扩展名被截掉: $name", name.endsWith(".flac"))
    }

    @Test
    fun truncationLeavesNoTrailingDash() {
        // 主体被截断时不能以「 - 」「 」「.」等残尾结尾
        val name = FileNamePolicy.build(1L, "晴".repeat(200), "周".repeat(200), "mp3")
        assertFalse(
            "以残尾结尾: $name",
            name.endsWith(" -") || name.endsWith(" ") || name.endsWith("."),
        )
        assertTrue(name.endsWith(".mp3"))
    }

    @Test
    fun whitespaceCollapsed() {
        assertEquals("A B - C.mp3", FileNamePolicy.build(1L, "A  B", "C", "mp3"))
    }

    @Test
    fun internalDotKept() {
        assertEquals("2.14 - 周杰伦.mp3", FileNamePolicy.build(1L, "2.14", "周杰伦", "mp3"))
    }
}
