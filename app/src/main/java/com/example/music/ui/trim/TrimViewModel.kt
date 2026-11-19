package com.example.music.ui.trim

import android.app.Application
import com.example.music.domain.metadata.TrackMetadataReader
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.music.MusicApplication
import com.example.music.domain.AudioFormats
import com.example.music.domain.model.ConnectionChannel
import com.example.music.domain.model.RemoteFile
import com.example.music.domain.model.ServerEndpoint
import com.example.music.domain.model.Track
import com.example.music.domain.trim.AudioTrimmer
import com.example.music.domain.trim.WaveformPeaks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class TrimUiState(
    val checking: Boolean = false,
    val busy: Boolean = false,
    val loadingLibrary: Boolean = false,
    val endpoint: ServerEndpoint? = null,
    val libraryTracks: List<Track> = emptyList(),
    val remoteTracks: List<RemoteFile> = emptyList(),
    val query: String = "",
    val fileUri: Uri? = null,
    val fileName: String? = null,
    val durationMs: Long = 0L,
    val startSec: Float = 0f,
    val endSec: Float = 0f,
    val currentSec: Float = 0f,
    val playing: Boolean = false,
    val waveformPeaks: FloatArray = FloatArray(0),
    val loadingWaveform: Boolean = false,
    val uploadDir: String = "",
    val message: String? = null,
    val error: String? = null,
)

class TrimViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as MusicApplication).container
    private val server = container.serverClient
    private val preferences = container.preferences
    private val library = container.libraryRepository

    private val core = MutableStateFlow(TrimUiState())
    private val query = MutableStateFlow("")
    private var waveformJob: Job? = null

    val state: StateFlow<TrimUiState> = combine(core, query, library.tracks) { base, q, tracks ->
        base.copy(libraryTracks = tracks, query = q)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrimUiState())

    val filteredLocalTracks: StateFlow<List<Track>> = combine(state, query) { s, q ->
        filterTracks(s.libraryTracks, q)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val filteredRemoteTracks: StateFlow<List<RemoteFile>> = combine(state, query) { s, q ->
        filterRemote(s.remoteTracks, q)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refreshEndpoint()
    }

    fun refreshEndpoint() {
        viewModelScope.launch {
            core.update { it.copy(checking = true, loadingLibrary = true, error = null) }
            try {
                val endpoint = server.pickEndpoint(
                    preferences.lanUrlOnce(),
                    preferences.tailscaleUrlOnce(),
                )
                val remote = if (endpoint != null) {
                    runCatching { server.library(endpoint.baseUrl) }.getOrElse { emptyList() }
                } else {
                    emptyList()
                }
                core.update {
                    it.copy(
                        checking = false,
                        loadingLibrary = false,
                        endpoint = endpoint,
                        remoteTracks = remote,
                        error = if (endpoint == null) {
                            "Сервер недоступен. Локальные треки всё равно можно обрезать."
                        } else {
                            null
                        },
                    )
                }
            } catch (e: Exception) {
                core.update {
                    it.copy(
                        checking = false,
                        loadingLibrary = false,
                        error = e.message ?: "Не удалось загрузить список с сервера",
                    )
                }
            }
        }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun selectLocalTrack(track: Track) {
        val durationMs = track.durationMs.takeIf { it > 0L } ?: readDurationMs(track.uri)
        if (durationMs <= 0L) {
            core.update { it.copy(error = "Не удалось определить длительность трека") }
            return
        }
        val durationSec = durationMs / 1000f
        core.update {
            it.copy(
                fileUri = track.uri,
                fileName = track.displayName,
                durationMs = durationMs,
                startSec = 0f,
                endSec = durationSec,
                currentSec = 0f,
                playing = false,
                waveformPeaks = FloatArray(0),
                loadingWaveform = true,
                message = "Выбран: ${track.title}",
                error = null,
            )
        }
        loadWaveform(track.uri)
    }

    fun selectRemoteTrack(file: RemoteFile) {
        val endpoint = core.value.endpoint ?: run {
            core.update { it.copy(error = "Сервер недоступен") }
            return
        }
        viewModelScope.launch {
            core.update { it.copy(busy = true, error = null, message = "Загружаю ${file.path}…") }
            try {
                val uri = downloadRemoteToCache(endpoint, file)
                val durationMs = readDurationMs(uri)
                if (durationMs <= 0L) error("Не удалось определить длительность файла")
                val durationSec = durationMs / 1000f
                val name = file.path.substringAfterLast('/')
                core.update {
                    it.copy(
                        busy = false,
                        fileUri = uri,
                        fileName = name,
                        durationMs = durationMs,
                        startSec = 0f,
                        endSec = durationSec,
                        currentSec = 0f,
                        playing = false,
                        waveformPeaks = FloatArray(0),
                        loadingWaveform = true,
                        message = "Выбран с сервера: $name",
                        error = null,
                    )
                }
                loadWaveform(uri)
            } catch (e: Exception) {
                core.update {
                    it.copy(
                        busy = false,
                        error = e.message ?: "Не удалось загрузить файл с сервера",
                    )
                }
            }
        }
    }

    fun setStartSec(value: Float) {
        core.update { current ->
            val maxEnd = current.endSec - 0.05f
            current.copy(startSec = value.coerceIn(0f, maxEnd.coerceAtLeast(0f)))
        }
    }

    fun setEndSec(value: Float) {
        core.update { current ->
            val durationSec = current.durationMs / 1000f
            val minEnd = current.startSec + 0.05f
            current.copy(endSec = value.coerceIn(minEnd, durationSec.coerceAtLeast(minEnd)))
        }
    }

    fun setUploadDir(value: String) {
        core.update { it.copy(uploadDir = value) }
    }

    fun setCurrentSec(value: Float) {
        core.update { it.copy(currentSec = value.coerceAtLeast(0f)) }
    }

    fun setPlaying(value: Boolean) {
        core.update { it.copy(playing = value) }
    }

    fun resetRange() {
        core.update { current ->
            val durationSec = current.durationMs / 1000f
            current.copy(startSec = 0f, endSec = durationSec, currentSec = 0f)
        }
    }

    fun clearSelection() {
        waveformJob?.cancel()
        core.update {
            it.copy(
                fileUri = null,
                fileName = null,
                durationMs = 0L,
                startSec = 0f,
                endSec = 0f,
                currentSec = 0f,
                playing = false,
                waveformPeaks = FloatArray(0),
                loadingWaveform = false,
            )
        }
    }

    private fun loadWaveform(uri: Uri) {
        waveformJob?.cancel()
        waveformJob = viewModelScope.launch {
            val peaks = WaveformPeaks.load(getApplication(), uri)
            core.update {
                if (it.fileUri == uri) it.copy(waveformPeaks = peaks, loadingWaveform = false) else it
            }
        }
    }

    fun trimAndUpload() {
        val current = core.value
        if (current.busy) return
        val uri = current.fileUri ?: return
        val endpoint = current.endpoint ?: run {
            core.update { it.copy(error = "Сервер недоступен") }
            return
        }
        if (current.endSec <= current.startSec) {
            core.update { it.copy(error = "Неверный диапазон обрезки") }
            return
        }

        viewModelScope.launch {
            core.update { it.copy(busy = true, error = null, message = "Обрезаю и загружаю…") }
            try {
                val context = getApplication<Application>()
                val wav = AudioTrimmer.trimToWav(
                    context = context,
                    uri = uri,
                    startSec = current.startSec,
                    endSec = current.endSec,
                )
                val baseName = current.fileName
                    ?.substringBeforeLast('.')
                    ?.ifBlank { "track" }
                    ?: "track"
                val uploadName = "${baseName}.trim.${current.startSec.toInt()}-${current.endSec.toInt()}.wav"
                val response = server.upload(
                    baseUrl = endpoint.baseUrl,
                    relativeDir = current.uploadDir,
                    fileName = uploadName,
                    bytes = wav,
                    mimeType = AudioFormats.mimeForFileName(uploadName),
                )
                val uploaded = response.uploaded.firstOrNull()?.name ?: uploadName
                core.update {
                    it.copy(
                        busy = false,
                        message = "Готово: $uploaded (${response.libraryCount} треков на сервере)",
                    )
                }
            } catch (e: Exception) {
                core.update {
                    it.copy(
                        busy = false,
                        error = e.message ?: "Не удалось обрезать или загрузить файл",
                    )
                }
            }
        }
    }

    private suspend fun downloadRemoteToCache(endpoint: ServerEndpoint, file: RemoteFile): Uri =
        withContext(Dispatchers.IO) {
            val context = getApplication<Application>()
            val key = "${endpoint.baseUrl}|${file.path}|${file.mtime}|${file.size}"
            val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val safeName = "$hash.${file.path.substringAfterLast('.', "audio")}"
            val cache = File(context.cacheDir, "trim_cache/$safeName")
            cache.parentFile?.mkdirs()
            if (!cache.exists() || cache.length() != file.size) {
                server.downloadCall(endpoint.baseUrl, file.path, 0L).execute().use { response ->
                    if (!response.isSuccessful) error("Не удалось скачать файл: ${response.code}")
                    val body = response.body ?: error("Пустой ответ сервера")
                    val partial = File(cache.parentFile, "${cache.name}.part")
                    try {
                        partial.outputStream().use { out -> body.byteStream().copyTo(out) }
                        check(file.size <= 0 || partial.length() == file.size) { "Файл скачан не полностью" }
                        check(partial.renameTo(cache)) { "Не удалось сохранить скачанный файл" }
                    } finally {
                        partial.delete()
                    }
                }
            }
            cache.toUri()
        }

    private fun readDurationMs(uri: Uri): Long {
        return try {
            val name = uri.lastPathSegment ?: "track.m4a"
            TrackMetadataReader.read(getApplication(), uri, name).durationMs
        } catch (_: Exception) {
            0L
        }
    }

    private fun filterTracks(tracks: List<Track>, query: String): List<Track> {
        val needle = query.trim()
        if (needle.isEmpty()) return tracks
        return tracks.filter { track ->
            track.title.contains(needle, true) ||
                track.artist.contains(needle, true) ||
                track.album.contains(needle, true) ||
                track.displayName.contains(needle, true)
        }
    }

    private fun filterRemote(files: List<RemoteFile>, query: String): List<RemoteFile> {
        val needle = query.trim()
        if (needle.isEmpty()) return files
        return files.filter { it.path.contains(needle, true) }
    }
}

fun ConnectionChannel.trimLabel(): String = when (this) {
    ConnectionChannel.LAN -> "LAN"
    ConnectionChannel.TAILSCALE -> "Tailscale"
    ConnectionChannel.NONE -> "—"
}
