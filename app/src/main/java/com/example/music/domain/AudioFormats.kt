package com.example.music.domain

object AudioFormats {
    val EXTENSIONS = setOf("mp3", "flac", "ogg", "opus", "m4a", "aac", "wav")

    fun isAudio(mime: String?, displayName: String): Boolean {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        if (ext in EXTENSIONS) return true
        return mime?.startsWith("audio/") == true &&
            mime != "audio/x-mpegurl" &&
            mime != "audio/mpegurl"
    }

    fun mimeForFileName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }
}
