package com.example.music.domain.art

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.example.music.data.local.SafStorage
import com.example.music.data.preferences.AppPreferences
import com.example.music.data.remote.ServerClient
import com.example.music.domain.art.ArtLog
import com.example.music.domain.model.Track

/** Covers live in private application storage, outside MediaStore/the gallery. */
class SidecarArtSync(
    private val context: Context,
    private val preferences: AppPreferences,
    private val storage: SafStorage,
    private val server: ServerClient,
) {
    private val disk = TrackArtDiskStore(context.applicationContext)

    suspend fun musicFolderUri(): Uri? = preferences.folderUriOnce()?.let(Uri::parse)

    suspend fun loadSidecarBitmap(audioRelativePath: String, audioUri: Uri? = null): Bitmap? {
        disk.loadBitmap(TrackArtCache.key(audioRelativePath))?.let { return it }
        audioUri?.let { uri ->
            storage.readSidecarNextToAudio(uri)?.let {
                disk.saveBitmap(TrackArtCache.key(audioRelativePath), it)
                ArtLog.d("sidecar.read.nextToAudio", "hit path=$audioRelativePath")
                return it
            }
        }
        val treeUri = preferences.folderUriOnce()?.let(Uri::parse) ?: run {
            ArtLog.w("sidecar.read.noFolder", audioRelativePath)
            return null
        }
        return storage.readSidecarBitmap(treeUri, audioRelativePath)?.also {
            disk.saveBitmap(TrackArtCache.key(audioRelativePath), it)
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
        return disk.save(TrackArtCache.key(audioRelativePath), bytes) != null
    }

    suspend fun hideLegacyGalleryCovers(tracks: List<Track>, folder: Uri? = null) {
        val tree = folder ?: musicFolderUri() ?: return
        storage.hideLegacySidecars(tree, tracks.map { it.relativePath }) { path, bytes ->
            val key = TrackArtCache.key(path)
            val existing = disk.loadBitmap(key)
            if (existing != null) { existing.recycle(); true }
            else disk.save(key, bytes) != null
        }
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
        val saved = disk.save(TrackArtCache.key(audioRelativePath), bytes) != null
        ArtLog.d("sidecar.persist.private", "path=$audioRelativePath saved=$saved bytes=${bytes.size}")
    }
}
