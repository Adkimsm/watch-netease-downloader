package io.github.adkimsm.neteasedownloader.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 三表本地索引库(手写 SQLite,避免 Room/KSP 在 Kotlin 2.3.20 工具链下的版本耦合):
 *  - playlist:已拉取的歌单
 *  - song:歌曲元数据 + 本地下载状态
 *  - playlist_song:歌单与歌曲的多对多关联(同步差量的基准)
 */
class AppDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE playlist (
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                cover TEXT,
                trackCount INTEGER NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 0,
                lastSyncAt INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE song (
                songId INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT,
                duration INTEGER NOT NULL,
                md5 TEXT,
                size INTEGER NOT NULL,
                br INTEGER NOT NULL,
                type TEXT,
                state TEXT NOT NULL,
                errorCode TEXT,
                localUri TEXT,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE playlist_song (
                playlistId INTEGER NOT NULL,
                songId INTEGER NOT NULL,
                sortIndex INTEGER NOT NULL,
                PRIMARY KEY (playlistId, songId)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_playlist_song_song ON playlist_song(songId)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 预留:后续版本迁移
    }

    companion object {
        private const val DB_NAME = "watchmusic.db"
        private const val DB_VERSION = 1
    }
}

/** 小工具:在写事务中批量执行 */
inline fun <T> SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    try {
        val result = block()
        setTransactionSuccessful()
        return result
    } finally {
        endTransaction()
    }
}

fun ContentValues.putIfNotNull(key: String, value: String?) {
    if (value != null) put(key, value)
}
