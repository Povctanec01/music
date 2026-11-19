package com.example.music.ui.sync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.music.MusicApplication
import com.example.music.domain.SyncComparison
import com.example.music.domain.model.ConnectionChannel
import com.example.music.domain.model.RemoteFile
import com.example.music.domain.model.ServerEndpoint
import com.example.music.domain.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SyncUiState(
    val checking: Boolean = false,
    val endpoint: ServerEndpoint? = null,
    val error: String? = null,
    val remoteCount: Int = 0,
    val newFiles: List<RemoteFile> = emptyList(),
    val selected: Set<String> = emptySet(),
    val folderSelected: Boolean = false,
)

private data class SyncCore(
    val checking: Boolean,
    val endpoint: ServerEndpoint?,
    val error: String?,
    val remote: List<RemoteFile>,
    val selected: Set<String>,
)

class SyncViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as MusicApplication).container
    private val server = container.serverClient
    private val preferences = container.preferences
    private val library = container.libraryRepository
    val downloads = container.downloadManager

    private val checking = MutableStateFlow(false)
    private val endpoint = MutableStateFlow<ServerEndpoint?>(null)
    private val error = MutableStateFlow<String?>(null)
    private val remote = MutableStateFlow<List<RemoteFile>>(emptyList())
    private val selected = MutableStateFlow<Set<String>>(emptySet())

    private val core = combine(checking, endpoint, error, remote, selected, ::SyncCore)

    val uiState: StateFlow<SyncUiState> = combine(
        core,
        library.tracks,
        preferences.folderUri,
    ) { core, tracks, folder ->
        val missing = diff(tracks, core.remote)
        SyncUiState(
            checking = core.checking,
            endpoint = core.endpoint,
            error = core.error,
            remoteCount = core.remote.size,
            newFiles = missing,
            selected = core.selected,
            folderSelected = !folder.isNullOrBlank(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncUiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            checking.value = true
            error.value = null
            try {
                val lan = preferences.lanUrlOnce()
                val tailscale = preferences.tailscaleUrlOnce()
                val found = server.pickEndpoint(lan, tailscale)
                endpoint.value = found
                if (found == null) {
                    remote.value = emptyList()
                    selected.value = emptySet()
                    error.value = "Сервер недоступен. Проверьте Wi‑Fi или Tailscale."
                } else {
                    val files = server.library(found.baseUrl)
                    remote.value = files
                    selected.value = diff(library.tracks.value, files).map { it.path }.toSet()
                }
            } catch (e: Exception) {
                error.value = e.message ?: "Не удалось получить список с сервера"
                remote.value = emptyList()
            } finally {
                checking.value = false
            }
        }
    }

    fun toggle(path: String) {
        selected.value = selected.value.toMutableSet().also { set ->
            if (path in set) set.remove(path) else set.add(path)
        }
    }

    fun selectAll(files: List<RemoteFile>) {
        selected.value = files.map { it.path }.toSet()
    }

    fun selectNone() {
        selected.value = emptySet()
    }

    fun downloadSelected() {
        val state = uiState.value
        val files = state.newFiles.filter { it.path in state.selected }
        if (files.isEmpty()) return
        downloads.enqueue(files)
    }

    fun pause() = downloads.pause()
    fun resume() = downloads.resume()
    fun cancel() = downloads.cancel()

    private fun diff(local: List<Track>, remoteFiles: List<RemoteFile>): List<RemoteFile> {
        val byPath = local.associateBy { it.relativePath.lowercase() }
        return remoteFiles.filter { remoteFile ->
            val localTrack = byPath[remoteFile.path.lowercase()]
            SyncComparison.needsDownload(localTrack?.size, localTrack?.sha256, remoteFile.size, remoteFile.sha256)
        }
    }
}

fun ConnectionChannel.label(): String = when (this) {
    ConnectionChannel.LAN -> "LAN (Wi‑Fi)"
    ConnectionChannel.TAILSCALE -> "Tailscale"
    ConnectionChannel.NONE -> "Нет связи"
}
