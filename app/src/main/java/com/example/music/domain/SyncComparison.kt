package com.example.music.domain

object SyncComparison {
    fun needsDownload(localSize: Long?, localSha256: String?, remoteSize: Long, remoteSha256: String?): Boolean {
        if (localSize == null || localSize != remoteSize) return true
        return !remoteSha256.isNullOrBlank() && !remoteSha256.equals(localSha256, ignoreCase = true)
    }
}
