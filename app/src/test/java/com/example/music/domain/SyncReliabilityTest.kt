package com.example.music.domain

import com.example.music.data.download.DownloadFiles
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SyncReliabilityTest {
    @Test fun detectsSameSizeUpdatesAndUnknownChecksums() {
        assertTrue(SyncComparison.needsDownload(10, "old", 10, "new"))
        assertTrue(SyncComparison.needsDownload(10, null, 10, "new"))
        assertFalse(SyncComparison.needsDownload(10, "same", 10, "SAME"))
        assertTrue(SyncComparison.needsDownload(null, null, 10, "new"))
    }
    @Test fun partialPathsIncludeFolderAndVersionAndVerifyBytes() {
        val root = kotlin.io.path.createTempDirectory("downloads").toFile()
        try {
            val first = DownloadFiles.stagedFile(root, "folder", "a.wav", "v1")
            first.writeBytes(byteArrayOf(1,2,3))
            val hash = DownloadFiles.sha256(first)
            DownloadFiles.verify(first, 3, hash)
            assertNotEquals(first, DownloadFiles.stagedFile(root, "folder", "a.wav", "v2"))
            assertNotEquals(first, DownloadFiles.stagedFile(root, "other", "a.wav", "v1"))
            assertTrue(runCatching { DownloadFiles.verify(first, 4, hash) }.isFailure)
            assertTrue(runCatching { DownloadFiles.verify(first, 3, "bad") }.isFailure)
        } finally { root.deleteRecursively() }
    }
}
