package io.github.adkimsm.neteasedownloader.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * 把下载好的音乐写入公共 MediaStore(Audio),系统/第三方播放器可直接扫描。
 * 目录:Music/WatchMusic/。自建行免存储权限(API 29+)。
 */
class MediaStoreWriter(private val context: Context) {

    private val resolver = context.contentResolver
    private val collection: Uri = MediaStore.Audio.Media.getContentUri(
        MediaStore.VOLUME_EXTERNAL_PRIMARY,
    )

    fun collectionUri(): Uri = collection

    fun deleteByUriString(uriString: String) {
        runCatching { resolver.delete(Uri.parse(uriString), null, null) }
    }

    /**
     * 预占位插入一条待下载记录,返回其 Uri;下载成功后调 [markDone],失败调 [delete]。
     */
    fun insertPending(
        displayName: String,
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
        mimeType: String,
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.ARTIST, artist)
            album?.let { put(MediaStore.Audio.Media.ALBUM, it) }
            put(MediaStore.Audio.Media.DURATION, durationMs)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, REL_ROOT)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        return runCatching { resolver.insert(collection, values) }.getOrNull()
    }

    fun markDone(uri: Uri, size: Long) {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.IS_PENDING, 0)
            put(MediaStore.Audio.Media.SIZE, size)
        }
        resolver.update(uri, values, null, null)
    }

    fun delete(uri: Uri) {
        resolver.delete(uri, null, null)
    }

    /** 批量查询指定 uri 的 DISPLAY_NAME,返回 uriString → 文件名 */
    fun displayNameByUris(uriStrings: Set<String>): Map<String, String> {
        if (uriStrings.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
        )
        runCatching {
            resolver.query(collection, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val uriString = Uri.withAppendedPath(
                        collection,
                        cursor.getLong(0).toString(),
                    ).toString()
                    if (uriString in uriStrings) {
                        cursor.getString(1)?.let { out[uriString] = it }
                    }
                }
            }
        }
        return out
    }

    /** 重命名 MediaStore 条目(IS_PENDING=0 也允许);返回是否成功 */
    fun rename(uri: Uri, displayName: String): Boolean = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
        }
        resolver.update(uri, values, null, null) > 0
    }.getOrDefault(false)

    /**
     * 删除本应用在 MediaStore 中的孤儿记录:
     * 数据库里已不存在(或 state 非 OK)但 MediaStore 里还留着的 WatchMusic 文件。
     * 返回被删除的记录条数。
     */
    fun deleteOrphans(validUriStrings: Set<String>): Int {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.IS_PENDING,
        )
        var deleted = 0
        resolver.query(collection, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val uri = Uri.withAppendedPath(collection, id.toString())
                if (uri.toString() !in validUriStrings) {
                    resolver.delete(uri, null, null)
                    deleted++
                }
            }
        }
        return deleted
    }

    companion object {
        const val REL_ROOT = "Music/WatchMusic"
    }
}
