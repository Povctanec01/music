package com.example.music

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.music.domain.model.Track
import com.example.music.player.MusicPlaybackService
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class QueueRegressionTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as MusicApplication).container

    @Test fun rapidShuffleTogglesCannotOverwriteOrderedQueueAndRequestsSurviveModes() = runBlocking {
        container.preferences.setGithubRepo("")
        container.appUpdateManager.checkForUpdates()
        val tracks = listOf("A", "B", "C", "D").map { name ->
            val file = File(context.filesDir, "queue-test-$name.wav").apply { writeBytes(pcmWav(30)) }
            Track(file.toURI().toString(), Uri.fromFile(file), file.name, file.name, "Queue", "", "", 30_000, file.length(), null, null, file.lastModified(), "audio/wav")
        }
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            val playback = container.playbackController
            withTimeout(20_000) {
                var focused = false
                while (!focused || !playback.state.value.connected) { activity.onActivity { focused = it.hasWindowFocus() }; delay(100) }
            }
            val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, MusicPlaybackService::class.java))).buildAsync()
            val remote = withContext(Dispatchers.IO) { future.get(10, TimeUnit.SECONDS) }
            suspend fun ids(): List<String> = withContext(Dispatchers.Main) { List(remote.mediaItemCount) { remote.getMediaItemAt(it).mediaId } }
            suspend fun waitForId(id: String) = withTimeout(5000) { while (playback.state.value.currentId != id) delay(50) }
            suspend fun startPaused() {
                withContext(Dispatchers.Main) {
                    if (playback.state.value.shuffle) playback.toggleShuffle()
                    playback.play(tracks, 0)
                }
                withTimeout(10_000) { while (!playback.state.value.isPlaying) delay(50) }
                withContext(Dispatchers.Main) { remote.pause(); remote.repeatMode = Player.REPEAT_MODE_ALL }
                withTimeout(5000) { while (playback.state.value.isPlaying || playback.state.value.repeatMode != Player.REPEAT_MODE_ALL) delay(50) }
            }
            try {
                startPaused()
                withContext(Dispatchers.Main) { repeat(12) { playback.toggleShuffle(); playback.toggleShuffle() } }
                delay(1000)
                assertFalse(playback.state.value.shuffle)
                assertFalse(playback.state.value.isPlaying)
                assertEquals(tracks.map { it.id }, ids())
                withContext(Dispatchers.Main) { assertEquals(Player.REPEAT_MODE_ALL, remote.repeatMode) }

                // The most recent request wins, and toggling modes never drops requests.
                withContext(Dispatchers.Main) {
                    playback.toggleShuffle()
                    playback.playNext(tracks[2])
                    playback.playNext(tracks[3])
                    playback.toggleShuffle()
                }
                delay(700)
                assertEquals(listOf(tracks[0].id, tracks[3].id, tracks[2].id, tracks[1].id), ids())
                assertFalse(playback.state.value.isPlaying)
                withContext(Dispatchers.Main) { playback.skipNext() }
                waitForId(tracks[3].id)
                withContext(Dispatchers.Main) { playback.skipNext() }
                waitForId(tracks[2].id)

                startPaused()
                withContext(Dispatchers.Main) { playback.toggleShuffle() }
                delay(500)
                withContext(Dispatchers.Main) { playback.playNext(tracks[2]); playback.skipNext() }
                waitForId(tracks[2].id)
                delay(500)
                assertEquals(tracks[2].id, playback.state.value.currentId)
                assertTrue(playback.state.value.shuffle)

                startPaused()
                withContext(Dispatchers.Main) { playback.playNext(tracks[0]); playback.skipNext() }
                withTimeout(5000) { while (playback.state.value.currentIndex != 1) delay(50) }
                assertEquals(tracks[0].id, playback.state.value.currentId)
                withContext(Dispatchers.Main) { playback.skipNext() }
                waitForId(tracks[1].id)
            } finally {
                withContext(Dispatchers.Main) {
                    if (playback.state.value.shuffle) playback.toggleShuffle()
                    tracks.forEach { playback.removeFromQueue(it.id) }
                    remote.release()
                }
            }
        }
    }
}
