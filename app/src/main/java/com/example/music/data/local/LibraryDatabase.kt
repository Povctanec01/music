package com.example.music.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.music.data.preferences.CachedTrack
import com.example.music.domain.model.DownloadTask
import com.example.music.domain.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class DownloadSnapshot(val folder: String?, val tasks: List<DownloadTask>, val paused: Boolean = true)

data class TrashEntry(val id: Long, val folder: String, val originalPath: String, val hiddenPath: String)

/** Private, transactional storage; audio and large library snapshots never go into DataStore. */
class LibraryDatabase(context: Context, private val json: Json, name: String = "library.db") : SQLiteOpenHelper(context, name, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE tracks(folder TEXT NOT NULL, path TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(folder,path))")
        db.execSQL("CREATE TABLE folders(folder TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE downloads(id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE trash(id INTEGER PRIMARY KEY AUTOINCREMENT, folder TEXT NOT NULL, original TEXT NOT NULL, hidden TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized fun hasLibrary(folder: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM folders WHERE folder=?", arrayOf(folder),
    ).use { it.moveToFirst() }

    @Synchronized fun tracks(folder: String): List<Track> = readableDatabase.rawQuery(
        "SELECT payload FROM tracks WHERE folder=? ORDER BY path", arrayOf(folder),
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(json.decodeFromString<CachedTrack>(cursor.getString(0)).toTrack())
    } }

    @Synchronized fun saveTracks(folder: String, tracks: List<Track>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = mutableMapOf<String, String>()
            db.rawQuery("SELECT path,payload FROM tracks WHERE folder=?", arrayOf(folder)).use { cursor ->
                while (cursor.moveToNext()) existing[cursor.getString(0)] = cursor.getString(1)
            }
            for (track in tracks) {
                val payload = json.encodeToString(CachedTrack.fromTrack(track))
                if (existing.remove(track.relativePath) != payload) {
                    db.execSQL("INSERT OR REPLACE INTO tracks(folder,path,payload) VALUES(?,?,?)",
                        arrayOf(folder, track.relativePath, payload))
                }
            }
            for (path in existing.keys) db.delete("tracks", "folder=? AND path=?", arrayOf(folder, path))
            db.execSQL("INSERT OR IGNORE INTO folders(folder) VALUES(?)", arrayOf(folder))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun downloadSnapshot(): DownloadSnapshot? = readableDatabase.rawQuery(
        "SELECT payload FROM downloads WHERE id=1", null,
    ).use { cursor -> if (cursor.moveToFirst()) json.decodeFromString<DownloadSnapshot>(cursor.getString(0)) else null }

    @Synchronized fun saveDownloads(snapshot: DownloadSnapshot) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO downloads(id,payload) VALUES(1,?)",
            arrayOf(json.encodeToString(snapshot)))
    }

    @Synchronized fun addTrash(folder: String, original: String, hidden: String): Long = writableDatabase.insertOrThrow(
        "trash", null, ContentValues().apply { put("folder", folder); put("original", original); put("hidden", hidden) },
    )
    @Synchronized fun lastTrash(): TrashEntry? = readableDatabase.rawQuery(
        "SELECT id,folder,original,hidden FROM trash ORDER BY id DESC LIMIT 1", null,
    ).use { if (it.moveToFirst()) TrashEntry(it.getLong(0), it.getString(1), it.getString(2), it.getString(3)) else null }
    @Synchronized fun removeTrash(id: Long) { writableDatabase.delete("trash", "id=?", arrayOf(id.toString())) }
}
