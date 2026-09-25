package io.github.adkimsm.neteasedownloader.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 本地索引库(手写 SQLite,避免 Room/KSP 在 Kotlin 2.3.20 工具链下的版本耦合):
 *  - playlist:已拉取的歌单(含 creatorId/specialType,决定能否远端编辑)
 *  - song:歌曲元数据 + 本地下载状态
 *  - playlist_song:歌单与歌曲的多对多关联(同步差量的基准)
 *  - liked_song:红心歌曲(增删走 radio/like,与歌单曲目接口分开)
 *
 * 建表 SQL 抽成常量:onCreate 与 onUpgrade 共用同一份定义,避免两条路径漂移。
 */
class AppDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_PLAYLIST)
        db.execSQL(SQL_CREATE_SONG)
        db.execSQL(SQL_CREATE_PLAYLIST_SONG)
        db.execSQL(SQL_CREATE_PLAYLIST_SONG_INDEX)
        db.execSQL(SQL_CREATE_LIKED_SONG)
        db.execSQL(SQL_CREATE_LIKED_SONG_INDEX)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) migrateToV2(db)
        if (oldVersion < 3) migrateToV3(db)
    }

    /**
     * v1 → v2:新增红心表,并给 playlist 补 creatorId / specialType。
     *
     * 幂等:列已存在就不重复 ALTER(升级中断后重跑、或降级回装再升级都可能走到这里)。
     */
    private fun migrateToV2(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_LIKED_SONG)
        db.execSQL(SQL_CREATE_LIKED_SONG_INDEX)
        if (!db.hasColumn(TABLE_PLAYLIST, "creatorId")) {
            db.execSQL("ALTER TABLE $TABLE_PLAYLIST ADD COLUMN creatorId INTEGER NOT NULL DEFAULT 0")
        }
        if (!db.hasColumn(TABLE_PLAYLIST, "specialType")) {
            db.execSQL("ALTER TABLE $TABLE_PLAYLIST ADD COLUMN specialType INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * v2 → v3:给 song 补 source —— 这份本地文件是用哪个第三方音源下载的。
     *
     * 与 v2 同法:幂等,升级中断后重跑不会重复 ALTER。
     */
    private fun migrateToV3(db: SQLiteDatabase) {
        if (!db.hasColumn(TABLE_SONG, "source")) {
            db.execSQL("ALTER TABLE $TABLE_SONG ADD COLUMN source TEXT")
        }
    }

    private fun SQLiteDatabase.hasColumn(table: String, column: String): Boolean =
        rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            if (nameIndex < 0) return@use false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return@use true
            }
            false
        }

    companion object {
        private const val DB_NAME = "watchmusic.db"
        private const val DB_VERSION = 3

        private const val TABLE_PLAYLIST = "playlist"
        private const val TABLE_SONG = "song"

        private val SQL_CREATE_PLAYLIST = """
            CREATE TABLE IF NOT EXISTS playlist (
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                cover TEXT,
                trackCount INTEGER NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 0,
                lastSyncAt INTEGER,
                creatorId INTEGER NOT NULL DEFAULT 0,
                specialType INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent()

        private val SQL_CREATE_SONG = """
            CREATE TABLE IF NOT EXISTS song (
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
                source TEXT,
                updatedAt INTEGER NOT NULL
            )
        """.trimIndent()

        private val SQL_CREATE_PLAYLIST_SONG = """
            CREATE TABLE IF NOT EXISTS playlist_song (
                playlistId INTEGER NOT NULL,
                songId INTEGER NOT NULL,
                sortIndex INTEGER NOT NULL,
                PRIMARY KEY (playlistId, songId)
            )
        """.trimIndent()

        private const val SQL_CREATE_PLAYLIST_SONG_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_playlist_song_song ON playlist_song(songId)"

        private val SQL_CREATE_LIKED_SONG = """
            CREATE TABLE IF NOT EXISTS liked_song (
                songId INTEGER PRIMARY KEY,
                likedAt INTEGER NOT NULL
            )
        """.trimIndent()

        private const val SQL_CREATE_LIKED_SONG_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_liked_song_at ON liked_song(likedAt)"
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
