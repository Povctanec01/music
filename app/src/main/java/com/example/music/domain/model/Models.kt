package com.example.music.domain.model

import android.net.Uri
import kotlinx.serialization.Serializable

data class Track(
    val id: String,
    val uri: Uri,
    val displayName: String,
    val relativePath: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val size: Long,
    val year: Int?,
    val trackNumber: Int?,
    val addedAt: Long,
    val mimeType: String,
) {
    val folder: String
        get() = relativePath.substringBeforeLast('/', missingDelimiterValue = "").ifBlank { "/" }

    val artistOrUnknown: String
        get() = artist.ifBlank { "Неизвестный исполнитель" }

    val albumOrUnknown: String
        get() = album.ifBlank { "Неизвестный альбом" }
}

@Serializable
data class RemoteLibrary(
    val files: List<RemoteFile> = emptyList(),
)

@Serializable
data class RemoteFile(
    val path: String,
    val size: Long,
    val mtime: Long = 0L,
)

enum class DownloadStatus {
    QUEUED, RUNNING, PAUSED, DONE, ERROR
}

data class DownloadTask(
    val path: String,
    val size: Long,
    val bytesDownloaded: Long = 0L,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val error: String? = null,
) {
    val progress: Float
        get() = if (size <= 0L) 0f else (bytesDownloaded.toFloat() / size.toFloat()).coerceIn(0f, 1f)
}

enum class ConnectionChannel { LAN, TAILSCALE, NONE }

data class ServerEndpoint(
    val baseUrl: String,
    val channel: ConnectionChannel,
)

enum class DownloadNetworkMode { WIFI_ONLY, WIFI_OR_TAILSCALE, ANY }

enum class SortMode { TITLE, ARTIST, DATE_ADDED, DURATION }

enum class GroupMode { TRACKS, ARTISTS, ALBUMS, FOLDERS, FAVORITES }

sealed class LibraryRow {
    data class Header(val title: String) : LibraryRow()
    data class Song(val track: Track) : LibraryRow()
}
