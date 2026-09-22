package io.github.adkimsm.neteasedownloader.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 远端写操作的请求体构造。
 *
 * 重点全在**转义**:`tracks` 与 `/api/batch` 都把 JSON 当字符串再嵌进 JSON,
 * 歌名里只要出现一个引号或反斜杠,手工拼字符串就会把整个请求体写坏 ——
 * 而且服务端多半只是静默返回 200,不报错。所以这些用例用真实的刁钻歌名做输入。
 */
class RemoteWritePayloadTest {

    private fun parse(json: String): JsonObject =
        NcmJson.parseToJsonElement(json) as JsonObject

    @Test
    fun playlistTrackOp_wrapsRefsAsJsonString() {
        val body = parse(RemoteWritePayload.playlistTrackOp(123L, listOf(456L)))
        assertEquals(123L, body["id"]?.jsonPrimitive?.content?.toLong())
        // tracks 的值必须是一个**字符串**,内容是 JSON 数组
        assertEquals("""[{"type":3,"id":456}]""", body["tracks"]?.jsonPrimitive?.content)
    }

    @Test
    fun playlistTrackOp_supportsMultipleSongs() {
        val body = parse(RemoteWritePayload.playlistTrackOp(1L, listOf(10L, 20L, 30L)))
        assertEquals("""[{"type":3,"id":10},{"type":3,"id":20},{"type":3,"id":30}]""", body["tracks"]?.jsonPrimitive?.content)
    }

    @Test
    fun playlistRemove_wrapsIdsAsJsonString() {
        val body = parse(RemoteWritePayload.playlistRemove(listOf(1L, 2L)))
        assertEquals("[1,2]", body["ids"]?.jsonPrimitive?.content)
    }

    @Test
    fun playlistCreate_carriesNameAndDefaults() {
        val body = parse(RemoteWritePayload.playlistCreate("我的新歌单"))
        assertEquals("我的新歌单", body["name"]?.jsonPrimitive?.content)
        assertEquals("0", body["privacy"]?.jsonPrimitive?.content)
        assertEquals("NORMAL", body["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun like_falseIsABooleanNotAString() {
        val body = parse(RemoteWritePayload.like(456L, liked = false))
        assertEquals("456", body["trackId"]?.jsonPrimitive?.content)
        assertEquals("false", body["like"]?.jsonPrimitive?.content)
        assertEquals("itembased", body["alg"]?.jsonPrimitive?.content)
        assertEquals("3", body["time"]?.jsonPrimitive?.content)
    }

    @Test
    fun like_trueIsABoolean() {
        val body = parse(RemoteWritePayload.like(1L, liked = true))
        assertEquals("true", body["like"]?.jsonPrimitive?.content)
    }

    @Test
    fun likedIds_carriesUid() {
        val body = parse(RemoteWritePayload.likedIds(999L))
        assertEquals(999L, body["uid"]?.jsonPrimitive?.content?.toLong())
    }

    @Test
    fun batchRename_usesEndpointPathAsKey() {
        val body = parse(RemoteWritePayload.batchRename(123L, "新名字"))
        assertTrue("键必须就是端点路径", RemoteWritePayload.BATCH_RENAME_PATH in body)
        assertEquals(
            """{"id":123,"name":"新名字"}""",
            body[RemoteWritePayload.BATCH_RENAME_PATH]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun batchRename_escapesQuotesInPlaylistName() {
        // 这一条是回归闸门:手工拼字符串在这里必然写坏
        val name = "他叫\"小明\""
        val body = parse(RemoteWritePayload.batchRename(1L, name))
        val inner = body[RemoteWritePayload.BATCH_RENAME_PATH]?.jsonPrimitive?.content.orEmpty()

        // 内层仍然是**合法 JSON**,而不是被引号截断的碎片
        val innerObj = parse(inner)
        assertEquals(name, innerObj["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun batchRename_escapesBackslashesAndNewlines() {
        val name = "a\\b\nc\td"
        val inner = parse(RemoteWritePayload.batchRename(7L, name))[RemoteWritePayload.BATCH_RENAME_PATH]
            ?.jsonPrimitive?.content.orEmpty()
        assertEquals(name, parse(inner)["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun batchRename_keepsChineseAndEmoji() {
        val name = "周杰伦 🎵 晴天"
        val inner = parse(RemoteWritePayload.batchRename(7L, name))[RemoteWritePayload.BATCH_RENAME_PATH]
            ?.jsonPrimitive?.content.orEmpty()
        assertEquals(name, parse(inner)["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun withCsrfToken_addsField() {
        val out = parse(RemoteWritePayload.withCsrfToken("""{"id":1}""", "csrf-abc"))
        assertEquals("csrf-abc", out["csrf_token"]?.jsonPrimitive?.content)
        assertEquals("1", out["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun withCsrfToken_overwritesExistingField() {
        val out = parse(RemoteWritePayload.withCsrfToken("""{"csrf_token":"old","id":1}""", "new"))
        assertEquals("new", out["csrf_token"]?.jsonPrimitive?.content)
        assertEquals(2, out.size)
    }

    @Test
    fun withCsrfToken_leavesNonObjectPayloadUntouched() {
        assertEquals("not-json", RemoteWritePayload.withCsrfToken("not-json", "csrf"))
    }

    @Test
    fun withCsrfToken_emptyCsrfStillAddsField() {
        // 未登录时也不能省这个字段:省了服务端直接 401,报错更难定位
        val out = parse(RemoteWritePayload.withCsrfToken("""{"id":1}""", ""))
        assertFalse(out.isEmpty())
        assertEquals("", out["csrf_token"]?.jsonPrimitive?.content)
    }
}
