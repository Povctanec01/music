package com.example.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.music.data.download.DownloadFiles
import com.example.music.data.local.DownloadSnapshot
import com.example.music.data.local.LibraryDatabase
import com.example.music.domain.model.DownloadStatus
import com.example.music.domain.model.DownloadTask
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import kotlinx.coroutines.runBlocking

/** Can run aPrepare, reboot the test emulator, then run bVerify independently. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RebootPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun aPrepare() = runBlocking {
        (context.applicationContext as MusicApplication).container.downloadManager.awaitRestored()
        val file = DownloadFiles.stagedFile(File(context.filesDir, "downloads"), "reboot-folder", "song.wav", "v1")
        file.outputStream().use { it.write(byteArrayOf(1,2,3,4)); it.fd.sync() }
        LibraryDatabase(context, Json).use {
            it.saveDownloads(DownloadSnapshot("reboot-folder", listOf(DownloadTask("song.wav", 100, status = DownloadStatus.RUNNING, bytesDownloaded = 4, version = "v1"))))
        }
    }
    @Test fun bVerify() {
        LibraryDatabase(context, Json).use {
            val snapshot = it.downloadSnapshot()!!
            assertEquals("reboot-folder", snapshot.folder)
            assertEquals(4L, snapshot.tasks.single().bytesDownloaded)
        }
        val file = DownloadFiles.stagedFile(File(context.filesDir, "downloads"), "reboot-folder", "song.wav", "v1")
        assertArrayEquals(byteArrayOf(1,2,3,4), file.readBytes())
        if (InstrumentationRegistry.getArguments().getString("afterReboot") == "true") runBlocking {
            val manager = (context.applicationContext as MusicApplication).container.downloadManager
            manager.awaitRestored()
            assertTrue(manager.paused.value)
            assertEquals(DownloadStatus.PAUSED, manager.tasks.value.single().status)
            assertEquals(4L, manager.tasks.value.single().bytesDownloaded)
        }
    }
}
