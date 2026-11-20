package com.example.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.music.domain.art.TrackArtRepository
import com.example.music.domain.model.Track
import com.example.music.player.resumptionPlaylist
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackRegressionTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as MusicApplication).container

    @Test fun coversArePrivateAndLegacyMigrationKeepsAudioAndUnrelatedImages() = runBlocking {
        provider(context, "reset")
        val prefs = container.preferences
        prefs.setFolderUri(TEST_TREE.toString())
        val root = container.safStorage.root(TEST_TREE)
        val legacy = root.createFile("image/jpeg", "legacy.jpg")!!
        context.contentResolver.openOutputStream(legacy.uri)!!.use { it.write(cover(Color.RED)) }
        val unrelated = root.createFile("image/jpeg", "holiday.jpg")!!
        context.contentResolver.openOutputStream(unrelated.uri)!!.use { it.write(cover(Color.GREEN)) }
        val audio = root.createFile("audio/wav", "legacy.wav")!!
        context.contentResolver.openOutputStream(audio.uri)!!.use { it.write(pcmWav(1)) }
        assertTrue(container.sidecarArtSync.saveBytes(audio.uri, "new-private-cover.wav", cover(Color.BLUE)))
        assertNull(root.findFile("new-private-cover.jpg"))
        assertNotNull(container.sidecarArtSync.loadSidecarBitmap("new-private-cover.wav"))
        // A stale public JPG must not replace a newer private thumbnail during migration.
        container.sidecarArtSync.saveBytes(audio.uri, "legacy.wav", cover(Color.BLUE))
        container.sidecarArtSync.hideLegacyGalleryCovers(listOf(track(audio.uri, "legacy.wav")))
        assertNull(root.findFile("legacy.jpg"))
        assertNotNull(root.findFile(".music-cover-legacy.jpg"))
        assertNotNull(root.findFile("legacy.wav"))
        assertNotNull(root.findFile("holiday.jpg"))
        assertNull(root.findFile(".nomedia"))
        val currentCover = container.sidecarArtSync.loadSidecarBitmap("legacy.wav")!!
        assertTrue(Color.blue(currentCover.getPixel(8, 8)) > 220)
        currentCover.recycle()
    }

    @Test fun concurrentArtworkObserversAllReceiveThePublishedCover() = runBlocking {
        val pool = Executors.newFixedThreadPool(16)
        try {
            repeat(12) { iteration ->
                val path = "art-race-${System.nanoTime()}-$iteration.wav"
                val gate = CountDownLatch(1)
                val readers = (0 until 16).map { pool.submit<kotlinx.coroutines.flow.StateFlow<TrackArtRepository.ArtLoadState>> {
                    gate.await(); container.trackArtRepository.observeFor(path, 0)
                } }
                gate.countDown()
                val flows = readers.map { it.get(5, TimeUnit.SECONDS) }
                assertTrue(flows.all { it === flows.first() })
                container.trackArtRepository.publishSidecarBytes(path, cover(Color.RED))
                withTimeout(5000) { flows.first().first { it is TrackArtRepository.ArtLoadState.Ready } }
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun backgroundNotificationAndHeadsetButtonsResumeAndArtworkFollowsNextPrevious() = runBlocking {
        container.preferences.setGithubRepo("")
        container.appUpdateManager.checkForUpdates()
        val red = File(context.filesDir, "regression-red.wav").apply { writeBytes(pcmWav(30)) }
        val blue = File(context.filesDir, "regression-blue.wav").apply { writeBytes(pcmWav(30)) }
        val tracks = listOf(track(Uri.fromFile(red), red.name), track(Uri.fromFile(blue), blue.name))
        container.sidecarArtSync.saveBytes(tracks[0].uri, tracks[0].relativePath, cover(Color.RED))
        container.sidecarArtSync.saveBytes(tracks[1].uri, tracks[1].relativePath, cover(Color.BLUE))
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            val playback = container.playbackController
            withTimeout(20_000) {
                var focused = false
                while (!focused || !playback.state.value.connected) {
                    activity.onActivity { focused = it.hasWindowFocus() }; delay(100)
                }
            }
            withContext(Dispatchers.Main) { playback.play(tracks, 0) }
            withTimeout(10_000) { while (!playback.state.value.isPlaying) delay(50) }
            val token = androidx.media3.session.SessionToken(context, android.content.ComponentName(context, com.example.music.player.MusicPlaybackService::class.java))
            val future = androidx.media3.session.MediaController.Builder(context, token).buildAsync()
            val remote = withContext(Dispatchers.IO) { future.get(10, TimeUnit.SECONDS) }
            try {
                suspend fun assertCover(color: Int) {
                    withTimeout(10_000) {
                        while (true) {
                            val bytes = withContext(Dispatchers.Main) { remote.mediaMetadata.artworkData }
                            val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                            val actual = bitmap?.getPixel(8, 8)
                            bitmap?.recycle()
                            if (actual != null && kotlin.math.abs(Color.red(actual) - Color.red(color)) < 20 && kotlin.math.abs(Color.blue(actual) - Color.blue(color)) < 20) break
                            delay(50)
                        }
                    }
                }
                assertCover(Color.RED)
                repeat(3) {
                    withContext(Dispatchers.Main) { remote.seekToNextMediaItem() }
                    assertCover(Color.BLUE)
                    withContext(Dispatchers.Main) { remote.seekToPreviousMediaItem() }
                    assertCover(Color.RED)
                }
                activity.moveToState(Lifecycle.State.CREATED)
                withContext(Dispatchers.Main) { remote.pause() }
                withTimeout(5000) { while (playback.state.value.isPlaying) delay(50) }
                delay(500)
                val audio = context.getSystemService(AudioManager::class.java)
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                withTimeout(8000) { while (!playback.state.value.isPlaying) delay(50) }
                withContext(Dispatchers.Main) { remote.pause() }
                withTimeout(5000) { while (playback.state.value.isPlaying) delay(50) }
                delay(500)
                val notifications = context.getSystemService(android.app.NotificationManager::class.java).activeNotifications
                val play = notifications.flatMap { it.notification.actions?.toList().orEmpty() }
                    .first { it.title.toString().lowercase().let { title -> title.contains("play") || title.contains("воспроиз") || title.contains("играть") } }
                play.actionIntent.send()
                withTimeout(8000) { while (!playback.state.value.isPlaying) delay(50) }
            } finally {
                withContext(Dispatchers.Main) { remote.pause(); remote.release(); playback.removeFromQueue(tracks[0].id); playback.removeFromQueue(tracks[1].id) }
            }
        }
    }

    @Test fun resumptionAnchorsCurrentTrackAfterAnEarlierQueueItemDisappears() = runBlocking {
        val prefs = container.preferences
        val oldFolder = prefs.folderUriOnce()
        val folder = "resumption-regression-${System.nanoTime()}"
        val a = track(Uri.parse("file:///a.wav"), "a.wav")
        val b = track(Uri.parse("file:///b.wav"), "b.wav")
        val c = track(Uri.parse("file:///c.wav"), "c.wav")
        try {
            prefs.setFolderUri(folder)
            prefs.saveCachedTracks(folder, listOf(b, c))
            prefs.savePlayback(listOf(a.id, b.id, c.id), 1, 1234, b.id)
            assertEquals(listOf(b.id, c.id), prefs.cachedTracks(folder).map { it.id })
            assertEquals(b.id, prefs.playbackSnapshot().mediaId)
            val restored = prefs.resumptionPlaylist()
            assertEquals(b.id, restored.mediaItems[restored.startIndex].mediaId)
            assertEquals(1234L, restored.startPositionMs)
        } finally { if (oldFolder != null) prefs.setFolderUri(oldFolder) }
    }

    private fun track(uri: Uri, path: String) = Track(uri.toString(), uri, path, path, "Regression", "", "", 30_000, 0, null, null, 1, "audio/wav")
    private fun cover(color: Int): ByteArray = ByteArrayOutputStream().use { output ->
        Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color); compress(Bitmap.CompressFormat.JPEG, 95, output); recycle()
        }
        output.toByteArray()
    }
}
