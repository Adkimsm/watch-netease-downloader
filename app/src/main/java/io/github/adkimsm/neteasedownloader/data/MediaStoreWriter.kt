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

    /**
     * 预占位插入一条待下载记录,返回其 Uri;下载成功后调 [markDone],失败调 [delete]。
     */
    fun insertPending(
        fileName: String,
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
        mimeType: String,
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
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

    companion object {
        const val REL_ROOT = "Music/WatchMusic"
    }
}
