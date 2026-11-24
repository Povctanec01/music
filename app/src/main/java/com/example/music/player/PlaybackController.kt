package com.example.music.player

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
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
    private var queueRevision = 0L
    private var shuffleBuildJob: Job? = null
    private var orderedQueue: List<MediaItem> = emptyList()
    private var orderedRepeatMode = Player.REPEAT_MODE_OFF
    private var entrySerial = 0L

    private fun invalidateQueueWork(): Long {
        queueRevision++
        shuffleBuildJob?.cancel()
        shuffleBuildJob = null
        return queueRevision
    }

    private fun queueItem(track: Track): MediaItem {
        val item = track.toMediaItem()
        return item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(Bundle(item.mediaMetadata.extras).apply {
            putString(QUEUE_ENTRY, "${track.id}:${++entrySerial}")
        }).build()).build()
    }

    private fun entryKey(item: MediaItem): String = item.mediaMetadata.extras?.getString(QUEUE_ENTRY) ?: item.mediaId
    private fun priority(item: MediaItem, requested: Boolean): MediaItem = item.buildUpon().setMediaMetadata(
        item.mediaMetadata.buildUpon().setExtras(Bundle(item.mediaMetadata.extras).apply { putBoolean(PLAY_NEXT, requested) }).build(),
    ).build()
    private fun pendingNext(player: Player): List<MediaItem> =
        (player.currentMediaItemIndex + 1 until player.mediaItemCount).map { player.getMediaItemAt(it) }
            .filter { it.mediaMetadata.extras?.getBoolean(PLAY_NEXT) == true }
    private fun randomItem(track: Track): MediaItem = priority(orderedQueue.firstOrNull { it.mediaId == track.id } ?: queueItem(track), false)

    private fun shuffleItems(tracks: List<Track>, selected: MediaItem, player: Player): List<MediaItem> {
        val index = tracks.indexOfFirst { it.id == selected.mediaId }.coerceAtLeast(0)
        val pending = pendingNext(player)
        val requestedIds = pending.map { it.mediaId }.toSet()
        return tracks.take(index).map(::randomItem) + selected + pending +
            tracks.drop(index + 1).filterNot { it.id in requestedIds }.map(::randomItem)
    }

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            trackListeningSignals(player)
            publish(player)
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                val currentId = player.currentMediaItem?.mediaId
                if (currentId != null) rememberRecent(currentId)
                if (shuffleEnabled) scope.launch { maintainShuffleQueue(player) }
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
                val revision = queueRevision
                scope.launch {
                    val snapshot = preferences.playbackSnapshot()
                    if (revision == queueRevision) {
                        shuffleEnabled = snapshot.shuffle
                        orderedRepeatMode = snapshot.repeatMode
                    }
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
        val revision = invalidateQueueWork()
        restored = true
        orderedQueue = tracks.map(::queueItem)
        val selected = orderedQueue[safeIndex]
        shufflePool = tracks
        rememberRecent(tracks[safeIndex].id)
        if (shuffleEnabled) {
            shuffleBuildJob = scope.launch {
                val queue = buildShuffleQueue(tracks[safeIndex])
                if (!shuffleEnabled || revision != queueRevision) return@launch
                player.shuffleModeEnabled = false
                player.repeatMode = Player.REPEAT_MODE_OFF
                val items = shuffleItems(queue, selected, player)
                val startIndex = items.indexOfFirst { entryKey(it) == entryKey(selected) }.coerceAtLeast(0)
                player.setMediaItems(items, startIndex, C.TIME_UNSET)
                player.prepare()
                player.play()
            }
        } else {
            player.setMediaItems(orderedQueue, safeIndex, C.TIME_UNSET)
            player.prepare()
            player.play()
        }
    }

    /** Expand / refresh the pool used by weighted shuffle (typically full library). */
    fun setShufflePool(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        shufflePool = tracks
    }

    fun playNext(track: Track) {
        val player = controller ?: return
        if (player.mediaItemCount == 0) { play(listOf(track), 0); return }
        if (shuffleEnabled) {
            shufflePool = (listOf(track) + shufflePool.filter { it.id != track.id })
        }
        val current = player.currentMediaItem ?: return
        if (orderedQueue.isEmpty()) orderedQueue = List(player.mediaItemCount) { player.getMediaItemAt(it) }
        var originalIndex = orderedQueue.indexOfFirst { entryKey(it) == entryKey(current) }
        if (originalIndex < 0) originalIndex = orderedQueue.indexOfFirst { it.mediaId == current.mediaId }
        if (originalIndex < 0) { orderedQueue = listOf(current) + orderedQueue; originalIndex = 0 }
        val requested = queueItem(track)
        orderedQueue = insertNext(orderedQueue, originalIndex, requested) { a, b -> a.mediaId == b.mediaId }
        for (index in player.mediaItemCount - 1 downTo player.currentMediaItemIndex + 1) {
            if (player.getMediaItemAt(index).mediaId == track.id) player.removeMediaItem(index)
        }
        player.addMediaItem(player.currentMediaItemIndex + 1, priority(requested, true))
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
        val revision = invalidateQueueWork()
        if (!shuffleEnabled) orderedRepeatMode = player.repeatMode
        shuffleEnabled = !shuffleEnabled
        // Weighted radio owns ordering; keep ExoPlayer shuffle off to avoid edge stops.
        player.shuffleModeEnabled = false
        val enabled = shuffleEnabled
        scope.launch { preferences.setShuffle(enabled) }
        if (shuffleEnabled) {
            if (orderedQueue.isEmpty()) orderedQueue = List(player.mediaItemCount) { player.getMediaItemAt(it) }
            shuffleBuildJob = scope.launch {
                while (shuffleEnabled && revision == queueRevision) {
                    val selected = player.currentMediaItem ?: return@launch
                    val current = shufflePool.firstOrNull { it.id == selected.mediaId } ?: return@launch
                    val queue = buildShuffleQueue(current)
                    if (!shuffleEnabled || revision != queueRevision) return@launch
                    if (player.currentMediaItem?.let(::entryKey) != entryKey(selected)) continue
                    val items = shuffleItems(queue, selected, player)
                    val startIndex = items.indexOfFirst { entryKey(it) == entryKey(selected) }.coerceAtLeast(0)
                    val wasPlaying = player.playWhenReady
                    player.repeatMode = Player.REPEAT_MODE_OFF
                    player.setMediaItems(items, startIndex, player.currentPosition.coerceAtLeast(0L))
                    player.prepare()
                    if (wasPlaying) player.play() else player.pause()
                    return@launch
                }
            }
        } else if (player.currentMediaItem != null) {
            val selected = player.currentMediaItem!!
            var index = orderedQueue.indexOfFirst { entryKey(it) == entryKey(selected) }
            if (index < 0) index = orderedQueue.indexOfFirst { it.mediaId == selected.mediaId }
            if (index < 0) {
                orderedQueue = shufflePool.map(::queueItem)
                index = orderedQueue.indexOfFirst { it.mediaId == selected.mediaId }
            }
            if (index < 0) { orderedQueue = listOf(selected) + orderedQueue; index = 0 }
            val requested = pendingNext(player).map(::entryKey).toSet()
            val items = orderedQueue.map { priority(it, entryKey(it) in requested) }.toMutableList()
            items[index] = selected
            val wasPlaying = player.playWhenReady
            player.repeatMode = orderedRepeatMode
            player.setMediaItems(items, index, player.currentPosition.coerceAtLeast(0L))
            player.prepare()
            if (wasPlaying) player.play() else player.pause()
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
            invalidateQueueWork()
            player.seekToDefaultPosition(index)
            player.play()
        }
    }

    fun removeFromQueue(index: Int) {
        val player = controller ?: return
        if (index in 0 until player.mediaItemCount) {
            invalidateQueueWork()
            val key = entryKey(player.getMediaItemAt(index))
            orderedQueue = orderedQueue.filterNot { entryKey(it) == key }
            player.removeMediaItem(index)
        }
    }

    fun removeFromQueue(trackId: String) {
        invalidateQueueWork()
        orderedQueue = orderedQueue.filterNot { it.mediaId == trackId }
        shufflePool = shufflePool.filter { it.id != trackId }
        val player = controller ?: return
        var index = 0
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
        val initialRevision = queueRevision
        val snapshot = preferences.playbackSnapshot()
        if (snapshot.queueIds.isEmpty()) return
        val byId = tracks.associateBy { it.id }
        val restoredTracks = snapshot.queueIds.mapNotNull { byId[it] }
        if (restoredTracks.isEmpty()) return
        val player = waitForController() ?: return
        if (initialRevision != queueRevision) return
        if (player.mediaItemCount > 0) {
            if (orderedQueue.isEmpty()) orderedQueue = if (snapshot.shuffle) tracks.map(::queueItem)
                else List(player.mediaItemCount) { player.getMediaItemAt(it) }
            restored = true
            return
        }
        shufflePool = if (tracks.size >= restoredTracks.size) tracks else restoredTracks
        val revision = invalidateQueueWork()
        orderedQueue = (if (snapshot.shuffle) tracks else restoredTracks).map(::queueItem)
        orderedRepeatMode = snapshot.repeatMode
        shuffleEnabled = snapshot.shuffle
        player.shuffleModeEnabled = false
        val savedIndex = restoredQueueIndex(snapshot.queueIds, snapshot.index, snapshot.mediaId, byId.keys)
        val index = savedIndex.coerceAtLeast(0)
        val restoredPosition = if (savedIndex >= 0) snapshot.positionMs else 0L
        if (shuffleEnabled) {
            val current = restoredTracks[index]
            rememberRecent(current.id)
            val queue = buildShuffleQueue(current)
            if (revision != queueRevision || !shuffleEnabled) return
            val selected = orderedQueue.firstOrNull { it.mediaId == current.id } ?: queueItem(current)
            val items = shuffleItems(queue, selected, player)
            val startIndex = items.indexOfFirst { entryKey(it) == entryKey(selected) }.coerceAtLeast(0)
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.setMediaItems(items, startIndex, restoredPosition)
        } else {
            player.repeatMode = snapshot.repeatMode
            player.setMediaItems(orderedQueue, index, restoredPosition)
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
        if (!shuffleEnabled || advancingWeighted || !player.playWhenReady) return
        if (player.playbackState != Player.STATE_ENDED) return
        if (player.hasNextMediaItem()) return
        scope.launch { advanceWeighted(player) }
    }

    private suspend fun advanceWeighted(player: Player) {
        if (advancingWeighted || !shuffleEnabled) return
        val revision = queueRevision
        val source = player.currentMediaItem?.let(::entryKey)
        val pool = shufflePool
        if (pool.isEmpty()) return
        advancingWeighted = true
        try {
            val behavior = preferences.listeningBehaviorOnce()
            val favorites = preferences.favoritesOnce()
            if (!shuffleEnabled || revision != queueRevision || player.currentMediaItem?.let(::entryKey) != source) return
            if (player.hasNextMediaItem()) { player.seekToNextMediaItem(); return }
            val exclude = recentShuffleIds.toList().takeLast(8)
            val next = ShufflePicker.pickNext(
                pool = pool,
                behavior = behavior,
                favorites = favorites,
                excludeIds = exclude,
                random = Random.Default,
            ) ?: return
            playWeightedTrack(next, expectedRevision = revision, expectedSource = source, honorNext = true)
        } finally {
            advancingWeighted = false
        }
    }

    private suspend fun playWeightedTrack(
        track: Track, recordRecent: Boolean = true,
        expectedRevision: Long = queueRevision,
        expectedSource: String? = controller?.currentMediaItem?.let(::entryKey),
        honorNext: Boolean = false,
    ) {
        val player = controller ?: return
        val queue = buildShuffleQueue(track)
        if (!shuffleEnabled || expectedRevision != queueRevision || player.currentMediaItem?.let(::entryKey) != expectedSource) return
        if (honorNext && player.hasNextMediaItem()) { player.seekToNextMediaItem(); return }
        if (recordRecent) rememberRecent(track.id)
        val selected = randomItem(track)
        val items = shuffleItems(queue, selected, player)
        val startIndex = items.indexOfFirst { entryKey(it) == entryKey(selected) }.coerceAtLeast(0)
        val wasPlaying = player.playWhenReady
        player.shuffleModeEnabled = false
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.setMediaItems(items, startIndex, C.TIME_UNSET)
        player.prepare()
        if (wasPlaying) player.play() else player.pause()
    }

    private suspend fun buildShuffleQueue(startTrack: Track, upcomingCount: Int = 4): List<Track> {
        val pool = shufflePool
        if (pool.isEmpty()) return listOf(startTrack)

        val queue = mutableListOf<Track>()
        val previousId = if (recentShuffleIds.lastOrNull() == startTrack.id)
            recentShuffleIds.elementAtOrNull(recentShuffleIds.size - 2) else recentShuffleIds.lastOrNull()
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
        val revision = queueRevision
        val source = player.currentMediaItem?.let(::entryKey)
        if (player.mediaItemCount > 8 && player.currentMediaItemIndex > 2) {
            player.removeMediaItem(0)
        }
        var trailing = player.mediaItemCount - (player.currentMediaItemIndex + 1)
        if (trailing >= 3) return

        val behavior = preferences.listeningBehaviorOnce()
        val favorites = preferences.favoritesOnce()
        if (!shuffleEnabled || revision != queueRevision || player.currentMediaItem?.let(::entryKey) != source) return
        trailing = player.mediaItemCount - (player.currentMediaItemIndex + 1)
        if (trailing >= 3) return
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
            player.addMediaItem(randomItem(next))
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

    private suspend fun fadeOutAndPause(player: Player) {
        val original = player.volume
        try {
            var volume = original
            while (volume > 0.05f) {
                volume -= 0.07f
                player.volume = volume.coerceAtLeast(0f)
                delay(120)
            }
            player.pause()
        } finally {
            // Cancelling a fading timer must also restore the user's volume.
            player.volume = original
        }
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
                .setExtras(Bundle().apply {
                    putString(PLAYBACK_ART_PATH, relativePath)
                    putLong(PLAYBACK_ART_VERSION, addedAt)
                })
                .setIsPlayable(true)
                .build(),
        )
        .build()

internal const val PLAYBACK_ART_PATH = "music.art.path"
internal const val QUEUE_ENTRY = "music.queue.entry"
private const val PLAY_NEXT = "music.queue.next"
internal const val PLAYBACK_ART_VERSION = "music.art.version"

fun loadEmbeddedArt(context: Context, uri: Uri): Bitmap? =
    TrackMetadataReader.readEmbeddedArt(context, uri)

suspend fun loadEmbeddedArtAsync(context: Context, uri: Uri): Bitmap? =
    withContext(Dispatchers.IO) { loadEmbeddedArt(context, uri) }
