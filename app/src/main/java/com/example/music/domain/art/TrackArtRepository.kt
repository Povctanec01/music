package com.example.music.domain.art

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.music.domain.metadata.TrackMetadataReader
import com.example.music.domain.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

class TrackArtRepository(
    context: Context,
    private val sidecarArtSync: SidecarArtSync,
) {
    private val appContext = context.applicationContext
    private val disk = TrackArtDiskStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadSemaphore = Semaphore(4)

    @Volatile
    var serverBaseUrl: String? = null

    @Volatile
    var serverIndex: ServerArtIndex = ServerArtIndex.EMPTY

    private val mutableFlows = ConcurrentHashMap<String, MutableStateFlow<ArtLoadState>>()
    private val publicFlows = ConcurrentHashMap<String, StateFlow<ArtLoadState>>()
    private val requests = ConcurrentHashMap<String, ArtRequest>()
    private val inFlight = ConcurrentHashMap<String, Job>()

    sealed interface ArtLoadState {
        data object Loading : ArtLoadState
        data class Ready(val bitmap: Bitmap) : ArtLoadState
        data object Missing : ArtLoadState
    }

    private data class ArtRequest(
        val cacheKey: String,
        val relativePath: String,
        val version: Long,
        val extraPaths: Array<String>,
        val contentUri: Uri? = null,
    )

    fun updateServerContext(baseUrl: String?, index: ServerArtIndex) {
        serverBaseUrl = baseUrl
        serverIndex = index
        scope.launch {
            requests.values.forEach { request ->
                val flow = mutableFlows[request.cacheKey] ?: return@forEach
                if (flow.value is ArtLoadState.Ready) return@forEach
                scheduleLoad(request, force = true)
            }
        }
    }

    fun evictTrack(track: Track) {
        val cacheKey = TrackArtCache.key(track.relativePath, track.displayName, track.title)
        inFlight[cacheKey]?.cancel()
        inFlight.remove(cacheKey)
        disk.delete(cacheKey)
        TrackArtCache.remove(cacheKey)
        mutableFlows[cacheKey]?.value = ArtLoadState.Missing
    }

    fun invalidateTrack(track: Track) {
        val cacheKey = TrackArtCache.key(track.relativePath, track.displayName, track.title)
        disk.delete(cacheKey)
        TrackArtCache.remove(cacheKey)
        mutableFlows[cacheKey]?.value = ArtLoadState.Loading
        requests[cacheKey]?.let { scheduleLoad(it, force = true) }
    }

    fun notifySidecarFromDisk(relativePath: String, version: Long = 0L, contentUri: Uri? = null) {
        val cacheKey = TrackArtCache.key(relativePath)
        if (mutableFlows[cacheKey]?.value is ArtLoadState.Ready) {
            ArtLog.d("art.notifyDisk.skipReady", relativePath)
            return
        }
        ArtLog.d("art.notifyDisk", relativePath)
        observeFor(relativePath, version, contentUri)
        val flow = mutableFlows[cacheKey] ?: return
        scope.launch {
            if (flow.value is ArtLoadState.Ready) return@launch
            sidecarArtSync.loadSidecarBitmap(relativePath, contentUri)?.let { bitmap ->
                persistAndPublish(cacheKey, flow, bitmap)
                ArtLog.d("art.notifyDisk.hit", relativePath)
            } ?: ArtLog.w("art.notifyDisk.miss", relativePath)
        }
    }

    fun refreshTracksSidecar(tracks: List<Track>, serverBaseUrl: String, force: Boolean = false) {
        scope.launch {
            sidecarArtSync.refreshTracksFromServer(
                tracks = tracks,
                serverBaseUrl = serverBaseUrl,
                serverIndex = serverIndex,
                force = force,
            )
            tracks.forEach { track ->
                notifySidecarFromDisk(track.relativePath, track.addedAt, track.uri)
            }
        }
    }

    fun reloadTracks(tracks: List<Track>) {
        tracks.forEach { ensureLoadedForTrack(it) }
    }

    fun ensureLoadedForTrack(track: Track) {
        observeForTrack(track)
        val cacheKey = TrackArtCache.key(track.relativePath, track.displayName, track.title)
        if (mutableFlows[cacheKey]?.value is ArtLoadState.Ready) return
        requests[cacheKey]?.let { scheduleLoad(it, force = false) }
    }

    fun onSidecarSaved(
        relativePath: String,
        version: Long,
        contentUri: Uri,
        bytes: ByteArray,
    ) {
        observeFor(relativePath, version, contentUri)
        publishSidecarBytes(relativePath, bytes, version, contentUri)
    }

    fun publishSidecarBytes(
        relativePath: String,
        bytes: ByteArray,
        version: Long = 0L,
        contentUri: Uri? = null,
    ) {
        if (bytes.isEmpty()) return
        val cacheKey = TrackArtCache.key(relativePath)
        if (!mutableFlows.containsKey(cacheKey)) {
            observeFor(relativePath, version, contentUri)
        }
        val flow = mutableFlows[cacheKey] ?: return
        scope.launch {
            decodeBitmap(bytes)?.let { bitmap ->
                persistAndPublish(cacheKey, flow, bitmap)
            } ?: sidecarArtSync.loadSidecarBitmap(relativePath, contentUri)?.let { bitmap ->
                persistAndPublish(cacheKey, flow, bitmap)
            }
        }
        // Refresh UI after library scan gives the track a new SAF uri.
        requests[cacheKey]?.let { request ->
            if (request.contentUri != contentUri && contentUri != null) {
                requests[cacheKey] = request.copy(contentUri = contentUri)
            }
        }
    }

    fun observeForTrack(track: Track): StateFlow<ArtLoadState> =
        observeFor(
            relativePath = track.relativePath,
            version = track.addedAt,
            contentUri = track.uri,
            extraPaths = arrayOf(track.displayName, track.title),
        )

    fun requestLoadForTrack(track: Track, force: Boolean = false) {
        observeForTrack(track)
        val cacheKey = TrackArtCache.key(track.relativePath, track.displayName, track.title)
        if (!force && mutableFlows[cacheKey]?.value is ArtLoadState.Ready) return
        requests[cacheKey]?.let { scheduleLoad(it, force = force) }
    }

    fun observeFor(
        relativePath: String,
        version: Long,
        contentUri: Uri? = null,
        vararg extraPaths: String,
    ): StateFlow<ArtLoadState> {
        val cacheKey = TrackArtCache.key(relativePath, *extraPaths)
        requests[cacheKey] = ArtRequest(
            cacheKey = cacheKey,
            relativePath = relativePath,
            version = version,
            extraPaths = Array(extraPaths.size) { extraPaths[it] },
            contentUri = contentUri,
        )
        return publicFlows.getOrPut(cacheKey) {
            val cached = localCachedBitmap(cacheKey)
            val mutable = MutableStateFlow(
                if (cached != null) ArtLoadState.Ready(cached) else ArtLoadState.Loading,
            )
            mutableFlows[cacheKey] = mutable
            mutable.asStateFlow()
        }
    }

    fun requestLoad(
        relativePath: String,
        version: Long,
        contentUri: Uri? = null,
        force: Boolean = false,
        vararg extraPaths: String,
    ) {
        observeFor(relativePath, version, contentUri, *extraPaths)
        val cacheKey = TrackArtCache.key(relativePath, *extraPaths)
        if (!force && mutableFlows[cacheKey]?.value is ArtLoadState.Ready) return
        requests[cacheKey]?.let { scheduleLoad(it, force = force) }
    }

    fun stateForTrack(track: Track): StateFlow<ArtLoadState> = observeForTrack(track)

    fun stateFor(
        relativePath: String,
        version: Long,
        contentUri: Uri? = null,
        vararg extraPaths: String,
    ): StateFlow<ArtLoadState> = observeFor(relativePath, version, contentUri, *extraPaths)

    private fun scheduleLoad(request: ArtRequest, force: Boolean = false) {
        val flow = mutableFlows[request.cacheKey] ?: return
        if (flow.value is ArtLoadState.Ready) return

        val active = inFlight[request.cacheKey]
        if (!force && active?.isActive == true) return
        if (force) active?.cancel()

        ArtLog.d("art.scheduleLoad", "path=${request.relativePath} force=$force state=${flow.value::class.simpleName}")
        inFlight[request.cacheKey] = scope.launch {
            try {
                loadArt(request, flow)
            } finally {
                inFlight.remove(request.cacheKey)
            }
        }
    }

    private suspend fun loadArt(request: ArtRequest, flow: MutableStateFlow<ArtLoadState>) {
        if (flow.value is ArtLoadState.Ready) return
        val start = System.currentTimeMillis()
        ArtLog.d("art.load.start", request.relativePath)
        downloadSemaphore.withPermit {
            val finished = withTimeoutOrNull(45_000L) {
                try {
                    localCachedBitmap(request.cacheKey)?.let { bitmap ->
                        publishReady(request.cacheKey, flow, bitmap)
                        return@withTimeoutOrNull true
                    }

                    sidecarArtSync.loadSidecarBitmap(request.relativePath, request.contentUri)?.let { bitmap ->
                        persistAndPublish(request.cacheKey, flow, bitmap)
                        return@withTimeoutOrNull true
                    }

                    if (flow.value !is ArtLoadState.Ready) {
                        flow.value = ArtLoadState.Loading
                    }

                    request.contentUri?.let { uri ->
                        loadEmbedded(uri)?.let { bitmap ->
                            persistAndPublish(request.cacheKey, flow, bitmap)
                            return@withTimeoutOrNull true
                        }
                    }

                    val baseUrl = serverBaseUrl
                    val folderUri = sidecarArtSync.musicFolderUri()
                    if (!baseUrl.isNullOrBlank() && folderUri != null) {
                        sidecarArtSync.downloadAndSave(
                            treeUri = folderUri,
                            serverBaseUrl = baseUrl,
                            audioRelativePath = request.relativePath,
                            mtime = request.version,
                            audioUri = request.contentUri,
                            serverIndex = serverIndex,
                            extraPaths = request.extraPaths,
                            force = false,
                        )?.takeIf { it.isNotEmpty() }?.let { bytes ->
                            decodeBitmap(bytes)?.let { bitmap ->
                                runCatching { disk.save(request.cacheKey, bytes) }
                                publishReady(request.cacheKey, flow, bitmap)
                                return@withTimeoutOrNull true
                            }
                        }
                    }

                    flow.value = ArtLoadState.Missing
                    ArtLog.w("art.load.missing", "${request.relativePath} ${System.currentTimeMillis() - start}ms")
                    true
                } catch (e: Exception) {
                    flow.value = ArtLoadState.Missing
                    ArtLog.e("art.load.error", request.relativePath, e)
                    true
                }
            }
            if (finished == null) {
                flow.value = ArtLoadState.Missing
                ArtLog.w("art.load.timeout", "${request.relativePath} ${System.currentTimeMillis() - start}ms")
            }
        }
    }

    private fun decodeBitmap(bytes: ByteArray): Bitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?.takeIf { it.width > 1 && it.height > 1 }

    private fun localCachedBitmap(cacheKey: String): Bitmap? {
        TrackArtCache.get(cacheKey)?.let { return it }
        disk.loadBitmap(cacheKey)?.let { bitmap ->
            TrackArtCache.put(cacheKey, bitmap)
            return bitmap
        }
        return null
    }

    private fun loadEmbedded(uri: Uri): Bitmap? =
        runCatching { TrackMetadataReader.readEmbeddedArt(appContext, uri) }
            .getOrNull()
            ?.takeIf { it.width > 1 && it.height > 1 }

    private fun persistAndPublish(cacheKey: String, flow: MutableStateFlow<ArtLoadState>, bitmap: Bitmap) {
        runCatching { disk.saveBitmap(cacheKey, bitmap) }
        publishReady(cacheKey, flow, bitmap)
    }

    private fun publishReady(cacheKey: String, flow: MutableStateFlow<ArtLoadState>, bitmap: Bitmap) {
        TrackArtCache.put(cacheKey, bitmap)
        flow.value = ArtLoadState.Ready(bitmap)
        ArtLog.d("art.ready", cacheKey)
    }
}
