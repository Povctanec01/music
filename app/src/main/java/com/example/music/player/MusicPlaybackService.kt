package com.example.music.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.music.MainActivity
import com.example.music.MusicApplication
import com.example.music.R
import com.example.music.domain.art.TrackArtRepository
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.io.ByteArrayOutputStream

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MusicPlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var artworkJob: Job? = null
    private var artworkIdentity: String? = null
    private var saveJob: Job? = null
    private var hadQueue = false
    private lateinit var sessionActivity: PendingIntent
    private val container get() = (application as MusicApplication).container

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Воспроизведение музыки", NotificationManager.IMPORTANCE_LOW),
        )
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(NOTIFICATION_ID).setChannelId(CHANNEL).build())
        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL).build()
        player = exoPlayer
        sessionActivity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val sessionPlayer = object : ForwardingPlayer(exoPlayer) {
            override fun play() { prepareForPlay(); super.play() }
            override fun setPlayWhenReady(playWhenReady: Boolean) {
                if (playWhenReady) prepareForPlay()
                super.setPlayWhenReady(playWhenReady)
            }
        }
        session = MediaSession.Builder(this, sessionPlayer).setSessionActivity(sessionActivity)
            .setCallback(object : MediaSession.Callback {
                override fun onPlaybackResumption(session: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                    scope.launch {
                        try {
                            val playlist = withContext(Dispatchers.IO) { container.preferences.resumptionPlaylist() }
                            val snapshot = container.preferences.playbackSnapshot()
                            session.player.repeatMode = if (snapshot.shuffle) Player.REPEAT_MODE_OFF else snapshot.repeatMode
                            session.player.shuffleModeEnabled = false
                            future.set(playlist)
                        } catch (error: CancellationException) { future.cancel(false) }
                        catch (error: Exception) { future.setException(error) }
                    }
                    return future
                }
            }).build()
        exoPlayer.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                observeCurrentArtwork()
                if (player.mediaItemCount == 0 && !hadQueue) return
                hadQueue = player.mediaItemCount > 0
                val ids = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }
                val index = player.currentMediaItemIndex.coerceAtLeast(0)
                val position = player.currentPosition.coerceAtLeast(0L)
                val id = player.currentMediaItem?.mediaId
                saveJob?.cancel()
                saveJob = scope.launch { container.preferences.savePlayback(ids, index, position, id) }
            }
        })
    }

    private fun prepareForPlay() {
        val current = player ?: return
        if (current.mediaItemCount == 0) return
        if (current.playbackState == Player.STATE_IDLE) current.prepare()
        if (current.playbackState == Player.STATE_ENDED) current.seekToDefaultPosition()
        // Android 15+ requires foreground status BEFORE a background audio-focus request.
        // The regular Media3 notification replaces this initial notification using the same ID.
        if (Build.VERSION.SDK_INT >= 35) {
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_music)
                .setContentTitle(current.mediaMetadata.title ?: "Музыка")
                .setContentText(current.mediaMetadata.artist).setContentIntent(sessionActivity)
                .setOnlyAlertOnce(true).setOngoing(true).build()
            ContextCompat.startForegroundService(this, Intent(this, MusicPlaybackService::class.java))
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        }
    }

    private fun observeCurrentArtwork() {
        val current = player?.currentMediaItem
        val path = current?.mediaMetadata?.extras?.getString(PLAYBACK_ART_PATH)
        val identity = current?.let { "${it.mediaId}|${it.localConfiguration?.uri}|$path" }
        if (identity == artworkIdentity) return
        artworkIdentity = identity
        artworkJob?.cancel()
        if (current == null || path == null) return
        val uri = current.localConfiguration?.uri
        val version = current.mediaMetadata.extras?.getLong(PLAYBACK_ART_VERSION) ?: 0L
        val repo = container.trackArtRepository
        artworkJob = scope.launch {
            repo.requestLoad(path, version, uri)
            repo.observeFor(path, version, uri).collectLatest { art ->
                if (art !is TrackArtRepository.ArtLoadState.Ready) return@collectLatest
                val bytes = withContext(Dispatchers.IO) {
                    ByteArrayOutputStream().use { output ->
                        art.bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
                        output.toByteArray()
                    }
                }
                val active = player ?: return@collectLatest
                val item = active.currentMediaItem ?: return@collectLatest
                if (artworkIdentity != identity || item.mediaId != current.mediaId || item.localConfiguration?.uri != uri) return@collectLatest
                if (!item.mediaMetadata.artworkData.contentEquals(bytes)) {
                    active.replaceMediaItem(active.currentMediaItemIndex, item.buildUpon().setMediaMetadata(
                        item.mediaMetadata.buildUpon().setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER).build(),
                    ).build())
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        player?.release()
        session = null
        player = null
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "music_playback"
        const val NOTIFICATION_ID = 1001
    }
}
