package com.example.music.data.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.music.data.local.MusicScanner
import com.example.music.data.local.SafStorage
import com.example.music.data.local.TrashEntry
import com.example.music.data.preferences.AppPreferences
import com.example.music.domain.art.TrackArtRepository
import com.example.music.domain.model.Track
import com.example.music.player.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LibraryRepository(
    private val context: Context,
    private val preferences: AppPreferences,
    private val scanner: MusicScanner,
    private val storage: SafStorage,
    private val trackArtRepository: TrackArtRepository,
    private val playbackController: PlaybackController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanMutex = Mutex()
    @Volatile private var indexedFolder: String? = null

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _lastDeleted = MutableStateFlow<TrashEntry?>(null)
    val lastDeleted: StateFlow<TrashEntry?> = _lastDeleted.asStateFlow()

    init {
        scope.launch {
            _lastDeleted.value = preferences.database.lastTrash()
            preferences.folderUri.collectLatest { uriString ->
                if (!uriString.isNullOrBlank()) {
                    val cached = preferences.cachedTracks(uriString)
                    _tracks.value = cached
                    indexedFolder = uriString
                    scan(
                        uri = Uri.parse(uriString),
                        cachedByRelativePath = cached.associateBy { it.relativePath.lowercase() },
                        showLoading = cached.isEmpty(),
                    )
                } else {
                    _tracks.value = emptyList()
                    indexedFolder = null
                }
            }
        }
    }

    suspend fun setFolder(uri: Uri) {
        val resolver = context.contentResolver
        val readFlag = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val writeFlag = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            resolver.takePersistableUriPermission(uri, readFlag or writeFlag)
        }.recoverCatching {
            resolver.takePersistableUriPermission(uri, readFlag)
            resolver.takePersistableUriPermission(uri, writeFlag)
        }.recoverCatching {
            resolver.takePersistableUriPermission(uri, readFlag)
        }.onFailure {
            _error.value = it.message ?: "Нет доступа к выбранной папке"
        }.getOrThrow()
        preferences.setFolderUri(uri.toString())
    }

    suspend fun deleteTrack(track: Track): Boolean {
        withContext(Dispatchers.Main.immediate) {
            playbackController.releaseTrackIfPlaying(track.id)
            playbackController.removeFromQueue(track.id)
        }

        return withContext(Dispatchers.IO) {
            try {
                val folder = preferences.folderUriOnce()?.let(Uri::parse) ?: return@withContext false
                if (indexedFolder != folder.toString() || _tracks.value.none { it.id == track.id }) return@withContext false
                val hidden = storage.moveToTrash(folder, track.relativePath)
                try {
                    val id = preferences.database.addTrash(folder.toString(), track.relativePath, hidden)
                    _lastDeleted.value = TrashEntry(id, folder.toString(), track.relativePath, hidden)
                } catch (error: Exception) {
                    storage.restoreFromTrash(folder, track.relativePath, hidden)
                    throw error
                }

                trackArtRepository.evictTrack(track)
                _tracks.update { tracks -> tracks.filter { it.id != track.id } }
                preferences.saveCachedTracks(folder.toString(), _tracks.value)
                scan(showLoading = false)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun undoDelete(): Boolean = withContext(Dispatchers.IO) {
        val entry = _lastDeleted.value ?: return@withContext false
        try {
            storage.restoreFromTrash(Uri.parse(entry.folder), entry.originalPath, entry.hiddenPath)
            preferences.database.removeTrash(entry.id)
            _lastDeleted.value = preferences.database.lastTrash()
            scan(showLoading = false)
            true
        } catch (error: Exception) {
            _error.value = error.message ?: "Не удалось восстановить трек"
            false
        }
    }

    suspend fun scan(
        uri: Uri? = null,
        cachedByRelativePath: Map<String, Track> = emptyMap(),
        showLoading: Boolean = true,
    ) = scanMutex.withLock {
        val folder = uri ?: preferences.folderUriOnce()?.let(Uri::parse) ?: return
        val folderString = folder.toString()
        _isScanning.value = showLoading
        _error.value = null
        try {
            val cachedMap = if (cachedByRelativePath.isNotEmpty()) {
                cachedByRelativePath
            } else {
                preferences.cachedTracks(folderString).associateBy { it.relativePath.lowercase() }
            }
            val result = withContext(Dispatchers.IO) { scanner.scan(folder, cachedMap) }
            currentCoroutineContext().ensureActive()
            if (preferences.folderUriOnce() != folderString) return@withLock
            _tracks.value = result
            indexedFolder = folderString
            preferences.saveCachedTracks(folderString, result)
            trackArtRepository.reloadTracks(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            _error.value = "Нет доступа к папке. Выберите её ещё раз."
            _tracks.value = emptyList()
        } catch (e: Exception) {
            _error.value = e.message ?: "Не удалось просканировать папку"
        } finally {
            _isScanning.value = false
        }
    }
}
