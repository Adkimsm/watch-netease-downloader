package io.github.adkimsm.neteasedownloader.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌单名校验与「加入歌单」候选。
 *
 * 手表上敲字本来就难受,所以能在本地判定的输入问题一律本地判定 ——
 * 提交后才知道"名字不能为空"是最差的反馈。
 */
class PlaylistNamesTest {

    @Test
    fun checkPlaylistName_acceptsNormalChineseName() {
        assertEquals(NameCheck.Ok("我的跑步歌单"), checkPlaylistName("我的跑步歌单"))
    }

    @Test
    fun checkPlaylistName_trimsSurroundingWhitespace() {
        assertEquals(NameCheck.Ok("歌单"), checkPlaylistName("  歌单\n"))
    }

    @Test
    fun checkPlaylistName_trimsIdeographicSpace() {
        // 手表输入法很容易打出全角空格,trim() 默认不处理它
        assertEquals(NameCheck.Ok("歌单"), checkPlaylistName("\u3000歌单\u3000"))
    }

    @Test
    fun checkPlaylistName_rejectsBlank() {
        assertEquals(NameCheck.Blank, checkPlaylistName(""))
        assertEquals(NameCheck.Blank, checkPlaylistName("   "))
        assertEquals(NameCheck.Blank, checkPlaylistName("\u3000"))
    }

    @Test
    fun checkPlaylistName_rejectsTooLong() {
        val name = "歌".repeat(PLAYLIST_NAME_MAX + 1)
        assertEquals(NameCheck.TooLong, checkPlaylistName(name))
    }

    @Test
    fun checkPlaylistName_acceptsExactlyAtLimit() {
        val name = "歌".repeat(PLAYLIST_NAME_MAX)
        assertEquals(NameCheck.Ok(name), checkPlaylistName(name))
    }

    @Test
    fun renameIsNoop_detectsUnchangedName() {
        assertTrue(renameIsNoop("老名字", "老名字"))
        assertTrue("只改了空白不算改动", renameIsNoop("老名字", " 老名字 "))
    }

    @Test
    fun renameIsNoop_falseWhenNameChanged() {
        assertFalse(renameIsNoop("老名字", "新名字"))
    }

    @Test
    fun renameIsNoop_falseWhenInputIsInvalid() {
        // 非法输入不该被当成"没变化"从而静默什么都不做 —— 要让用户看到报错
        assertFalse(renameIsNoop("老名字", ""))
        assertFalse(renameIsNoop("老名字", "歌".repeat(PLAYLIST_NAME_MAX + 1)))
    }

    @Test
    fun addToPlaylistTargets_excludesOthersAndLiked() {
        val targets = addToPlaylistTargets(
            listOf(
                AddTarget(1L, "我的歌单", owned = true, liked = false),
                AddTarget(2L, "收藏的他人歌单", owned = false, liked = false),
                AddTarget(3L, "我喜欢的音乐", owned = true, liked = true),
                AddTarget(4L, "另一个我的歌单", owned = true, liked = false),
            ),
        )
        assertEquals(listOf(1L, 4L), targets.map { it.playlistId })
    }

    @Test
    fun addToPlaylistTargets_emptyWhenNothingIsOwned() {
        val targets = addToPlaylistTargets(
            listOf(AddTarget(9L, "别人给的", owned = false, liked = false)),
        )
        assertTrue(targets.isEmpty())
    }
}
