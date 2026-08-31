package com.example.music.domain.art

import com.example.music.domain.model.RemoteFile

/** Maps local filenames / YouTube ids to server library paths for `/api/art/...`. */
class ServerArtIndex private constructor(
    private val byExactPath: Map<String, Entry>,
    private val byBasename: Map<String, Entry>,
    private val byYoutubeId: Map<String, Entry>,
) {
    data class Entry(val path: String, val mtime: Long)

    fun resolve(relativePath: String, vararg extraPaths: String): Entry? {
        for (candidate in sequenceOf(relativePath, *extraPaths)) {
            if (candidate.isBlank()) continue
            byExactPath[candidate]?.let { return it }
            byExactPath[candidate.lowercase()]?.let { return it }
            val basename = candidate.substringAfterLast('/')
            if (basename.isNotBlank()) {
                byBasename[basename.lowercase()]?.let { return it }
            }
            TrackArtSources.youtubeVideoId(candidate)?.lowercase()?.let { id ->
                byYoutubeId[id]?.let { return it }
            }
        }
        return null
    }

    companion object {
        val EMPTY = ServerArtIndex(emptyMap(), emptyMap(), emptyMap())

        fun fromLibrary(files: List<RemoteFile>): ServerArtIndex {
            val byExact = mutableMapOf<String, Entry>()
            val byBase = mutableMapOf<String, Entry>()
            val byYt = mutableMapOf<String, Entry>()
            for (file in files) {
                val entry = Entry(file.path, file.mtime)
                byExact[file.path] = entry
                byExact[file.path.lowercase()] = entry
                val basename = file.path.substringAfterLast('/')
                if (basename.isNotBlank()) {
                    byBase[basename.lowercase()] = entry
                }
                TrackArtSources.youtubeVideoId(file.path)?.lowercase()?.let { id ->
                    byYt[id] = entry
                }
            }
            return ServerArtIndex(byExact, byBase, byYt)
        }
    }
}
