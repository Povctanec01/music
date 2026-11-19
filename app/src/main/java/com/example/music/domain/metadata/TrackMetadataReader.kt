package com.example.music.domain.metadata

import android.content.Context
import android.graphics.Bitmap
import com.example.music.domain.art.TrackArtCache
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.example.music.domain.art.TrackArtSources
import com.example.music.domain.model.Track

data class ReadTrackMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val year: Int?,
    val trackNumber: Int?,
)

object TrackMetadataReader {
    private val artistInParensRegex = Regex("""\(([^)/]+)""")
    private val albumAfterSlashRegex = Regex("""/([^)]+)""")

    fun needsMetadataRefresh(cached: Track, displayName: String): Boolean {
        if (cached.durationMs <= 0L) return true
        val fallbackTitle = displayName.substringBeforeLast('.')
        val looksLikeFilenameOnly = cached.artist.isBlank() &&
            cached.album.isBlank() &&
            cached.title.equals(fallbackTitle, ignoreCase = true)
        return looksLikeFilenameOnly && displayName.substringAfterLast('.', "").lowercase() in setOf("m4a", "aac", "mp4")
    }

    fun read(context: Context, uri: Uri, displayName: String): ReadTrackMetadata {
        val fallbackTitle = displayName.substringBeforeLast('.')
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSourceSafe(context, uri)
            var title = retriever.firstNonBlank(
                MediaMetadataRetriever.METADATA_KEY_TITLE,
            ) ?: fallbackTitle
            var artist = retriever.firstNonBlank(
                MediaMetadataRetriever.METADATA_KEY_ARTIST,
                MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST,
                MediaMetadataRetriever.METADATA_KEY_AUTHOR,
                MediaMetadataRetriever.METADATA_KEY_COMPOSER,
                MediaMetadataRetriever.METADATA_KEY_WRITER,
            ).orEmpty()
            var album = retriever.firstNonBlank(
                MediaMetadataRetriever.METADATA_KEY_ALBUM,
            ).orEmpty()
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.toIntOrNull()
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                    ?.take(4)
                    ?.toIntOrNull()
            val trackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore('/')
                ?.trim()
                ?.toIntOrNull()

            val enriched = enrichFromFilename(displayName, title, artist, album)
            title = enriched.title
            artist = enriched.artist
            album = enriched.album

            ReadTrackMetadata(
                title = title,
                artist = artist,
                album = album,
                durationMs = duration,
                year = year,
                trackNumber = trackNumber,
            )
        } catch (_: Exception) {
            val enriched = enrichFromFilename(displayName, fallbackTitle, "", "")
            ReadTrackMetadata(
                title = enriched.title,
                artist = enriched.artist,
                album = enriched.album,
                durationMs = 0L,
                year = null,
                trackNumber = null,
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun readEmbeddedArt(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSourceSafe(context, uri)
            retriever.embeddedPicture?.let { bytes ->
                TrackArtCache.decode(bytes)
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun enrichFromFilename(
        displayName: String,
        title: String,
        artist: String,
        album: String,
    ): ReadTrackMetadata {
        val base = displayName.substringBeforeLast('.')
        var nextTitle = title
        var nextArtist = artist
        var nextAlbum = album

        if (nextArtist.isBlank()) {
            nextArtist = artistInParensRegex.findAll(base)
                .map { it.groupValues[1].trim() }
                .lastOrNull { it.isNotBlank() }
                .orEmpty()
        }

        val fallbackTitle = base.substringBeforeLast('.')
        if (nextTitle.isBlank() || nextTitle.equals(base, ignoreCase = true) || nextTitle.equals(fallbackTitle, ignoreCase = true)) {
            nextTitle = base
                .substringAfter(']', missingDelimiterValue = base)
                .substringBefore('(')
                .trim()
                .ifBlank { base.substringBefore('[').trim() }
                .ifBlank { base.substringBeforeLast('(').trim() }
                .ifBlank { base.substringBeforeLast('.') }
        }

        if (nextAlbum.isBlank()) {
            nextAlbum = albumAfterSlashRegex.find(base)?.groupValues?.get(1)?.trim().orEmpty()
        }

        // Strip trailing YouTube id bracket from title if still present.
        nextTitle = nextTitle.replace(TrackArtSources.youtubeIdBracketRegex, "").trim()

        return ReadTrackMetadata(
            title = nextTitle.ifBlank { displayName.substringBeforeLast('.') },
            artist = nextArtist,
            album = nextAlbum,
            durationMs = 0L,
            year = null,
            trackNumber = null,
        )
    }

    private fun MediaMetadataRetriever.setDataSourceSafe(context: Context, uri: Uri) {
        try {
            setDataSource(context, uri)
            return
        } catch (_: Exception) {
            // SAF/content URIs for m4a often fail without a file descriptor.
        }

        when (uri.scheme) {
            "file" -> {
                val path = uri.path ?: error("Пустой путь к файлу")
                setDataSource(path)
            }
            "content" -> {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                } ?: context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    setDataSource(pfd.fileDescriptor)
                } ?: error("Не удалось открыть файл")
            }
            else -> setDataSource(context, uri)
        }
    }

    private fun MediaMetadataRetriever.firstNonBlank(vararg keys: Int): String? {
        for (key in keys) {
            val value = extractMetadata(key)?.trim()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }
}
