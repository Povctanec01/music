package com.example.music

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.music.data.download.DownloadFiles
import com.example.music.data.local.DownloadSnapshot
import com.example.music.data.local.LibraryDatabase
import com.example.music.data.local.MusicScanner
import com.example.music.data.local.SafStorage
import com.example.music.domain.model.DownloadStatus
import com.example.music.domain.model.DownloadTask
import com.example.music.domain.trim.AudioTrimmer
import com.example.music.domain.trim.WaveformPeaks
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val TEST_AUTHORITY = "com.example.music.debug.documents"
internal val TEST_TREE: Uri get() = DocumentsContract.buildTreeDocumentUri(TEST_AUTHORITY, "root")
internal fun provider(context: Context, method: String): android.os.Bundle? {
    return context.contentResolver.call(Uri.parse("content://$TEST_AUTHORITY"), method, null, null)
}
internal fun pcmWav(seconds: Int = 3): ByteArray {
    val frames = seconds * 8000
    return ByteArray(44 + frames * 4).also { bytes ->
        val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun text(offset: Int, value: String) { value.toByteArray().copyInto(bytes, offset) }
        text(0,"RIFF"); view.putInt(4,bytes.size-8); text(8,"WAVE"); text(12,"fmt ")
        view.putInt(16,16); view.putShort(20,1); view.putShort(22,2); view.putInt(24,8000)
        view.putInt(28,32000); view.putShort(32,4); view.putShort(34,16); text(36,"data"); view.putInt(40,frames*4)
        repeat(frames) { frame -> view.putShort(44 + frame * 4, 0); view.putShort(46 + frame * 4, (if (frame % 8 < 4) 4000 else -4000).toShort()) }
    }
}

@RunWith(AndroidJUnit4::class)
class ReliabilityTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val storage get() = SafStorage(context)
    @Before fun reset() { provider(context, "reset") }
    private fun source(bytes: ByteArray) = File(context.cacheDir, "verified-test.wav").apply { writeBytes(bytes) }
    private fun read(path: String) = DocumentFile.fromTreeUri(context, TEST_TREE)!!.findFile(path)!!.uri.let { context.contentResolver.openInputStream(it)!!.use { input -> input.readBytes() } }

    @Test fun replacementAndFailedRenameKeepOriginalSafe() {
        val old = pcmWav(3)
        val first = source(old)
        storage.replaceVerified(TEST_TREE, "song.wav", first, DownloadFiles.sha256(first))
        val replacement = source(pcmWav(1))
        provider(context, "failRename")
        assertTrue(runCatching { storage.replaceVerified(TEST_TREE, "song.wav", replacement, DownloadFiles.sha256(replacement)) }.isFailure)
        assertArrayEquals(old, read("song.wav"))
        storage.replaceVerified(TEST_TREE, "song.wav", replacement, DownloadFiles.sha256(replacement))
        assertArrayEquals(replacement.readBytes(), read("song.wav"))
    }

    @Test fun checksumFailureNeverReplacesExistingFile() {
        val first = source(pcmWav())
        storage.replaceVerified(TEST_TREE, "song.wav", first, DownloadFiles.sha256(first))
        val original = read("song.wav")
        assertTrue(runCatching { storage.replaceVerified(TEST_TREE, "song.wav", source(pcmWav(1)), "wrong") }.isFailure)
        assertArrayEquals(original, read("song.wav"))
        assertTrue(runCatching { storage.replaceVerified(TEST_TREE, "song.wav", first, DownloadFiles.sha256(first)) { false } }.isFailure)
        assertArrayEquals(original, read("song.wav"))
    }

    @Test fun trashRestoresAndRefusesOverwrite() {
        val first = source(pcmWav())
        storage.replaceVerified(TEST_TREE, "song.wav", first, DownloadFiles.sha256(first))
        val hidden = storage.moveToTrash(TEST_TREE, "song.wav")
        assertNull(DocumentFile.fromTreeUri(context, TEST_TREE)!!.findFile("song.wav"))
        storage.restoreFromTrash(TEST_TREE, "song.wav", hidden)
        assertArrayEquals(first.readBytes(), read("song.wav"))
        val nextHidden = storage.moveToTrash(TEST_TREE, "song.wav")
        storage.replaceVerified(TEST_TREE, "song.wav", first, DownloadFiles.sha256(first))
        assertTrue(runCatching { storage.restoreFromTrash(TEST_TREE, "song.wav", nextHidden) }.isFailure)
    }

    @Test fun scannerAndDatabaseReuseUnchangedMetadata() = runBlocking {
        val first = source(pcmWav())
        storage.replaceVerified(TEST_TREE, "song.wav", first, DownloadFiles.sha256(first))
        val scanner = MusicScanner(context)
        val tracks = scanner.scan(TEST_TREE)
        assertEquals(1, tracks.size)
        assertEquals(DownloadFiles.sha256(first), tracks.single().sha256)
        val dbContext = context
        LibraryDatabase(dbContext, Json, "reliability-test.db").use { it.saveTracks(TEST_TREE.toString(), tracks) }
        LibraryDatabase(dbContext, Json, "reliability-test.db").use { database ->
            provider(context, "resetReads")
            val cached = database.tracks(TEST_TREE.toString())
            val again = scanner.scan(TEST_TREE, cached.associateBy { it.relativePath.lowercase() })
            assertEquals(tracks.single().sha256, again.single().sha256)
            assertEquals(0, provider(context, "reads")!!.getInt("count"))
            database.saveDownloads(DownloadSnapshot(TEST_TREE.toString(), listOf(DownloadTask("song.wav", 123, status = DownloadStatus.RUNNING, bytesDownloaded = 50))))
        }
        LibraryDatabase(dbContext, Json, "reliability-test.db").use { assertEquals(50L, it.downloadSnapshot()!!.tasks.single().bytesDownloaded) }
    }

    @Test fun actualCodecTrimsStereoAndBuildsWaveform() = runBlocking {
        val first = source(pcmWav())
        val uri = Uri.fromFile(first)
        val trimmed = AudioTrimmer.trimToWav(context, uri, 1f, 2f)
        val header = ByteBuffer.wrap(trimmed).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2, header.getShort(22).toInt())
        assertEquals(32000, header.getInt(40))
        val peaks = WaveformPeaks.load(context, uri, 60)
        assertEquals(60, peaks.size)
        assertTrue(peaks.take(20).all { it > 0.5f })
        assertTrue(peaks.takeLast(20).all { it > 0.5f })
    }

    @Test fun largeLibraryWritesOnlyChangedRows() = runBlocking {
        val first = source(pcmWav(1))
        storage.replaceVerified(TEST_TREE, "song.wav", first, DownloadFiles.sha256(first))
        val seed = MusicScanner(context).scan(TEST_TREE).single()
        val tracks = List(2000) { seed.copy(id = "large-$it", relativePath = "group/song-$it.wav") }
        LibraryDatabase(context, Json, "large-library-test.db").use { database ->
            database.saveTracks("large", tracks)
            fun changes() = database.writableDatabase.rawQuery("SELECT total_changes()", null).use { it.moveToFirst(); it.getLong(0) }
            val before = changes()
            database.saveTracks("large", tracks)
            assertEquals(before, changes())
            database.saveTracks("large", tracks.mapIndexed { index, track -> if (index == 1000) track.copy(title = "Updated") else track })
            assertEquals(before + 1, changes())
            assertEquals(2000, database.tracks("large").size)
        }
    }
}
