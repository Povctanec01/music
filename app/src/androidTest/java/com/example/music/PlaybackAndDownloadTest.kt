package com.example.music

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.music.data.download.DownloadFiles
import com.example.music.data.local.DownloadSnapshot
import com.example.music.domain.model.DownloadNetworkMode
import com.example.music.domain.model.DownloadStatus
import com.example.music.domain.model.RemoteFile
import com.example.music.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class PlaybackAndDownloadTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as MusicApplication).container

    @Test fun playerKeepsPlayingInBackgroundAndSleepTimerPauses() = runBlocking {
        container.preferences.setGithubRepo("")
        container.appUpdateManager.checkForUpdates()
        val source = File(context.filesDir, "playback-test.wav").apply { writeBytes(pcmWav(20)) }
        val track = Track("test-playback", Uri.fromFile(source), source.name, source.name, "Test", "", "", 20_000, source.length(), null, null, source.lastModified(), "audio/wav")
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            val playback = container.playbackController
            withTimeout(20_000) {
                var focused = false
                while (!focused) {
                    activity.onActivity { focused = it.hasWindowFocus() }
                    if (!focused) delay(100)
                }
            }
            withTimeout(10_000) { while (!playback.state.value.connected) delay(100) }
            withContext(Dispatchers.Main) { playback.play(listOf(track), 0) }
            withTimeout(10_000) { while (!playback.state.value.isPlaying) delay(100) }
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { screenshot ->
                File(context.getExternalFilesDir(null), "playback-test.png")
                    .outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
            }
            activity.moveToState(Lifecycle.State.CREATED)
            delay(1000)
            assertTrue(playback.state.value.isPlaying)
            assertTrue(playback.state.value.positionMs > 0)
            assertTrue(context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.isNotEmpty())
            activity.moveToState(Lifecycle.State.RESUMED)
            withTimeout(20_000) {
                var focused = false
                while (!focused) {
                    activity.onActivity { focused = it.hasWindowFocus() }
                    if (!focused) delay(100)
                }
            }
            withTimeout(5000) { while (!playback.state.value.isPlaying) delay(100) }
            withContext(Dispatchers.Main) { playback.startSleepTimer(1000, fade = false) }
            withTimeout(5000) { while (playback.state.value.isPlaying) delay(100) }
            assertEquals(0L, playback.state.value.sleepRemainingMs)
            withContext(Dispatchers.Main) { playback.cancelSleepTimer(); playback.removeFromQueue(track.id) }
        }
    }

    @Test fun downloadsResumeFromPrivatePartialAndCommitOnlyVerifiedAudio() = runBlocking {
        val manager = container.downloadManager
        manager.awaitRestored()
        manager.cancel()
        provider(context, "reset")
        context.grantUriPermission(context.packageName, TEST_TREE,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        assertTrue("Fixture must have write access", container.safStorage.root(TEST_TREE).canWrite())
        val bytes = pcmWav(6)
        val source = File(context.cacheDir, "network-source.wav").apply { writeBytes(bytes) }
        val checksum = DownloadFiles.sha256(source)
        val rangeOffset = AtomicLong(-1)
        val server = LocalMusicServer(bytes, checksum, rangeOffset)
        server.use {
            val prefs = container.preferences
            prefs.setFolderUri(TEST_TREE.toString())
            prefs.setLanUrl("http://127.0.0.1:${server.port}")
            prefs.setTailscaleUrl("")
            prefs.setNetworkMode(DownloadNetworkMode.ANY)
            prefs.database.saveDownloads(DownloadSnapshot(TEST_TREE.toString(), emptyList()))
            val old = File(context.cacheDir, "old-network-source.wav").apply { writeBytes(bytes.copyOf().apply { this[50] = (this[50].toInt() xor 1).toByte() }) }
            container.safStorage.replaceVerified(TEST_TREE, "song.wav", old, DownloadFiles.sha256(old))
            val staged = DownloadFiles.stagedFile(File(context.filesDir, "downloads"), TEST_TREE.toString(), "song.wav", "test-v1")
            staged.writeBytes(bytes.copyOfRange(0, 5000))
            ActivityScenario.launch(MainActivity::class.java).use {
                manager.enqueue(listOf(RemoteFile("song.wav", bytes.size.toLong(), hasArt = true, sha256 = checksum, version = "test-v1")))
                val completed = withTimeoutOrNull(45_000) {
                    while (manager.tasks.value.singleOrNull()?.status != DownloadStatus.DONE || manager.running.value) delay(100)
                    true
                } ?: false
                assertTrue("Download did not finish: ${manager.statusMessage.value}; ${manager.lastError.value}; ${manager.tasks.value}", completed)
                assertEquals(DownloadStatus.DONE, manager.tasks.value.single().status)
                assertEquals(5000L, rangeOffset.get())
                assertFalse(staged.exists())
                val track = container.scanner.scan(TEST_TREE).single()
                assertEquals(checksum, track.sha256)
                manager.cancel()
            }
        }
    }
}

private class LocalMusicServer(private val audio: ByteArray, private val hash: String, private val range: AtomicLong) : AutoCloseable {
    private val socket = ServerSocket(0)
    val port get() = socket.localPort
    private val cover = ByteArrayOutputStream().also {
        Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff22aa55.toInt()); compress(Bitmap.CompressFormat.JPEG, 90, it); recycle() }
    }.toByteArray()
    private val worker = Thread {
        while (!socket.isClosed) {
            try { socket.accept().use { client ->
                val reader = client.getInputStream().bufferedReader()
                val request = reader.readLine() ?: return@use
                val path = request.split(' ')[1]
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                }
                val offset = headers["range"]?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                val body = when {
                    path == "/api/health" -> "{\"status\":\"ok\"}".toByteArray()
                    path == "/api/library" -> "{\"files\":[{\"path\":\"song.wav\",\"size\":${audio.size},\"sha256\":\"$hash\",\"version\":\"test-v1\",\"hasArt\":true}]}".toByteArray()
                    path.startsWith("/files/song.wav") -> { range.set(offset.toLong()); audio.copyOfRange(offset, audio.size) }
                    else -> cover
                }
                val partial = path.startsWith("/files/song.wav") && offset > 0
                val response = "HTTP/1.1 ${if (partial) "206 Partial Content" else "200 OK"}\r\nContent-Length: ${body.size}\r\nConnection: close\r\n" +
                    (if (partial) "Content-Range: bytes $offset-${audio.size-1}/${audio.size}\r\n" else "") + "\r\n"
                client.getOutputStream().apply { write(response.toByteArray()); write(body); flush() }
            } } catch (_: java.io.IOException) { if (socket.isClosed) break }
        }
    }.apply { isDaemon = true; start() }
    override fun close() { socket.close(); worker.join(2000) }
}
