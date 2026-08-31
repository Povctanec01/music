package com.example.music.domain.art

/** Sidecar cover image stored next to the audio file in the user's music folder. */
object TrackSidecarArt {
    private val imageExtensions = listOf("jpg", "jpeg", "png", "webp")

    fun stemFromAudioPath(audioRelativePath: String): String =
        audioRelativePath.substringAfterLast('/').substringBeforeLast('.')

    fun sidecarFileNames(stem: String): List<String> =
        imageExtensions.map { ext -> "$stem.$ext" }

    fun primarySidecarRelativePath(audioRelativePath: String): String {
        val stem = stemFromAudioPath(audioRelativePath)
        val dir = audioRelativePath.substringBeforeLast('/', missingDelimiterValue = "")
        val fileName = "$stem.jpg"
        return if (dir.isEmpty()) fileName else "$dir/$fileName"
    }
}
