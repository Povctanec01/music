package com.example.music.domain.art

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.security.MessageDigest
import android.util.AtomicFile
import java.io.IOException

/** Persistent on-device cache of cover art downloaded from the music server. */
class TrackArtDiskStore(context: Context) {
    private val dir = File(context.filesDir, "track_art")

    init {
        dir.mkdirs()
    }

    fun loadBitmap(cacheKey: String): Bitmap? = synchronized(lock) {
        val file = fileForKey(cacheKey)
        val atomic = AtomicFile(file)
        if (!file.exists() && !File(file.path + ".bak").exists()) return@synchronized null
        val bitmap = runCatching { atomic.openRead().use { TrackArtCache.decode(it.readBytes()) } }.getOrNull()
        if (bitmap == null || bitmap.width <= 1 || bitmap.height <= 1) {
            bitmap?.recycle()
            atomic.delete()
            return@synchronized null
        }
        bitmap
    }

    fun save(cacheKey: String, bytes: ByteArray): File? {
        val decoded = TrackArtCache.decode(bytes) ?: return null
        return try {
            saveBitmap(cacheKey, decoded)
        } finally {
            decoded.recycle()
        }
    }

    fun saveBitmap(cacheKey: String, bitmap: Bitmap): File? = synchronized(lock) {
        if (bitmap.width <= 1 || bitmap.height <= 1) return@synchronized null
        val file = fileForKey(cacheKey)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)) throw IOException("Cannot encode cover")
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
        file.takeIf { it.length() > 128L }
    }

    fun delete(cacheKey: String) = synchronized(lock) {
        AtomicFile(fileForKey(cacheKey)).delete()
    }

    private fun fileForKey(cacheKey: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(cacheKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return File(dir, "$digest.jpg")
    }

    companion object { private val lock = Any() }
}
