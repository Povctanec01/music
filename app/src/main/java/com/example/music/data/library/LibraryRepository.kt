package com.example.music.data.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.music.data.local.MusicScanner
import com.example.music.data.preferences.AppPreferences
import com.example.music.domain.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryRepository(
    private val context: Context,
    private val preferences: AppPreferences,
    private val scanner: MusicScanner,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        scope.launch {
            preferences.folderUri.collectLatest { uriString ->
                if (!uriString.isNullOrBlank()) {
                    scan(Uri.parse(uriString))
                } else {
                    _tracks.value = emptyList()
                }
            }
        }
    }

    suspend fun setFolder(uri: Uri) {
        val resolver = context.contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        preferences.setFolderUri(uri.toString())
    }

    suspend fun scan(uri: Uri? = null) {
        val folder = uri ?: preferences.folderUriOnce()?.let(Uri::parse) ?: return
        _isScanning.value = true
        _error.value = null
        try {
            val result = withContext(Dispatchers.IO) { scanner.scan(folder) }
            _tracks.value = result
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
