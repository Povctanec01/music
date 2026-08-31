package com.example.music.domain.art

import android.net.Uri
import com.example.music.domain.model.Track
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object TrackArtSources {
    /** Matches `[videoId]` and `[-videoId]` without grabbing spaced tags like `[original anime MV]`. */
    val youtubeIdBracketRegex = Regex("""\[(?:-)?([A-Za-z0-9_-]{11})\]""")

    /** Fallback for shorter or unusual bracket ids at the end of a filename. */
    private val youtubeIdLooseRegex = Regex("""\[(-?[A-Za-z0-9_-]{6,})\]""")

    fun youtubeVideoId(vararg paths: String): String? {
        for (path in paths) {
            if (path.isBlank()) continue
            youtubeIdBracketRegex.findAll(path).lastOrNull()?.groupValues?.get(1)?.let { return it }
            youtubeIdLooseRegex.findAll(path).lastOrNull()?.groupValues?.get(1)
                ?.trimStart('-')
                ?.takeIf { it.length in 6..11 }
                ?.let { return it }
        }
        return null
    }

    fun youtubeArtUrls(vararg paths: String): List<String> {
        val videoId = youtubeVideoId(*paths) ?: return emptyList()
        return listOf(
            "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
            "https://i.ytimg.com/vi/$videoId/mqdefault.jpg",
            "https://img.youtube.com/vi/$videoId/mqdefault.jpg",
            "https://i.ytimg.com/vi/$videoId/default.jpg",
        )
    }

    fun encodeRelativePath(relativePath: String): String =
        relativePath.split('/')
            .joinToString("/") { part ->
                URLEncoder.encode(part, StandardCharsets.UTF_8.name()).replace("+", "%20")
            }

    fun serverArtUrl(baseUrl: String, relativePath: String, version: Long): String {
        val encoded = encodeRelativePath(relativePath)
        return "${baseUrl.trimEnd('/')}/api/art/$encoded?v=$version"
    }

    fun serverFileUrl(baseUrl: String, relativePath: String): String {
        val encoded = encodeRelativePath(relativePath)
        return "${baseUrl.trimEnd('/')}/files/$encoded"
    }

    /** Server-only URLs: stored sidecar `.jpg` first, then `/api/art/`. No YouTube on phone. */
    fun serverArtUrlsForPath(
        relativePath: String,
        version: Long,
        serverBaseUrl: String?,
        serverIndex: ServerArtIndex = ServerArtIndex.EMPTY,
        extraPaths: Array<String> = emptyArray(),
    ): List<String> = buildList {
        if (serverBaseUrl.isNullOrBlank()) return@buildList
        val seen = linkedSetOf<String>()
        fun addUnique(url: String) {
            if (seen.add(url)) add(url)
        }

        val dot = relativePath.lastIndexOf('.')
        if (dot > 0) {
            val sidecarPath = relativePath.substring(0, dot) + ".jpg"
            addUnique(serverFileUrl(serverBaseUrl, sidecarPath))
            val sidecarBasename = sidecarPath.substringAfterLast('/')
            if (sidecarBasename != sidecarPath) {
                addUnique(serverFileUrl(serverBaseUrl, sidecarBasename))
            }
        }

        val resolved = serverIndex.resolve(relativePath, *extraPaths)
        if (resolved != null) {
            addUnique(serverArtUrl(serverBaseUrl, resolved.path, resolved.mtime))
        }
        val basename = relativePath.substringAfterLast('/')
        if (basename.isNotBlank()) {
            addUnique(serverArtUrl(serverBaseUrl, basename, version))
        }
        addUnique(serverArtUrl(serverBaseUrl, relativePath, version))
    }

    fun networkUrls(track: Track, serverBaseUrl: String?, serverIndex: ServerArtIndex = ServerArtIndex.EMPTY): List<String> =
        networkUrlsForPath(
            relativePath = track.relativePath,
            version = track.addedAt,
            serverBaseUrl = serverBaseUrl,
            serverIndex = serverIndex,
            extraPaths = arrayOf(track.displayName, track.title),
        )

    fun networkUrlsForPath(
        relativePath: String,
        version: Long,
        serverBaseUrl: String?,
        serverIndex: ServerArtIndex = ServerArtIndex.EMPTY,
        extraPaths: Array<String> = emptyArray(),
    ): List<String> = buildList {
        val seen = linkedSetOf<String>()
        fun addUnique(url: String) {
            if (seen.add(url)) add(url)
        }

        // Same order as web TrackArt: YouTube first, then server art.
        youtubeArtUrls(*extraPaths, relativePath).forEach(::addUnique)

        if (!serverBaseUrl.isNullOrBlank()) {
            val resolved = serverIndex.resolve(relativePath, *extraPaths)
            if (resolved != null) {
                addUnique(serverArtUrl(serverBaseUrl, resolved.path, resolved.mtime))
            }
            val basename = relativePath.substringAfterLast('/')
            if (basename.isNotBlank()) {
                addUnique(serverArtUrl(serverBaseUrl, basename, version))
            }
            addUnique(serverArtUrl(serverBaseUrl, relativePath, version))
        }
    }
}
