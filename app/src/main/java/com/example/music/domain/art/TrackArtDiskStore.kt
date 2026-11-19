package com.example.music.domain.art

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.security.MessageDigest

/** Persistent on-device cache of cover art downloaded from the music server. */
class TrackArtDiskStore(context: Context) {
    private val dir = File(context.filesDir, "track_art")

    init {
        dir.mkdirs()
    }

    fun loadBitmap(cacheKey: String): Bitmap? {
        val file = fileForKey(cacheKey)
        if (!file.isFile || file.length() <= 128L) return null
        val bitmap = TrackArtCache.decodeFile(file.absolutePath)
        if (bitmap == null || bitmap.width <= 1 || bitmap.height <= 1) {
            bitmap?.recycle()
            file.delete()
            return null
        }
        return bitmap
    }

    fun save(cacheKey: String, bytes: ByteArray): File? {
        val decoded = TrackArtCache.decode(bytes) ?: return null
        return try {
            saveBitmap(cacheKey, decoded)
        } finally {
            decoded.recycle()
        }
    }

    fun saveBitmap(cacheKey: String, bitmap: Bitmap): File? {
        if (bitmap.width <= 1 || bitmap.height <= 1) return null
        val file = fileForKey(cacheKey)
        file.outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
        }
        return file.takeIf { it.length() > 128L }
    }

    fun delete(cacheKey: String) {
        fileForKey(cacheKey).delete()
    }

    private fun fileForKey(cacheKey: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(cacheKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return File(dir, "$digest.jpg")
    }
}
