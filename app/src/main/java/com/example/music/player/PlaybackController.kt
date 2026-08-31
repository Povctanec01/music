package com.example.music.player

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
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
import com.example.music.domain.metadata.TrackMetadataReader
import com.example.music.domain.recommendation.ShufflePicker
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
import kotlin.collections.ArrayDeque
import kotlin.random.Random

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
    /** Full pool used for weighted shuffle / radio mode. */
    private var shufflePool: List<Track> = emptyList()
    private var shuffleEnabled = false
    private val recentShuffleIds = ArrayDeque<String>()
    private var advancingWeighted = false

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            trackListeningSignals(player)
            publish(player)
            persist(player)
            if (shuffleEnabled && events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                val currentId = player.currentMediaItem?.mediaId
                if (currentId != null) rememberRecent(currentId)
                scope.launch { maintainShuffleQueue(player) }
            }
            maybeContinueWeightedShuffle(player)
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
                scope.launch {
                    val snapshot = preferences.playbackSnapshot()
                    shuffleEnabled = snapshot.shuffle
                    mediaController.shuffleModeEnabled = false
                    publish(mediaController)
                }
                startPositionUpdates()
            } catch (_: Exception) {
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun play(tracks: List<Track>, index: Int) {
        val player = controller ?: return
        if (tracks.isEmpty()) return
        val safeIndex = index.coerceIn(0, tracks.lastIndex)
        shufflePool = tracks
        rememberRecent(tracks[safeIndex].id)
        if (shuffleEnabled) {
            scope.launch {
                val queue = buildShuffleQueue(tracks[safeIndex])
                player.shuffleModeEnabled = false
                player.repeatMode = Player.REPEAT_MODE_OFF
                val startIndex = queue.indexOfFirst { it.id == tracks[safeIndex].id }.coerceAtLeast(0)
                player.setMediaItems(queue.map { it.toMediaItem() }, startIndex, C.TIME_UNSET)
                player.prepare()
                player.play()
                preferences.savePlayback(
                    queueIds = queue.map { it.id },
                    index = startIndex,
                    positionMs = 0L,
                    mediaId = tracks[safeIndex].id,
                )
            }
        } else {
            player.setMediaItems(tracks.map { it.toMediaItem() }, safeIndex, C.TIME_UNSET)
            player.prepare()
            player.play()
            scope.launch {
                preferences.savePlayback(tracks.map { it.id }, safeIndex, 0L, tracks[safeIndex].id)
            }
        }
    }

    /** Expand / refresh the pool used by weighted shuffle (typically full library). */
    fun setShufflePool(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        shufflePool = tracks
    }

    fun playNext(track: Track) {
        val player = controller ?: return
        if (shuffleEnabled) {
            shufflePool = (listOf(track) + shufflePool.filter { it.id != track.id })
            scope.launch { playWeightedTrack(track) }
            return
        }
        val insertAt = (player.currentMediaItemIndex + 1).coerceAtMost(player.mediaItemCount)
        player.addMediaItem(insertAt, track.toMediaItem())
    }

    fun togglePlayPause() {
        val player = controller ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    fun skipNext() {
        val player = controller ?: return
        if (shuffleEnabled) {
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
            } else {
                scope.launch { advanceWeighted(player) }
            }
            return
        }
        if (player.hasNextMediaItem()) {
            player.seekToNext()
        }
    }

    fun skipPrevious() {
        val player = controller ?: return
        if (shuffleEnabled) {
            if (player.hasPreviousMediaItem()) {
                player.seekToPreviousMediaItem()
                return
            }
            val previousId = recentShuffleIds.elementAtOrNull(recentShuffleIds.size - 2)
            val previous = previousId?.let { id -> shufflePool.firstOrNull { it.id == id } }
            if (previous != null) {
                scope.launch { playWeightedTrack(previous, recordRecent = false) }
            }
            return
        }
        player.seekToPrevious()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun toggleShuffle() {
        val player = controller ?: return
        shuffleEnabled = !shuffleEnabled
        // Weighted radio owns ordering; keep ExoPlayer shuffle off to avoid edge stops.
        player.shuffleModeEnabled = false
        scope.launch { preferences.setShuffle(shuffleEnabled) }
        if (shuffleEnabled) {
            val currentId = player.currentMediaItem?.mediaId
            val current = currentId?.let { id -> shufflePool.firstOrNull { it.id == id } }
            if (current != null) {
                scope.launch {
                    val queue = buildShuffleQueue(current)
                    val startIndex = queue.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
                    player.repeatMode = Player.REPEAT_MODE_OFF
                    player.setMediaItems(
                        queue.map { it.toMediaItem() },
                        startIndex,
                        player.currentPosition.coerceAtLeast(0L),
                    )
                    player.prepare()
                    if (_state.value.isPlaying) player.play()
                }
            }
        } else if (shufflePool.isNotEmpty()) {
            val currentId = player.currentMediaItem?.mediaId
            val index = shufflePool.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
            player.setMediaItems(
                shufflePool.map { it.toMediaItem() },
                index,
                player.currentPosition.coerceAtLeast(0L),
            )
            player.prepare()
            if (_state.value.isPlaying) player.play()
        }
        publish(player)
    }

    fun cycleRepeat() {
        val player = controller ?: return
        if (shuffleEnabled) return
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

    fun removeFromQueue(trackId: String) {
        val player = controller ?: return
        var index = player.currentMediaItemIndex
        while (index < player.mediaItemCount) {
            val itemIndex = (index until player.mediaItemCount).firstOrNull { idx ->
                player.getMediaItemAt(idx).mediaId == trackId
            } ?: break
            val removingCurrent = itemIndex == player.currentMediaItemIndex
            player.removeMediaItem(itemIndex)
            if (player.mediaItemCount == 0) {
                player.stop()
                player.clearMediaItems()
                publish(player)
                return
            }
            if (removingCurrent) {
                val nextIndex = itemIndex.coerceAtMost(player.mediaItemCount - 1)
                player.seekToDefaultPosition(nextIndex)
            }
            index = itemIndex
        }
        publish(player)
    }

    /** Stop and detach a track from the player before its file is deleted from storage. */
    fun releaseTrackIfPlaying(trackId: String) {
        val player = controller ?: return
        if (player.currentMediaItem?.mediaId != trackId) return
        player.pause()
        removeFromQueue(trackId)
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
        shufflePool = if (tracks.size >= restoredTracks.size) tracks else restoredTracks
        shuffleEnabled = snapshot.shuffle
        player.shuffleModeEnabled = false
        val index = snapshot.index.coerceIn(0, restoredTracks.lastIndex)
        if (shuffleEnabled) {
            val current = restoredTracks[index]
            rememberRecent(current.id)
            val queue = buildShuffleQueue(current)
            val startIndex = queue.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.setMediaItems(queue.map { it.toMediaItem() }, startIndex, snapshot.positionMs)
        } else {
            player.repeatMode = snapshot.repeatMode
            player.setMediaItems(restoredTracks.map { it.toMediaItem() }, index, snapshot.positionMs)
        }
        player.prepare()
        player.pause()
        restored = true
        publish(player)
    }

    fun currentTrack(tracks: List<Track>): Track? {
        val id = _state.value.currentId ?: return null
        return tracks.firstOrNull { it.id == id }
    }

    private fun maybeContinueWeightedShuffle(player: Player) {
        if (!shuffleEnabled || advancingWeighted) return
        if (player.playbackState != Player.STATE_ENDED) return
        if (player.hasNextMediaItem()) return
        scope.launch { advanceWeighted(player) }
    }

    private suspend fun advanceWeighted(player: Player) {
        if (advancingWeighted) return
        val pool = shufflePool
        if (pool.isEmpty()) return
        advancingWeighted = true
        try {
            val behavior = preferences.listeningBehaviorOnce()
            val favorites = preferences.favoritesOnce()
            val exclude = recentShuffleIds.toList().takeLast(8)
            val next = ShufflePicker.pickNext(
                pool = pool,
                behavior = behavior,
                favorites = favorites,
                excludeIds = exclude,
                random = Random.Default,
            ) ?: return
            playWeightedTrack(next)
        } finally {
            advancingWeighted = false
        }
    }

    private suspend fun playWeightedTrack(track: Track, recordRecent: Boolean = true) {
        val player = controller ?: return
        if (recordRecent) rememberRecent(track.id)
        val queue = buildShuffleQueue(track)
        val startIndex = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        player.shuffleModeEnabled = false
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.setMediaItems(queue.map { it.toMediaItem() }, startIndex, C.TIME_UNSET)
        player.prepare()
        player.play()
        preferences.savePlayback(
            queueIds = queue.map { it.id },
            index = startIndex,
            positionMs = 0L,
            mediaId = track.id,
        )
    }

    private suspend fun buildShuffleQueue(startTrack: Track, upcomingCount: Int = 4): List<Track> {
        val pool = shufflePool
        if (pool.isEmpty()) return listOf(startTrack)

        val queue = mutableListOf<Track>()
        val previousId = recentShuffleIds.elementAtOrNull(recentShuffleIds.size - 2)
        val previous = previousId?.let { id -> pool.firstOrNull { it.id == id } }
        if (previous != null && previous.id != startTrack.id) {
            queue += previous
        }
        queue += startTrack

        val behavior = preferences.listeningBehaviorOnce()
        val favorites = preferences.favoritesOnce()
        val exclude = (recentShuffleIds.toList().takeLast(8) + queue.map { it.id }).toMutableSet()
        repeat(upcomingCount) {
            val next = ShufflePicker.pickNext(
                pool = pool,
                behavior = behavior,
                favorites = favorites,
                excludeIds = exclude.toList(),
                random = Random.Default,
            ) ?: return@repeat
            queue += next
            exclude += next.id
        }
        return queue
    }

    private suspend fun maintainShuffleQueue(player: Player) {
        if (!shuffleEnabled || shufflePool.isEmpty()) return
        if (player.mediaItemCount > 8 && player.currentMediaItemIndex > 2) {
            player.removeMediaItem(0)
        }
        val trailing = player.mediaItemCount - (player.currentMediaItemIndex + 1)
        if (trailing >= 3) return

        val behavior = preferences.listeningBehaviorOnce()
        val favorites = preferences.favoritesOnce()
        val exclude = (
            recentShuffleIds.toList().takeLast(8) +
                List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }
            ).toMutableSet()
        repeat(3 - trailing) {
            val next = ShufflePicker.pickNext(
                pool = shufflePool,
                behavior = behavior,
                favorites = favorites,
                excludeIds = exclude.toList(),
                random = Random.Default,
            ) ?: return
            player.addMediaItem(next.toMediaItem())
            exclude += next.id
        }
    }

    private fun rememberRecent(trackId: String) {
        if (recentShuffleIds.lastOrNull() == trackId) return
        recentShuffleIds.addLast(trackId)
        while (recentShuffleIds.size > 24) {
            recentShuffleIds.removeFirst()
        }
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
            shuffle = shuffleEnabled,
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

fun loadEmbeddedArt(context: Context, uri: Uri): Bitmap? =
    TrackMetadataReader.readEmbeddedArt(context, uri)

suspend fun loadEmbeddedArtAsync(context: Context, uri: Uri): Bitmap? =
    withContext(Dispatchers.IO) { loadEmbeddedArt(context, uri) }
