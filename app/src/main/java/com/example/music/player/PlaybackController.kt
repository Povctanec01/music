package com.example.music.player

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.music.data.preferences.AppPreferences
import com.example.music.domain.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlayerUiState(
    val connected: Boolean = false,
    val isPlaying: Boolean = false,
    val currentId: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queueSize: Int = 0,
    val currentIndex: Int = 0,
    val queue: List<QueueItem> = emptyList(),
    val sleepRemainingMs: Long = 0L,
)

data class QueueItem(
    val id: String,
    val title: String,
    val artist: String,
)

class PlaybackController(
    private val context: Context,
    private val preferences: AppPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null
    private var positionJob: Job? = null
    private var sleepJob: Job? = null
    private var persistJob: Job? = null
    private var restored = false
    private var lastTrackedMediaId: String? = null
    private var lastTrackedPositionMs: Long = 0L
    private var pendingListenedMs: Long = 0L
    private var completedTrackIds = mutableSetOf<String>()

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            trackListeningSignals(player)
            publish(player)
            persist(player)
        }
    }

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, MusicPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            try {
                val mediaController = future.get()
                controller = mediaController
                mediaController.addListener(listener)
                publish(mediaController)
                startPositionUpdates()
            } catch (_: Exception) {
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun play(tracks: List<Track>, index: Int) {
        val player = controller ?: return
        if (tracks.isEmpty()) return
        val safeIndex = index.coerceIn(0, tracks.lastIndex)
        player.setMediaItems(tracks.map { it.toMediaItem() }, safeIndex, C.TIME_UNSET)
        player.prepare()
        player.play()
        scope.launch {
            preferences.savePlayback(tracks.map { it.id }, safeIndex, 0L, tracks[safeIndex].id)
        }
    }

    fun playNext(track: Track) {
        val player = controller ?: return
        val insertAt = (player.currentMediaItemIndex + 1).coerceAtMost(player.mediaItemCount)
        player.addMediaItem(insertAt, track.toMediaItem())
    }

    fun togglePlayPause() {
        val player = controller ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    fun skipNext() {
        controller?.seekToNext()
    }

    fun skipPrevious() {
        controller?.seekToPrevious()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun toggleShuffle() {
        val player = controller ?: return
        val enabled = !player.shuffleModeEnabled
        player.shuffleModeEnabled = enabled
        scope.launch { preferences.setShuffle(enabled) }
    }

    fun cycleRepeat() {
        val player = controller ?: return
        val next = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        player.repeatMode = next
        scope.launch { preferences.setRepeatMode(next) }
    }

    fun jumpToQueueIndex(index: Int) {
        val player = controller ?: return
        if (index in 0 until player.mediaItemCount) {
            player.seekToDefaultPosition(index)
            player.play()
        }
    }

    fun removeFromQueue(index: Int) {
        val player = controller ?: return
        if (index in 0 until player.mediaItemCount) {
            player.removeMediaItem(index)
        }
    }

    fun startSleepTimer(durationMs: Long, fade: Boolean) {
        sleepJob?.cancel()
        if (durationMs <= 0L) {
            _state.value = _state.value.copy(sleepRemainingMs = 0L)
            return
        }
        sleepJob = scope.launch {
            var remaining = durationMs
            while (remaining > 0 && isActive) {
                _state.value = _state.value.copy(sleepRemainingMs = remaining)
                delay(1000)
                remaining -= 1000
            }
            if (isActive) {
                val player = controller
                if (player != null) {
                    if (fade) fadeOutAndPause(player) else player.pause()
                }
                _state.value = _state.value.copy(sleepRemainingMs = 0L)
            }
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        _state.value = _state.value.copy(sleepRemainingMs = 0L)
    }

    suspend fun restoreIfNeeded(tracks: List<Track>) {
        if (restored || tracks.isEmpty()) return
        val snapshot = preferences.playbackSnapshot()
        if (snapshot.queueIds.isEmpty()) return
        val byId = tracks.associateBy { it.id }
        val restoredTracks = snapshot.queueIds.mapNotNull { byId[it] }
        if (restoredTracks.isEmpty()) return
        val player = waitForController() ?: return
        if (player.mediaItemCount > 0) {
            restored = true
            return
        }
        val index = snapshot.index.coerceIn(0, restoredTracks.lastIndex)
        player.shuffleModeEnabled = snapshot.shuffle
        player.repeatMode = snapshot.repeatMode
        player.setMediaItems(restoredTracks.map { it.toMediaItem() }, index, snapshot.positionMs)
        player.prepare()
        player.pause()
        restored = true
        publish(player)
    }

    fun currentTrack(tracks: List<Track>): Track? {
        val id = _state.value.currentId ?: return null
        return tracks.firstOrNull { it.id == id }
    }

    private suspend fun waitForController(): MediaController? {
        repeat(20) {
            controller?.let { return it }
            delay(100)
        }
        return controller
    }

    private fun startPositionUpdates() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (isActive) {
                controller?.let {
                    collectListenedTime(it)
                    publish(it, positionOnly = true)
                }
                delay(500)
            }
        }
    }

    private fun trackListeningSignals(player: Player) {
        val previous = _state.value
        val previousId = previous.currentId
        val currentId = player.currentMediaItem?.mediaId

        if (previousId != null && previousId != currentId) {
            flushListenedTime(previousId)
            finalizeTrack(previousId, previous.positionMs, previous.durationMs)
        }

        if (currentId == null) {
            lastTrackedMediaId = null
            lastTrackedPositionMs = 0L
            pendingListenedMs = 0L
            return
        }

        if (currentId != lastTrackedMediaId) {
            lastTrackedMediaId = currentId
            lastTrackedPositionMs = player.currentPosition.coerceAtLeast(0L)
            pendingListenedMs = 0L
            completedTrackIds.remove(currentId)
            scope.launch { preferences.recordTrackStart(currentId) }
        }

        if (!player.isPlaying) {
            flushListenedTime(currentId)
        }

        if (player.playbackState == Player.STATE_ENDED && currentId !in completedTrackIds) {
            scope.launch { preferences.recordCompletion(currentId) }
            completedTrackIds += currentId
        }
    }

    private fun collectListenedTime(player: Player) {
        val currentId = player.currentMediaItem?.mediaId
        val currentPosition = player.currentPosition.coerceAtLeast(0L)
        if (currentId == null) {
            pendingListenedMs = 0L
            lastTrackedPositionMs = 0L
            return
        }
        if (currentId != lastTrackedMediaId) {
            lastTrackedMediaId = currentId
            lastTrackedPositionMs = currentPosition
            pendingListenedMs = 0L
            return
        }
        if (player.isPlaying) {
            val delta = (currentPosition - lastTrackedPositionMs).coerceAtLeast(0L)
            if (delta in 1L..5_000L) {
                pendingListenedMs += delta
                if (pendingListenedMs >= 5_000L) {
                    flushListenedTime(currentId)
                }
            }
        }
        lastTrackedPositionMs = currentPosition
    }

    private fun finalizeTrack(trackId: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0L) return
        val progress = positionMs.toFloat() / durationMs.toFloat()
        when {
            progress >= 0.85f && trackId !in completedTrackIds -> {
                completedTrackIds += trackId
                scope.launch { preferences.recordCompletion(trackId) }
            }
            positionMs in 5_000L until 45_000L && progress < 0.35f -> {
                scope.launch { preferences.recordSkip(trackId) }
            }
        }
    }

    private fun flushListenedTime(trackId: String) {
        val listenedMs = pendingListenedMs
        if (listenedMs <= 0L) return
        pendingListenedMs = 0L
        scope.launch { preferences.addListenedTime(trackId, listenedMs) }
    }

    private fun publish(player: Player, positionOnly: Boolean = false) {
        val metadata = player.mediaMetadata
        val queue = buildList {
            repeat(player.mediaItemCount) { index ->
                val item = player.getMediaItemAt(index)
                add(
                    QueueItem(
                        id = item.mediaId,
                        title = item.mediaMetadata.title?.toString().orEmpty(),
                        artist = item.mediaMetadata.artist?.toString().orEmpty(),
                    ),
                )
            }
        }
        val current = player.currentMediaItem
        _state.value = _state.value.copy(
            connected = true,
            isPlaying = player.isPlaying,
            currentId = current?.mediaId,
            title = metadata.title?.toString().orEmpty().ifBlank { current?.mediaMetadata?.title?.toString().orEmpty() },
            artist = metadata.artist?.toString().orEmpty(),
            album = metadata.albumTitle?.toString().orEmpty(),
            durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            queueSize = player.mediaItemCount,
            currentIndex = player.currentMediaItemIndex.coerceAtLeast(0),
            queue = if (positionOnly) _state.value.queue else queue,
        )
    }

    private fun persist(player: Player) {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(400)
            val ids = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }
            preferences.savePlayback(
                queueIds = ids,
                index = player.currentMediaItemIndex.coerceAtLeast(0),
                positionMs = player.currentPosition.coerceAtLeast(0L),
                mediaId = player.currentMediaItem?.mediaId,
            )
        }
    }

    private suspend fun fadeOutAndPause(player: Player) {
        val original = player.volume
        var volume = original
        while (volume > 0.05f) {
            volume -= 0.07f
            player.volume = volume.coerceAtLeast(0f)
            delay(120)
        }
        player.pause()
        player.volume = original
    }
}

fun Track.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setUri(uri)
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artistOrUnknown)
                .setAlbumTitle(albumOrUnknown)
                .setIsPlayable(true)
                .build(),
        )
        .build()

fun loadEmbeddedArt(context: Context, uri: Uri): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        retriever.embeddedPicture?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    } catch (_: Exception) {
        null
    } finally {
        try {
            retriever.release()
        } catch (_: Exception) {
        }
    }
}

suspend fun loadEmbeddedArtAsync(context: Context, uri: Uri): Bitmap? =
    withContext(Dispatchers.IO) { loadEmbeddedArt(context, uri) }
