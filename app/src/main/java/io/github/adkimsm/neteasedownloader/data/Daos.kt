package io.github.adkimsm.neteasedownloader.data

import android.content.ContentValues
import android.database.Cursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        val placeholders = ids.joinToString(",") { "?" }
        db.readableDatabase
            .rawQuery(
                "SELECT * FROM song WHERE songId IN ($placeholders)",
                ids.map { it.toString() }.toTypedArray(),
            )
            .use { cursor -> cursor.mapRows { it.toSong() } }
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

    suspend fun deleteByIds(ids: List<Long>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        db.writableDatabase.inTransaction {
            ids.forEach { id ->
                execSQL("DELETE FROM song WHERE songId = ?", arrayOf<Any>(id))
            }
        }
    }
}

class PlaylistSongDao(private val db: AppDatabase) {
    suspend fun songIdsForPlaylist(playlistId: Long): List<Long> = withContext(Dispatchers.IO) {
        db.readableDatabase
            .rawQuery("SELECT songId FROM playlist_song WHERE playlistId = ?", arrayOf(playlistId.toString()))
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

private inline fun <T> Cursor.mapRows(mapper: (Cursor) -> T): List<T> {
    val out = ArrayList<T>(count)
    while (moveToNext()) out.add(mapper(this))
    return out
}
