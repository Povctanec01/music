package com.example.music.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.music.MusicApplication
import com.example.music.domain.model.GroupMode
import com.example.music.domain.model.LibraryRow
import com.example.music.domain.model.SortMode
import com.example.music.domain.model.Track
import com.example.music.domain.recommendation.RecommendationSection
import com.example.music.domain.recommendation.TrackBehavior
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LibraryBrowseMode { HOME, LIST }

data class LibraryUiState(
    val folderSelected: Boolean = false,
    val isScanning: Boolean = false,
    val error: String? = null,
    val query: String = "",
    val browseMode: LibraryBrowseMode = LibraryBrowseMode.LIST,
    val sortMode: SortMode = SortMode.ARTIST,
    val groupMode: GroupMode = GroupMode.TRACKS,
    val groupOrder: List<GroupMode> = GroupMode.entries.toList(),
    val favorites: Set<String> = emptySet(),
    val recommendationSections: List<RecommendationSection> = emptyList(),
    val rows: List<LibraryRow> = emptyList(),
    val visibleTracks: List<Track> = emptyList(),
    val totalCount: Int = 0,
)

private data class LibraryCore(
    val tracks: List<Track>,
    val isScanning: Boolean,
    val error: String?,
    val folderUri: String?,
    val favorites: Set<String>,
    val listeningBehavior: Map<String, TrackBehavior>,
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as MusicApplication).container
    private val library = container.libraryRepository
    private val preferences = container.preferences
    private val recommendationEngine = container.recommendationEngine
    val playback = container.playbackController

    private val query = MutableStateFlow("")
    private val browseMode = MutableStateFlow(LibraryBrowseMode.LIST)
    private val sortMode = MutableStateFlow(SortMode.ARTIST)
    private val groupMode = MutableStateFlow(GroupMode.TRACKS)
    private val groupOrder = MutableStateFlow(GroupMode.entries.toList())
    private var libraryTracks: List<Track> = emptyList()

    private val core = combine(
        combine(
            library.tracks,
            library.isScanning,
            library.error,
            preferences.folderUri,
            preferences.favorites,
        ) { tracks, isScanning, error, folderUri, favorites ->
            LibraryCore(
                tracks = tracks,
                isScanning = isScanning,
                error = error,
                folderUri = folderUri,
                favorites = favorites,
                listeningBehavior = emptyMap(),
            )
        },
        preferences.listeningBehavior,
    ) { base, listeningBehavior ->
        base.copy(listeningBehavior = listeningBehavior)
    }

    private val uiStateBase =
        combine(core, query, browseMode, sortMode, groupMode) { core: LibraryCore, q: String, mode: LibraryBrowseMode, sort: SortMode, group: GroupMode ->
            val filtered = filterAndSort(core.tracks, q, sort, group, core.favorites)
            LibraryUiState(
                folderSelected = !core.folderUri.isNullOrBlank(),
                isScanning = core.isScanning,
                error = core.error,
                query = q,
                browseMode = mode,
                sortMode = sort,
                groupMode = group,
                favorites = core.favorites,
                recommendationSections = if (q.isBlank()) {
                    recommendationEngine.buildSections(core.tracks, core.listeningBehavior, core.favorites)
                } else {
                    emptyList()
                },
                rows = buildRows(filtered, group),
                visibleTracks = filtered,
                totalCount = core.tracks.size,
            )
        }

    val uiState: StateFlow<LibraryUiState> =
        combine(uiStateBase, groupOrder) { base, order ->
            base.copy(groupOrder = order)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    init {
        viewModelScope.launch {
            library.tracks.collect { tracks ->
                libraryTracks = tracks
                if (playback.state.value.shuffle && tracks.isNotEmpty()) {
                    playback.setShufflePool(tracks)
                }
                playback.restoreIfNeeded(tracks)
            }
        }
        viewModelScope.launch {
            var wasShuffle = false
            playback.state.collect { state ->
                if (state.shuffle && !wasShuffle && libraryTracks.isNotEmpty()) {
                    playback.setShufflePool(libraryTracks)
                }
                wasShuffle = state.shuffle
            }
        }
        viewModelScope.launch {
            // Restore previously selected sorting mode.
            sortMode.value = preferences.sortModeOnce()
        }
        viewModelScope.launch {
            preferences.groupModeOrder.collect { order ->
                groupOrder.value = order
            }
        }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun setBrowseMode(mode: LibraryBrowseMode) {
        browseMode.value = mode
    }

    fun setSort(mode: SortMode) {
        sortMode.value = mode
        viewModelScope.launch { preferences.setSortMode(mode) }
    }

    fun setGroup(mode: GroupMode) {
        groupMode.value = mode
    }

    fun swapGroupOrder(a: GroupMode, b: GroupMode) {
        if (a == b) return
        val current = groupOrder.value.toMutableList()
        val i = current.indexOf(a)
        val j = current.indexOf(b)
        if (i == -1 || j == -1) return
        val tmp = current[i]
        current[i] = current[j]
        current[j] = tmp
        groupOrder.value = current
        viewModelScope.launch { preferences.setGroupModeOrder(current) }
    }

    fun refresh() {
        viewModelScope.launch { library.scan() }
    }

    fun selectFolder(uri: Uri) {
        viewModelScope.launch { library.setFolder(uri) }
    }

    fun play(track: Track, queue: List<Track>) {
        val pool = if (playback.state.value.shuffle && libraryTracks.size > 1) {
            libraryTracks
        } else {
            queue
        }
        var index = pool.indexOfFirst { it.id == track.id }
        val finalPool = if (index < 0) {
            index = 0
            listOf(track) + pool.filter { it.id != track.id }
        } else {
            pool
        }
        playback.play(finalPool, index)
    }

    fun playNext(track: Track) {
        playback.playNext(track)
    }

    fun toggleFavorite(trackId: String) {
        viewModelScope.launch { preferences.toggleFavorite(trackId) }
    }

    fun deleteTrack(track: Track, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val deleted = runCatching { library.deleteTrack(track) }.getOrDefault(false)
            onResult(deleted)
        }
    }

    private fun filterAndSort(
        tracks: List<Track>,
        q: String,
        sort: SortMode,
        group: GroupMode,
        favorites: Set<String>,
    ): List<Track> {
        val needle = q.trim()
        var result = tracks
        if (group == GroupMode.FAVORITES) {
            result = result.filter { it.id in favorites }
        }
        if (needle.isNotEmpty()) {
            result = result.filter { track ->
                track.title.contains(needle, true) ||
                    track.artist.contains(needle, true) ||
                    track.album.contains(needle, true) ||
                    track.displayName.contains(needle, true)
            }
        }
        return when (sort) {
            SortMode.TITLE -> result.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            SortMode.ARTIST -> result.sortedWith(
                compareBy<Track, String>(String.CASE_INSENSITIVE_ORDER) { it.artistOrUnknown }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.albumOrUnknown }
                    .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
            )
            SortMode.DATE_ADDED -> result.sortedByDescending { it.addedAt }
            SortMode.DURATION -> result.sortedByDescending { it.durationMs }
        }
    }

    private fun buildRows(tracks: List<Track>, group: GroupMode): List<LibraryRow> {
        if (tracks.isEmpty()) return emptyList()
        return when (group) {
            GroupMode.TRACKS, GroupMode.FAVORITES -> tracks.map { LibraryRow.Song(it) }
            GroupMode.ARTISTS -> grouped(tracks) { it.artistOrUnknown }
            GroupMode.ALBUMS -> grouped(tracks) { it.albumOrUnknown }
            GroupMode.FOLDERS -> grouped(tracks) { it.folder }
        }
    }

    private fun grouped(tracks: List<Track>, key: (Track) -> String): List<LibraryRow> {
        val rows = mutableListOf<LibraryRow>()
        tracks.groupBy(key).toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (header, songs) ->
            rows += LibraryRow.Header(header)
            songs.forEach { rows += LibraryRow.Song(it) }
        }
        return rows
    }
}
