package com.example.music.domain.art

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache

object TrackArtCache {
    private val memory = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    fun get(key: String): Bitmap? = memory.get(key)

    fun put(key: String, bitmap: Bitmap) {
        memory.put(key, bitmap)
    }

    fun remove(key: String) {
        memory.remove(key)
    }

    fun decode(bytes: ByteArray): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        configureSampling(options)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    fun decodeFile(path: String): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        configureSampling(options)
        return BitmapFactory.decodeFile(path, options)
    }

    private fun configureSampling(options: BitmapFactory.Options) {
        options.inSampleSize = 1
        while (options.outWidth / options.inSampleSize > 512 || options.outHeight / options.inSampleSize > 512) {
            options.inSampleSize *= 2
        }
        options.inJustDecodeBounds = false
    }

    fun key(relativePath: String, vararg extraPaths: String): String =
        relativePath.replace('\\', '/').lowercase()
}
