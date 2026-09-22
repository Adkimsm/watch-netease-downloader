package io.github.adkimsm.neteasedownloader.data

import android.content.ContentValues
import android.database.Cursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
/** SQLite 绑定变量上限的保守取值;单条 IN 查询的 id 数量不得超过它 */
private const val SQL_BIND_LIMIT = 500

private fun Cursor.getLongByName(name: String): Long = getLong(getColumnIndexOrThrow(name))
private fun Cursor.getStringByName(name: String): String? = getString(getColumnIndexOrThrow(name))
private fun Cursor.getIntByName(name: String): Int = getInt(getColumnIndexOrThrow(name))

private fun Cursor.toPlaylist(): PlaylistEntity = PlaylistEntity(
    id = getLongByName("id"),
    name = getStringByName("name") ?: "",
    cover = getStringByName("cover"),
    trackCount = getIntByName("trackCount"),
    enabled = getIntByName("enabled") != 0,
    lastSyncAt = if (isNull(getColumnIndexOrThrow("lastSyncAt"))) null else getLongByName("lastSyncAt"),
    creatorId = getLongByName("creatorId"),
    specialType = getIntByName("specialType"),
)

private fun Cursor.toSong(): SongEntity = SongEntity(
    songId = getLongByName("songId"),
    name = getStringByName("name") ?: "",
    artist = getStringByName("artist") ?: "",
    album = getStringByName("album"),
    duration = getLongByName("duration"),
    md5 = getStringByName("md5"),
    size = getLongByName("size"),
    br = getLongByName("br"),
    type = getStringByName("type"),
    state = getStringByName("state") ?: SongState.FAILED.name,
    errorCode = getStringByName("errorCode"),
    localUri = getStringByName("localUri"),
    updatedAt = getLongByName("updatedAt"),
)

class PlaylistDao(private val db: AppDatabase) {
    suspend fun getAll(): List<PlaylistEntity> = withContext(Dispatchers.IO) {
        db.readableDatabase
            .rawQuery("SELECT * FROM playlist ORDER BY id", null)
            .use { cursor -> cursor.mapRows { it.toPlaylist() } }
    }

    suspend fun getEnabled(): List<PlaylistEntity> = withContext(Dispatchers.IO) {
        db.readableDatabase
            .rawQuery("SELECT * FROM playlist WHERE enabled = 1", null)
            .use { cursor -> cursor.mapRows { it.toPlaylist() } }
    }

    suspend fun upsertAll(playlists: List<PlaylistEntity>) = withContext(Dispatchers.IO) {
        db.writableDatabase.inTransaction {
            playlists.forEach { p ->
                val values = ContentValues().apply {
                    put("id", p.id)
                    put("name", p.name)
                    putIfNotNull("cover", p.cover)
                    put("trackCount", p.trackCount)
                    put("enabled", if (p.enabled) 1 else 0)
                    p.lastSyncAt?.let { put("lastSyncAt", it) }
                    put("creatorId", p.creatorId)
                    put("specialType", p.specialType)
                }
                insertWithOnConflict(
                    "playlist", null, values,
                    android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
        }
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        db.writableDatabase
            .execSQL("UPDATE playlist SET enabled = ? WHERE id = ?", arrayOf(if (enabled) 1 else 0, id))
    }

    suspend fun setLastSyncAt(id: Long, timestamp: Long) = withContext(Dispatchers.IO) {
        db.writableDatabase
            .execSQL("UPDATE playlist SET lastSyncAt = ? WHERE id = ?", arrayOf(timestamp, id))
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        db.writableDatabase.execSQL("DELETE FROM playlist WHERE id = ?", arrayOf(id))
    }
}

class SongDao(private val db: AppDatabase) {
    suspend fun getByIds(ids: List<Long>): List<SongEntity> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        // 分批查询:SQLite 的绑定变量上限(旧版安卓 999)会被三四千首的大歌单顶穿
        ids.distinct().chunked(SQL_BIND_LIMIT).flatMap { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            db.readableDatabase
                .rawQuery(
                    "SELECT * FROM song WHERE songId IN ($placeholders)",
                    chunk.map { it.toString() }.toTypedArray(),
                )
                .use { cursor -> cursor.mapRows { it.toSong() } }
        }
    }

    suspend fun getAllIds(): List<Long> = withContext(Dispatchers.IO) {
        db.readableDatabase
            .rawQuery("SELECT songId FROM song", null)
            .use { cursor -> cursor.mapRows { it.getLong(0) } }
    }

    suspend fun upsertAll(songs: List<SongEntity>) = withContext(Dispatchers.IO) {
        db.writableDatabase.inTransaction {
            songs.forEach { s ->
                val values = ContentValues().apply {
                    put("songId", s.songId)
                    put("name", s.name)
                    put("artist", s.artist)
                    putIfNotNull("album", s.album)
                    put("duration", s.duration)
                    putIfNotNull("md5", s.md5)
                    put("size", s.size)
                    put("br", s.br)
                    putIfNotNull("type", s.type)
                    put("state", s.state)
                    putIfNotNull("errorCode", s.errorCode)
                    putIfNotNull("localUri", s.localUri)
                    put("updatedAt", s.updatedAt)
                }
                insertWithOnConflict(
                    "song", null, values,
                    android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
        }
    }

    suspend fun updateState(songId: Long, state: SongState, errorCode: String? = null) =
        withContext(Dispatchers.IO) {
            db.writableDatabase.execSQL(
                "UPDATE song SET state = ?, errorCode = ?, updatedAt = ? WHERE songId = ?",
                arrayOf<Any?>(state.name, errorCode, System.currentTimeMillis(), songId),
            )
        }

    suspend fun updateLocalResult(
        songId: Long,
        state: SongState,
        localUri: String?,
        size: Long,
        md5: String?,
        br: Long,
        type: String?,
        errorCode: String? = null,
    ) = withContext(Dispatchers.IO) {
        db.writableDatabase.execSQL(
            """
            UPDATE song SET state = ?, localUri = ?, size = ?, md5 = ?, br = ?, type = ?,
            errorCode = ?, updatedAt = ? WHERE songId = ?
            """.trimIndent(),
            arrayOf<Any?>(
                state.name, localUri, size, md5, br, type, errorCode,
                System.currentTimeMillis(), songId,
            ),
        )
    }

    /** 上次同步中断留下的 DOWNLOADING 状态,重置为 PENDING 以便重试 */
    suspend fun resetStaleDownloading() = withContext(Dispatchers.IO) {
        db.writableDatabase.execSQL(
            "UPDATE song SET state = ? WHERE state = ?",
            arrayOf<Any?>(SongState.PENDING.name, SongState.DOWNLOADING.name),
        )
    }

    /**
     * 清掉本地文件的账:文件已被删/被移出时,保留歌曲元数据,只把本地态清空。
     *
     * 与 [deleteByIds] 的区别是**不删行** —— 未勾选歌单里的歌仍要能浏览、能串流,
     * 把行删了会导致每次进歌单详情都重新拉一遍。
     */
    suspend fun clearLocal(ids: List<Long>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        db.writableDatabase.inTransaction {
            ids.forEach { id ->
                execSQL(
                    "UPDATE song SET state = ?, localUri = NULL, md5 = NULL, size = 0, updatedAt = ? " +
                        "WHERE songId = ?",
                    arrayOf<Any?>(SongState.PENDING.name, now, id),
                )
            }
        }
    }

    suspend fun deleteByIds(ids: List<Long>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        db.writableDatabase.inTransaction {
            ids.forEach { id ->
                execSQL("DELETE FROM song WHERE songId = ?", arrayOf<Any>(id))
            }
        }
    }

    /**
     * 回收既没有歌单引用、也不是红心的 song 行。
     *
     * 浏览过的歌单会留下元数据缓存(以便未下载也能展示/串流),所以这里只清
     * "彻底没人要"的那些 —— 否则缓存会无限增长。
     */
    suspend fun deleteUnreferenced() = withContext(Dispatchers.IO) {
        db.writableDatabase.execSQL(
            """
            DELETE FROM song WHERE songId NOT IN (SELECT songId FROM playlist_song)
              AND songId NOT IN (SELECT songId FROM liked_song)
            """.trimIndent(),
        )
    }
}

class PlaylistSongDao(private val db: AppDatabase) {
    suspend fun songIdsForPlaylist(playlistId: Long): List<Long> = withContext(Dispatchers.IO) {
        db.readableDatabase
            .rawQuery(
                "SELECT songId FROM playlist_song WHERE playlistId = ? ORDER BY sortIndex",
                arrayOf(playlistId.toString()),
            )
            .use { cursor -> cursor.mapRows { it.getLong(0) } }
    }

    /** 所有启用歌单的歌曲 id 并集 */
    suspend fun enabledPlaylistSongIds(): List<Long> = withContext(Dispatchers.IO) {
        db.readableDatabase
            .rawQuery(
                """
                SELECT songId FROM playlist_song
                WHERE playlistId IN (SELECT id FROM playlist WHERE enabled = 1)
                """.trimIndent(),
                null,
            )
            .use { cursor -> cursor.mapRows { it.getLong(0) } }
    }

    /** 所有歌单(含未勾选)的歌曲 id 并集 —— 决定 song 行能否被回收 */
    suspend fun allSongIds(): List<Long> = withContext(Dispatchers.IO) {
        db.readableDatabase
            .rawQuery("SELECT DISTINCT songId FROM playlist_song", null)
            .use { cursor -> cursor.mapRows { it.getLong(0) } }
    }

    /** 一首歌出现在哪些歌单里(歌单名 + 归属),供删除范围面板使用 */
    suspend fun playlistsContaining(songId: Long): List<Pair<Long, String>> = withContext(Dispatchers.IO) {
        db.readableDatabase
            .rawQuery(
                """
                SELECT p.id AS id, p.name AS name FROM playlist_song ps
                JOIN playlist p ON p.id = ps.playlistId
                WHERE ps.songId = ?
                ORDER BY p.name
                """.trimIndent(),
                arrayOf(songId.toString()),
            )
            .use { cursor ->
                cursor.mapRows { it.getLongByName("id") to (it.getStringByName("name") ?: "") }
            }
    }

    suspend fun insertAll(entries: List<PlaylistSongEntity>) = withContext(Dispatchers.IO) {
        db.writableDatabase.inTransaction {
            entries.forEach { e ->
                execSQL(
                    "INSERT OR REPLACE INTO playlist_song (playlistId, songId, sortIndex) VALUES (?, ?, ?)",
                    arrayOf<Any>(e.playlistId, e.songId, e.sortIndex),
                )
            }
        }
    }

    suspend fun delete(playlistId: Long, songIds: List<Long>) = withContext(Dispatchers.IO) {
        if (songIds.isEmpty()) return@withContext
        db.writableDatabase.inTransaction {
            songIds.forEach { id ->
                execSQL(
                    "DELETE FROM playlist_song WHERE playlistId = ? AND songId = ?",
                    arrayOf<Any>(playlistId, id),
                )
            }
        }
    }

    suspend fun deleteByPlaylist(playlistId: Long) = withContext(Dispatchers.IO) {
        db.writableDatabase.execSQL("DELETE FROM playlist_song WHERE playlistId = ?", arrayOf(playlistId))
    }

    suspend fun refCount(songId: Long): Int = withContext(Dispatchers.IO) {
        db.readableDatabase
            .rawQuery("SELECT COUNT(*) FROM playlist_song WHERE songId = ?", arrayOf(songId.toString()))
            .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }
}

/**
 * 红心歌曲。
 *
 * 与 `playlist_song` 分开存:「我喜欢的音乐」的曲目在远端是一个歌单,但增删走
 * `radio/like` 而不是歌单曲目接口,本地也就需要一份独立的事实来源。
 */
class LikedSongDao(private val db: AppDatabase) {
    suspend fun allIds(): List<Long> = withContext(Dispatchers.IO) {
        db.readableDatabase
            .rawQuery("SELECT songId FROM liked_song ORDER BY likedAt DESC", null)
            .use { cursor -> cursor.mapRows { it.getLong(0) } }
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        db.readableDatabase
            .rawQuery("SELECT COUNT(*) FROM liked_song", null)
            .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }

    /** 远端全量覆盖。以服务端为准,本地多余的删掉、缺的补上。 */
    suspend fun replaceAll(songIds: List<Long>, likedAt: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            val wanted = songIds.toHashSet()
            db.writableDatabase.inTransaction {
                wanted.forEach { id ->
                    execSQL(
                        "INSERT OR REPLACE INTO liked_song (songId, likedAt) VALUES (?, ?)",
                        arrayOf<Any>(id, likedAt),
                    )
                }
                rawQuery("SELECT songId FROM liked_song", null).use { cursor ->
                    val stale = buildList {
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(0)
                            if (id !in wanted) add(id)
                        }
                    }
                    stale.forEach { execSQL("DELETE FROM liked_song WHERE songId = ?", arrayOf<Any>(it)) }
                }
            }
        }

    suspend fun setLiked(songId: Long, liked: Boolean, at: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            if (liked) {
                db.writableDatabase.execSQL(
                    "INSERT OR REPLACE INTO liked_song (songId, likedAt) VALUES (?, ?)",
                    arrayOf<Any>(songId, at),
                )
            } else {
                db.writableDatabase.execSQL("DELETE FROM liked_song WHERE songId = ?", arrayOf<Any>(songId))
            }
        }

    suspend fun likedSet(): Set<Long> = allIds().toHashSet()

    /** 单曲判定。别用 likedSet().contains() —— 红心几千首时那是白读一整张表。 */
    suspend fun isLiked(songId: Long): Boolean = withContext(Dispatchers.IO) {
        db.readableDatabase
            .rawQuery("SELECT 1 FROM liked_song WHERE songId = ? LIMIT 1", arrayOf(songId.toString()))
            .use { cursor -> cursor.moveToFirst() }
    }
}

private inline fun <T> Cursor.mapRows(mapper: (Cursor) -> T): List<T> {
    val out = ArrayList<T>(count)
    while (moveToNext()) out.add(mapper(this))
    return out
}
