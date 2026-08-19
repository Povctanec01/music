package com.example.music.data.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.music.data.local.SafStorage
import com.example.music.data.preferences.AppPreferences
import com.example.music.data.remote.NetworkMonitor
import com.example.music.data.remote.ServerClient
import com.example.music.domain.model.ConnectionChannel
import com.example.music.domain.model.DownloadStatus
import com.example.music.domain.model.DownloadTask
import com.example.music.domain.model.RemoteFile
import com.example.music.domain.model.ServerEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileOutputStream
import java.io.IOException

class DownloadManager(
    private val context: Context,
    private val preferences: AppPreferences,
    private val server: ServerClient,
    private val storage: SafStorage,
    private val network: NetworkMonitor,
    private val onFinished: suspend () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _endpoint = MutableStateFlow<ServerEndpoint?>(null)
    val endpoint: StateFlow<ServerEndpoint?> = _endpoint.asStateFlow()

    private var job: Job? = null

    fun enqueue(files: List<RemoteFile>) {
        _tasks.value = files.map { DownloadTask(path = it.path, size = it.size) }
        resume()
    }

    fun pause() {
        _paused.value = true
        _statusMessage.value = "Загрузка на паузе"
    }

    fun resume() {
        _paused.value = false
        if (job?.isActive == true) return
        job = scope.launch { runQueue() }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _running.value = false
        _paused.value = false
        _tasks.value = emptyList()
        _statusMessage.value = "Загрузка отменена"
        context.stopService(Intent(context, MusicDownloadService::class.java))
    }

    private suspend fun runQueue() {
        val folder = preferences.folderUriOnce()?.let(Uri::parse)
        if (folder == null) {
            _statusMessage.value = "Сначала выберите папку для музыки"
            return
        }
        val endpoint = server.pickEndpoint(preferences.lanUrlOnce(), preferences.tailscaleUrlOnce())
        _endpoint.value = endpoint
        if (endpoint == null) {
            _statusMessage.value = "Сервер недоступен по LAN и Tailscale"
            return
        }
        val mode = preferences.networkModeOnce()
        if (!network.canDownload(mode, endpoint.channel)) {
            _statusMessage.value = "Загрузка запрещена текущей сетью. Разрешите Tailscale или любые сети в настройках."
            return
        }

        startForegroundService()
        _running.value = true
        _statusMessage.value = channelLabel(endpoint.channel)

        try {
            while (true) {
                if (_paused.value) {
                    delay(400)
                    continue
                }
                val next = mutex.withLock {
                    val list = _tasks.value.toMutableList()
                    val index = list.indexOfFirst { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.PAUSED }
                    if (index < 0) null
                    else {
                        list[index] = list[index].copy(status = DownloadStatus.RUNNING, error = null)
                        _tasks.value = list
                        list[index]
                    }
                } ?: break

                if (!network.canDownload(preferences.networkModeOnce(), endpoint.channel)) {
                    markPaused(next.path, "Сеть недоступна для загрузки")
                    _paused.value = true
                    _statusMessage.value = "Загрузка приостановлена: нет подходящей сети"
                    continue
                }

                try {
                    downloadOne(folder, endpoint, next)
                } catch (_: PausedException) {
                    // already marked as paused
                } catch (e: Exception) {
                    if (e is IOException && (e.message?.contains("timeout", true) == true ||
                            e.message?.contains("Failed to connect", true) == true ||
                            e.message?.contains("Unable to resolve", true) == true)
                    ) {
                        markPaused(next.path, e.message)
                        _paused.value = true
                        _statusMessage.value = "Связь с сервером пропала. Загрузка на паузе."
                    } else {
                        updateTask(next.path) { it.copy(status = DownloadStatus.ERROR, error = e.message) }
                    }
                }
            }
            val hasErrors = _tasks.value.any { it.status == DownloadStatus.ERROR }
            _statusMessage.value = if (hasErrors) "Готово, есть ошибки" else "Все выбранные треки скачаны"
            onFinished()
        } finally {
            _running.value = false
            context.stopService(Intent(context, MusicDownloadService::class.java))
        }
    }

    private suspend fun downloadOne(folder: Uri, endpoint: ServerEndpoint, task: DownloadTask) {
        val file = storage.createOrOpenFile(folder, task.path)
        var existing = file.length().coerceAtLeast(0L)
        if (existing > task.size && task.size > 0) {
            existing = 0L
        }
        if (task.size > 0 && existing == task.size) {
            updateTask(task.path) {
                it.copy(bytesDownloaded = existing, status = DownloadStatus.DONE)
            }
            return
        }

        val call = server.downloadCall(endpoint.baseUrl, task.path, existing)
        call.execute().use { response ->
            if (response.code == 416) {
                updateTask(task.path) { it.copy(bytesDownloaded = task.size, status = DownloadStatus.DONE) }
                return
            }
            if (!response.isSuccessful && response.code != 206) {
                throw IOException("HTTP ${response.code} для ${task.path}")
            }
            val body = response.body ?: throw IOException("Пустой ответ")
            val pfd = context.contentResolver.openFileDescriptor(
                file.uri,
                if (existing > 0 && response.code == 206) "rw" else "rwt",
            ) ?: throw IOException("Не удалось открыть файл для записи")
            pfd.use { descriptor ->
                FileOutputStream(descriptor.fileDescriptor).use { output ->
                    if (existing > 0 && response.code == 206) {
                        output.channel.position(existing)
                    }
                    val input = body.byteStream()
                    val buffer = ByteArray(DEFAULT_BUFFER)
                    var downloaded = existing
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        if (_paused.value) {
                            output.flush()
                            markPaused(task.path, null, downloaded)
                            throw PausedException()
                        }
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded % (DEFAULT_BUFFER * 8) < DEFAULT_BUFFER) {
                            updateTask(task.path) {
                                it.copy(bytesDownloaded = downloaded, status = DownloadStatus.RUNNING)
                            }
                        }
                    }
                    output.flush()
                    updateTask(task.path) {
                        it.copy(bytesDownloaded = downloaded, status = DownloadStatus.DONE)
                    }
                }
            }
        }
    }

    private fun startForegroundService() {
        val intent = Intent(context, MusicDownloadService::class.java)
        context.startForegroundService(intent)
    }

    private fun markPaused(path: String, error: String?, bytes: Long? = null) {
        updateTask(path) {
            it.copy(
                status = DownloadStatus.PAUSED,
                error = error,
                bytesDownloaded = bytes ?: it.bytesDownloaded,
            )
        }
    }

    private fun updateTask(path: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.value = _tasks.value.map { if (it.path == path) transform(it) else it }
    }

    private fun channelLabel(channel: ConnectionChannel): String = when (channel) {
        ConnectionChannel.LAN -> "Загрузка по Wi‑Fi (LAN)"
        ConnectionChannel.TAILSCALE -> "Загрузка через Tailscale"
        ConnectionChannel.NONE -> "Сервер недоступен"
    }

    private class PausedException : IOException("paused")

    companion object {
        private const val DEFAULT_BUFFER = 64 * 1024
    }
}
