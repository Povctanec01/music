package com.example.music.domain.art

import android.graphics.Bitmap
import android.util.LruCache

object TrackArtCache {
    private val memory = object : LruCache<String, Bitmap>(128) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    fun get(key: String): Bitmap? = memory.get(key)

    fun put(key: String, bitmap: Bitmap) {
        memory.put(key, bitmap)
    }

    fun remove(key: String) {
        memory.remove(key)
    }

    fun key(relativePath: String, vararg extraPaths: String): String =
        relativePath.replace('\\', '/').lowercase()
}
