package com.example.music.domain.art

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.example.music.data.local.SafStorage
import com.example.music.data.preferences.AppPreferences
import com.example.music.data.remote.ServerClient
import com.example.music.domain.art.ArtLog
import com.example.music.domain.model.Track

/** Downloads cover art from the server and stores `{stem}.jpg` next to audio in SAF folder. */
class SidecarArtSync(
    private val context: Context,
    private val preferences: AppPreferences,
    private val storage: SafStorage,
    private val server: ServerClient,
) {
    suspend fun musicFolderUri(): Uri? = preferences.folderUriOnce()?.let(Uri::parse)

    suspend fun loadSidecarBitmap(audioRelativePath: String, audioUri: Uri? = null): Bitmap? {
        audioUri?.let { uri ->
            storage.readSidecarNextToAudio(uri)?.let {
                ArtLog.d("sidecar.read.nextToAudio", "hit path=$audioRelativePath")
                return it
            }
        }
        val treeUri = preferences.folderUriOnce()?.let(Uri::parse) ?: run {
            ArtLog.w("sidecar.read.noFolder", audioRelativePath)
            return null
        }
        return storage.readSidecarBitmap(treeUri, audioRelativePath)?.also {
            ArtLog.d("sidecar.read.tree", "hit path=$audioRelativePath")
        } ?: run {
            ArtLog.d("sidecar.read.miss", audioRelativePath)
            null
        }
    }

    suspend fun downloadAndSave(
        treeUri: Uri,
        serverBaseUrl: String,
        audioRelativePath: String,
        mtime: Long,
        audioUri: Uri? = null,
        serverIndex: ServerArtIndex = ServerArtIndex.EMPTY,
        extraPaths: Array<String> = emptyArray(),
        force: Boolean = false,
    ): ByteArray? {
        if (!force) {
            loadSidecarBitmap(audioRelativePath, audioUri)?.let {
                ArtLog.d("sidecar.download.skip", "already on disk path=$audioRelativePath")
                return ByteArray(0)
            }
        }

        val sidecarPath = TrackSidecarArt.primarySidecarRelativePath(audioRelativePath)
        for (path in listOf(sidecarPath, sidecarPath.substringAfterLast('/')).distinct()) {
            ArtLog.d("sidecar.download.files", "try path=$path")
            server.downloadFileBytes(serverBaseUrl, path)?.let { bytes ->
                ArtLog.d("sidecar.download.files.hit", "path=$path bytes=${bytes.size}")
                persistSidecar(bytes, treeUri, audioRelativePath, audioUri)
                return bytes
            }
            ArtLog.d("sidecar.download.files.miss", path)
        }

        val resolved = serverIndex.resolve(audioRelativePath, *extraPaths)
        val artCandidates = buildList {
            resolved?.path?.let { add(it to resolved.mtime) }
            add(audioRelativePath.substringAfterLast('/') to mtime)
            add(audioRelativePath to mtime)
        }.distinctBy { it.first }

        for ((path, version) in artCandidates) {
            ArtLog.d("sidecar.download.apiArt", "try path=$path v=$version")
            server.downloadArtBytes(serverBaseUrl, path, version)?.let { bytes ->
                ArtLog.d("sidecar.download.apiArt.hit", "path=$path bytes=${bytes.size}")
                persistSidecar(bytes, treeUri, audioRelativePath, audioUri)
                return bytes
            }
            ArtLog.d("sidecar.download.apiArt.miss", path)
        }

        ArtLog.w("sidecar.download.fail", audioRelativePath)
        return null
    }

    suspend fun refreshTracksFromServer(
        tracks: List<Track>,
        serverBaseUrl: String,
        serverIndex: ServerArtIndex = ServerArtIndex.EMPTY,
        force: Boolean = false,
        maxTracks: Int = Int.MAX_VALUE,
    ): Int {
        val treeUri = preferences.folderUriOnce()?.let(Uri::parse) ?: return 0
        var saved = 0
        for (track in tracks.take(maxTracks)) {
            val bytes = downloadAndSave(
                treeUri = treeUri,
                serverBaseUrl = serverBaseUrl,
                audioRelativePath = track.relativePath,
                mtime = track.addedAt,
                audioUri = track.uri,
                serverIndex = serverIndex,
                extraPaths = arrayOf(track.displayName, track.title),
                force = force,
            )
            if (bytes != null) {
                saved++
            }
        }
        return saved
    }

    suspend fun saveBytes(audioUri: Uri?, audioRelativePath: String, bytes: ByteArray): Boolean {
        if (bytes.size <= 128) return false
        if (audioUri != null && storage.writeSidecarNextToAudio(audioUri, bytes)) {
            return true
        }
        val treeUri = preferences.folderUriOnce()?.let(Uri::parse) ?: return false
        return storage.writeSidecarImage(treeUri, audioRelativePath, bytes)
    }

    private fun persistSidecar(
        bytes: ByteArray,
        treeUri: Uri,
        audioRelativePath: String,
        audioUri: Uri?,
    ) {
        if (bytes.size <= 128) {
            ArtLog.w("sidecar.persist.skipSmall", "path=$audioRelativePath bytes=${bytes.size}")
            return
        }
        val nextOk = audioUri != null && storage.writeSidecarNextToAudio(audioUri, bytes)
        val treeOk = storage.writeSidecarImage(treeUri, audioRelativePath, bytes)
        ArtLog.d("sidecar.persist", "path=$audioRelativePath nextToAudio=$nextOk tree=$treeOk bytes=${bytes.size}")
    }
}
