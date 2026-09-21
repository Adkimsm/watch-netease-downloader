package io.github.adkimsm.neteasedownloader.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
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

    /** 读取条目内容(用于探测已有标签);失败返回 null */
    fun openRead(uri: Uri): InputStream? = runCatching { resolver.openInputStream(uri) }.getOrNull()

    /**
     * 就地重写条目内容:先把 transform 的结果写进 cacheDir 临时文件,成功后再整段写回原 Uri。
     * 返回最终字节数;任何失败返回 -1,且**原文件不会被截断**(临时文件在 finally 里删掉)。
     *
     * 两次空间预检都必要:临时文件占一个源文件大小(cacheDir),回写占一个新文件大小(目标卷);
     * 空间不足时宁可不写,也不能截断后写一半把文件写坏。
     */
    fun rewrite(uri: Uri, transform: (InputStream, OutputStream) -> Unit): Long {
        val sourceSize = runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull() ?: -1L
        if (sourceSize > 0 && !cacheHasRoomFor(sourceSize)) return -1L

        val temp = runCatching { File.createTempFile("retag-", ".tmp", context.cacheDir) }.getOrNull()
            ?: return -1L
        try {
            FileOutputStream(temp).use { out ->
                val source = resolver.openInputStream(uri) ?: return -1L
                source.use { transform(it, out) }
                out.flush()
                out.fd.sync()
            }
            if (!copyBack(uri, temp)) return -1L
            return temp.length()
        } catch (_: Throwable) {
            return -1L
        } finally {
            temp.delete()
        }
    }

    private fun copyBack(uri: Uri, temp: File): Boolean {
        if (!targetHasRoomFor(temp.length())) return false
        val output = openTruncatingOutput(uri) ?: return false
        return runCatching {
            output.use { out -> temp.inputStream().use { it.copyTo(out) } }
        }.isSuccess
    }

    /** 截断写回的通道:优先 "wt",不被支持时退回 ParcelFileDescriptor 的 "rwt" */
    private fun openTruncatingOutput(uri: Uri): OutputStream? {
        runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()?.let { return it }
        val descriptor = runCatching { resolver.openFileDescriptor(uri, "rwt") }.getOrNull()
            ?: return null
        return runCatching { FileOutputStream(descriptor.fileDescriptor) }
            .onFailure { runCatching { descriptor.close() } }
            .getOrNull()
    }

    /** 临时文件落在 cacheDir:失败只是补标签失败,原文件不受影响,故取不到空间信息时放行 */
    private fun cacheHasRoomFor(bytes: Long): Boolean = runCatching {
        StatFs(context.cacheDir.absolutePath).availableBytes >= (bytes * SPACE_MARGIN).toLong()
    }.getOrDefault(true)

    /** 回写目标卷:取不到空间信息时保守拒绝,避免截断原文件 */
    private fun targetHasRoomFor(bytes: Long): Boolean = runCatching {
        StatFs(Environment.getExternalStorageDirectory().absolutePath).availableBytes >=
            (bytes * SPACE_MARGIN).toLong()
    }.getOrDefault(false)

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

        /** 回写/临时文件都按 1.2 倍留余量,避免刚好卡在边界上写到一半 */
        private const val SPACE_MARGIN = 1.2
    }
}
