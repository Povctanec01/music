package com.example.music.data.preferences

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.music.domain.model.DownloadNetworkMode
import com.example.music.domain.model.GroupMode
import com.example.music.domain.model.SortMode
import com.example.music.domain.model.Track
import com.example.music.domain.recommendation.TrackBehavior
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "music_prefs")

class AppPreferences(
    context: Context,
    private val json: Json,
) {
    private val dataStore = context.dataStore

    val folderUri: Flow<String?> = dataStore.data.map { it[Keys.FOLDER_URI] }
    val lanUrl: Flow<String> = dataStore.data.map {
        it[Keys.LAN_URL] ?: DEFAULT_LAN_URL
    }
    val tailscaleUrl: Flow<String> = dataStore.data.map { it[Keys.TAILSCALE_URL] ?: DEFAULT_TAILSCALE_URL }
    val githubRepo: Flow<String> = dataStore.data.map { it[Keys.GITHUB_REPO] ?: DEFAULT_GITHUB_REPO }
    val downloadNetworkMode: Flow<DownloadNetworkMode> = dataStore.data.map { prefs ->
        runCatching {
            DownloadNetworkMode.valueOf(prefs[Keys.NETWORK_MODE] ?: DownloadNetworkMode.WIFI_ONLY.name)
        }.getOrDefault(DownloadNetworkMode.WIFI_ONLY)
    }
    val shuffle: Flow<Boolean> = dataStore.data.map { it[Keys.SHUFFLE] ?: false }
    val repeatMode: Flow<Int> = dataStore.data.map { it[Keys.REPEAT_MODE] ?: 0 }
    val sortMode: Flow<SortMode> = dataStore.data.map { prefs ->
        runCatching { SortMode.valueOf(prefs[Keys.SORT_MODE] ?: SortMode.ARTIST.name) }
            .getOrDefault(SortMode.ARTIST)
    }
    val groupModeOrder: Flow<List<GroupMode>> = dataStore.data.map { prefs ->
        val raw = prefs[Keys.GROUP_MODE_ORDER].orEmpty()
        if (raw.isBlank()) GroupMode.entries.toList()
        else runCatching {
            normalizeGroupModeOrder(
                json.decodeFromString<List<String>>(raw)
                    .mapNotNull { token -> runCatching { GroupMode.valueOf(token) }.getOrNull() },
            )
        }.getOrDefault(GroupMode.entries.toList())
    }
    val favorites: Flow<Set<String>> = dataStore.data.map { prefs ->
        val raw = prefs[Keys.FAVORITES].orEmpty()
        if (raw.isBlank()) emptySet()
        else runCatching { json.decodeFromString<List<String>>(raw).toSet() }.getOrDefault(emptySet())
    }
    val listeningBehavior: Flow<Map<String, TrackBehavior>> = dataStore.data.map { prefs ->
        val raw = prefs[Keys.LISTENING_BEHAVIOR].orEmpty()
        if (raw.isBlank()) emptyMap()
        else runCatching { json.decodeFromString<Map<String, TrackBehavior>>(raw) }.getOrDefault(emptyMap())
    }

    suspend fun folderUriOnce(): String? = folderUri.first()
    suspend fun lanUrlOnce(): String = lanUrl.first()
    suspend fun tailscaleUrlOnce(): String = tailscaleUrl.first()
    suspend fun githubRepoOnce(): String = githubRepo.first()
    suspend fun networkModeOnce(): DownloadNetworkMode = downloadNetworkMode.first()
    suspend fun favoritesOnce(): Set<String> = favorites.first()
    suspend fun listeningBehaviorOnce(): Map<String, TrackBehavior> = listeningBehavior.first()
    suspend fun sortModeOnce(): SortMode = sortMode.first()
    suspend fun groupModeOrderOnce(): List<GroupMode> = groupModeOrder.first()

    suspend fun setFolderUri(uri: String) {
        dataStore.edit { it[Keys.FOLDER_URI] = uri }
    }

    suspend fun setLanUrl(url: String) {
        dataStore.edit { it[Keys.LAN_URL] = url.trim() }
    }

    suspend fun setTailscaleUrl(url: String) {
        dataStore.edit { it[Keys.TAILSCALE_URL] = url.trim() }
    }

    suspend fun setGithubRepo(repo: String) {
        dataStore.edit { it[Keys.GITHUB_REPO] = repo.trim() }
    }

    suspend fun setNetworkMode(mode: DownloadNetworkMode) {
        dataStore.edit { it[Keys.NETWORK_MODE] = mode.name }
    }

    suspend fun setShuffle(enabled: Boolean) {
        dataStore.edit { it[Keys.SHUFFLE] = enabled }
    }

    suspend fun setRepeatMode(mode: Int) {
        dataStore.edit { it[Keys.REPEAT_MODE] = mode }
    }

    suspend fun setSortMode(mode: SortMode) {
        dataStore.edit { it[Keys.SORT_MODE] = mode.name }
    }

    suspend fun setGroupModeOrder(order: List<GroupMode>) {
        val raw = json.encodeToString(normalizeGroupModeOrder(order).map { it.name })
        dataStore.edit { prefs ->
            prefs[Keys.GROUP_MODE_ORDER] = raw
        }
    }

    suspend fun toggleFavorite(trackId: String): Boolean {
        var nowFavorite = false
        dataStore.edit { prefs ->
            val current = prefs[Keys.FAVORITES].orEmpty()
                .let { raw ->
                    if (raw.isBlank()) mutableSetOf()
                    else runCatching { json.decodeFromString<List<String>>(raw).toMutableSet() }
                        .getOrDefault(mutableSetOf())
                }
            nowFavorite = if (trackId in current) {
                current.remove(trackId)
                false
            } else {
                current.add(trackId)
                true
            }
            prefs[Keys.FAVORITES] = json.encodeToString(current.toList())
        }
        return nowFavorite
    }

    suspend fun recordTrackStart(trackId: String, playedAt: Long = System.currentTimeMillis()) {
        mutateTrackBehavior(trackId) { current ->
            current.copy(
                playCount = current.playCount + 1,
                lastPlayedAt = playedAt,
            )
        }
    }

    suspend fun addListenedTime(trackId: String, listenedMs: Long) {
        if (listenedMs <= 0L) return
        mutateTrackBehavior(trackId) { current ->
            current.copy(totalListenedMs = current.totalListenedMs + listenedMs)
        }
    }

    suspend fun recordCompletion(trackId: String) {
        mutateTrackBehavior(trackId) { current ->
            current.copy(completionCount = current.completionCount + 1)
        }
    }

    suspend fun recordSkip(trackId: String) {
        mutateTrackBehavior(trackId) { current ->
            current.copy(skipCount = current.skipCount + 1)
        }
    }

    suspend fun savePlayback(queueIds: List<String>, index: Int, positionMs: Long, mediaId: String?) {
        dataStore.edit {
            it[Keys.QUEUE] = json.encodeToString(queueIds)
            it[Keys.QUEUE_INDEX] = index
            it[Keys.POSITION_MS] = positionMs
            if (mediaId != null) it[Keys.LAST_MEDIA_ID] = mediaId
            else it.remove(Keys.LAST_MEDIA_ID)
        }
    }

    suspend fun playbackSnapshot(): PlaybackSnapshot {
        val prefs = dataStore.data.first()
        val queue = prefs[Keys.QUEUE].orEmpty().let { raw ->
            if (raw.isBlank()) emptyList()
            else runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
        }
        return PlaybackSnapshot(
            queueIds = queue,
            index = prefs[Keys.QUEUE_INDEX] ?: 0,
            positionMs = prefs[Keys.POSITION_MS] ?: 0L,
            mediaId = prefs[Keys.LAST_MEDIA_ID],
            shuffle = prefs[Keys.SHUFFLE] ?: false,
            repeatMode = prefs[Keys.REPEAT_MODE] ?: 0,
        )
    }

    suspend fun cachedTracks(folderUri: String): List<Track> {
        val raw = dataStore.data.first()[Keys.CACHED_LIBRARY].orEmpty()
        if (raw.isBlank()) return emptyList()
        val snapshot = runCatching { json.decodeFromString<CachedLibrarySnapshot>(raw) }.getOrNull() ?: return emptyList()
        if (snapshot.folderUri != folderUri) return emptyList()
        return snapshot.tracks.map { it.toTrack() }
    }

    suspend fun saveCachedTracks(folderUri: String, tracks: List<Track>) {
        val snapshot = CachedLibrarySnapshot(
            folderUri = folderUri,
            tracks = tracks.map(CachedTrack::fromTrack),
        )
        dataStore.edit { it[Keys.CACHED_LIBRARY] = json.encodeToString(snapshot) }
    }

    val pendingUpdateRestart: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.PENDING_UPDATE_RESTART] ?: false
    }

    suspend fun pendingUpdateRestartOnce(): Boolean = pendingUpdateRestart.first()

    suspend fun setPendingUpdateRestart(targetVersionCode: Int) {
        dataStore.edit { prefs ->
            prefs[Keys.PENDING_UPDATE_RESTART] = true
            prefs[Keys.PENDING_UPDATE_TARGET_VERSION_CODE] = targetVersionCode
        }
    }

    suspend fun clearPendingUpdateRestart() {
        dataStore.edit { prefs ->
            prefs[Keys.PENDING_UPDATE_RESTART] = false
            prefs.remove(Keys.PENDING_UPDATE_TARGET_VERSION_CODE)
        }
    }

    suspend fun sidecarArtRefreshVersionOnce(): Int =
        dataStore.data.first()[Keys.SIDECAR_ART_REFRESH_VERSION] ?: 0

    suspend fun setSidecarArtRefreshVersion(version: Int) {
        dataStore.edit { prefs ->
            prefs[Keys.SIDECAR_ART_REFRESH_VERSION] = version
        }
    }

    private suspend fun mutateTrackBehavior(
        trackId: String,
        update: (TrackBehavior) -> TrackBehavior,
    ) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.LISTENING_BEHAVIOR].orEmpty()
            val map = if (current.isBlank()) {
                mutableMapOf()
            } else {
                runCatching { json.decodeFromString<Map<String, TrackBehavior>>(current).toMutableMap() }
                    .getOrDefault(mutableMapOf())
            }
            map[trackId] = update(map[trackId] ?: TrackBehavior())
            prefs[Keys.LISTENING_BEHAVIOR] = json.encodeToString(map)
        }
    }

    private object Keys {
        val FOLDER_URI = stringPreferencesKey("folder_uri")
        val LAN_URL = stringPreferencesKey("lan_url")
        val TAILSCALE_URL = stringPreferencesKey("tailscale_url")
        val GITHUB_REPO = stringPreferencesKey("github_repo")
        val NETWORK_MODE = stringPreferencesKey("network_mode")
        val SHUFFLE = booleanPreferencesKey("shuffle")
        val REPEAT_MODE = intPreferencesKey("repeat_mode")
        val SORT_MODE = stringPreferencesKey("sort_mode")
        val GROUP_MODE_ORDER = stringPreferencesKey("group_mode_order")
        val FAVORITES = stringPreferencesKey("favorites")
        val QUEUE = stringPreferencesKey("queue")
        val QUEUE_INDEX = intPreferencesKey("queue_index")
        val POSITION_MS = longPreferencesKey("position_ms")
        val LAST_MEDIA_ID = stringPreferencesKey("last_media_id")
        val LISTENING_BEHAVIOR = stringPreferencesKey("listening_behavior")
        val CACHED_LIBRARY = stringPreferencesKey("cached_library")
        val PENDING_UPDATE_RESTART = booleanPreferencesKey("pending_update_restart")
        val PENDING_UPDATE_TARGET_VERSION_CODE = intPreferencesKey("pending_update_target_version_code")
        val SIDECAR_ART_REFRESH_VERSION = intPreferencesKey("sidecar_art_refresh_version")
    }

    companion object {
        const val DEFAULT_LAN_URL = "http://192.168.1.80:8080"
        const val DEFAULT_TAILSCALE_URL = "http://100.106.26.26:8080"
        const val DEFAULT_GITHUB_REPO = "Povctanec01/music"
        /** One-time forced sidecar refresh for existing local library tracks. */
        const val CURRENT_SIDECAR_ART_REFRESH_VERSION = 2
        const val FORCE_SIDECAR_REFRESH_TRACK_COUNT = 14

        fun normalizeGroupModeOrder(order: List<GroupMode>): List<GroupMode> {
            val seen = linkedSetOf<GroupMode>()
            order.forEach { mode -> seen.add(mode) }
            GroupMode.entries.forEach { mode -> seen.add(mode) }
            return seen.toList()
        }
    }
}

data class PlaybackSnapshot(
    val queueIds: List<String>,
    val index: Int,
    val positionMs: Long,
    val mediaId: String?,
    val shuffle: Boolean,
    val repeatMode: Int,
)

@kotlinx.serialization.Serializable
private data class CachedLibrarySnapshot(
    val folderUri: String,
    val tracks: List<CachedTrack>,
)

@kotlinx.serialization.Serializable
private data class CachedTrack(
    val id: String,
    val uri: String,
    val displayName: String,
    val relativePath: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val size: Long,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val addedAt: Long,
    val mimeType: String,
) {
    fun toTrack(): Track = Track(
        id = id,
        uri = Uri.parse(uri),
        displayName = displayName,
        relativePath = relativePath,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        size = size,
        year = year,
        trackNumber = trackNumber,
        addedAt = addedAt,
        mimeType = mimeType,
    )

    companion object {
        fun fromTrack(track: Track): CachedTrack = CachedTrack(
            id = track.id,
            uri = track.uri.toString(),
            displayName = track.displayName,
            relativePath = track.relativePath,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            size = track.size,
            year = track.year,
            trackNumber = track.trackNumber,
            addedAt = track.addedAt,
            mimeType = track.mimeType,
        )
    }
}
