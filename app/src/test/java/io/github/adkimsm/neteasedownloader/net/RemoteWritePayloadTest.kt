package io.github.adkimsm.neteasedownloader.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 远端写操作的请求体构造。
 *
 * 重点全在**转义**:`manipulate/tracks` 的 `trackIds` 与 `/api/batch` 都把 JSON
 * 当字符串再嵌进 JSON,歌名里只要出现一个引号或反斜杠,手工拼字符串就会把整个
 * 请求体写坏 —— 而且服务端多半只是静默返回 200,不报错。
 * 所以这些用例用真实的刁钻歌名做输入。
 */
class RemoteWritePayloadTest {

    private fun parse(json: String): JsonObject =
        NcmJson.parseToJsonElement(json) as JsonObject

    @Test
    fun manipulateTracks_carriesOpPidAndStringIds() {
        val body = parse(RemoteWritePayload.manipulateTracks("del", 123L, listOf(456L)))
        assertEquals("del", body["op"]?.jsonPrimitive?.content)
        assertEquals(123L, body["pid"]?.jsonPrimitive?.content?.toLong())
        // trackIds 的值必须是**字符串 id 的 JSON 字符串**(参考 playlist_tracks.js)
        assertEquals("""["456"]""", body["trackIds"]?.jsonPrimitive?.content)
    }

    @Test
    fun manipulateTracks_supportsMultipleSongs() {
        val body = parse(RemoteWritePayload.manipulateTracks("add", 1L, listOf(10L, 20L, 30L)))
        assertEquals("add", body["op"]?.jsonPrimitive?.content)
        assertEquals("""["10","20","30"]""", body["trackIds"]?.jsonPrimitive?.content)
        assertEquals("true", body["imme"]?.jsonPrimitive?.content)
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
    fun withDeviceHeader_addsHeaderObject() {
        val out = parse(
            RemoteWritePayload.withDeviceHeader("""{"id":1}""", mapOf("os" to "iPhone OS", "appver" to "9.0.90")),
        )
        val header = out["header"]?.jsonObject
        assertNotNull(header)
        assertEquals("iPhone OS", header?.get("os")?.jsonPrimitive?.content)
        assertEquals("9.0.90", header?.get("appver")?.jsonPrimitive?.content)
        assertEquals("1", out["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun withDeviceHeader_overwritesExistingHeader() {
        val out = parse(
            RemoteWritePayload.withDeviceHeader("""{"header":{"os":"old"},"id":1}""", mapOf("os" to "new")),
        )
        assertEquals("new", out["header"]?.jsonObject?.get("os")?.jsonPrimitive?.content)
        assertEquals(2, out.size)
    }

    @Test
    fun withDeviceHeader_leavesNonObjectPayloadUntouched() {
        assertEquals("not-json", RemoteWritePayload.withDeviceHeader("not-json", mapOf("os" to "x")))
    }

    @Test
    fun withDeviceHeader_emptyHeaderAddsNothing() {
        val out = parse(RemoteWritePayload.withDeviceHeader("""{"id":1}""", emptyMap()))
        assertFalse(out.containsKey("header"))
    }
}
