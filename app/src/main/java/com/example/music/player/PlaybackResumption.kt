package com.example.music.player

import androidx.media3.session.MediaSession
import com.example.music.data.preferences.AppPreferences

/** Restore directly from disk, without waiting for the Activity or a network scan. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal suspend fun AppPreferences.resumptionPlaylist(): MediaSession.MediaItemsWithStartPosition {
    val snapshot = playbackSnapshot()
    val folder = folderUriOnce() ?: throw IllegalStateException("Не выбрана музыкальная папка")
    val byId = cachedTracks(folder).associateBy { it.id }
    val tracks = snapshot.queueIds.mapNotNull { byId[it] }
    if (tracks.isEmpty()) throw IllegalStateException("Сохранённая очередь пуста")
    val currentId = snapshot.mediaId ?: snapshot.queueIds.getOrNull(snapshot.index)
    val index = tracks.indexOfFirst { it.id == currentId }
    return MediaSession.MediaItemsWithStartPosition(
        tracks.map { it.toMediaItem() }, index.coerceAtLeast(0),
        if (index >= 0) snapshot.positionMs.coerceAtLeast(0L) else 0L,
    )
}
