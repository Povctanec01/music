package com.example.music.data.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.music.data.local.SafStorage
import com.example.music.data.preferences.AppPreferences
import com.example.music.data.remote.NetworkMonitor
import com.example.music.data.remote.ServerClient
import com.example.music.domain.art.ArtLog
import com.example.music.domain.art.SidecarArtSync
import com.example.music.domain.art.ServerArtIndex
import com.example.music.domain.art.TrackArtRepository
import com.example.music.domain.model.ConnectionChannel
import com.example.music.domain.model.DownloadStatus
import com.example.music.domain.model.DownloadTask
import com.example.music.domain.model.RemoteFile
import com.example.music.domain.model.ServerEndpoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.combine
import com.example.music.data.local.DownloadSnapshot
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call

class DownloadManager(
    private val context: Context,
    private val preferences: AppPreferences,
    private val server: ServerClient,
    private val storage: SafStorage,
    private val network: NetworkMonitor,
    private val sidecarArtSync: SidecarArtSync,
    private val trackArtRepository: TrackArtRepository,
    private val onFinished: suspend () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val enqueueMutex = Mutex()

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _endpoint = MutableStateFlow<ServerEndpoint?>(null)
    val endpoint: StateFlow<ServerEndpoint?> = _endpoint.asStateFlow()

    @Volatile private var job: Job? = null
    private val activeCall = AtomicReference<Call?>(null)

    private val restored = CompletableDeferred<Unit>()
    suspend fun awaitRestored() = restored.await()
    @Volatile private var queueFolder: String? = null

    init {
        scope.launch {
            try {
                preferences.database.downloadSnapshot()?.let { snapshot ->
                    queueFolder = snapshot.folder
                    _tasks.value = snapshot.tasks.map {
                        if (it.status != DownloadStatus.DONE)
                            it.copy(status = DownloadStatus.PAUSED) else it
                    }
                    _paused.value = _tasks.value.any { it.status == DownloadStatus.PAUSED }
                    if (_paused.value) _statusMessage.value = "Очередь восстановлена. Нажмите «Продолжить»."
                }
            } catch (error: Exception) {
                _lastError.value = error.message ?: "Не удалось восстановить очередь"
            }
            combine(_tasks, _paused) { _, _ -> Unit }
                .collect {
                    try { preferences.database.saveDownloads(DownloadSnapshot(queueFolder, _tasks.value, _paused.value)) }
                    catch (error: Exception) { _lastError.value = error.message; _paused.value = true }
                    finally { if (!restored.isCompleted) restored.complete(Unit) }
                }
        }
    }

    fun enqueue(files: List<RemoteFile>) {
        scope.launch {
          enqueueMutex.withLock {
            try {
                restored.await()
                if (files.isEmpty()) return@launch
                val folder = preferences.folderUriOnce()
                val paths = _tasks.value.filter { it.status != DownloadStatus.DONE }.map { it.path } + files.map { it.path }
                if (paths.distinct().groupBy { it.lowercase() }.any { it.value.size > 1 }) {
                    _lastError.value = "Есть пути, отличающиеся только регистром. Переименуйте их на сервере перед загрузкой."
                    return@launch
                }
                if (_tasks.value.any { it.status != DownloadStatus.DONE } && queueFolder != folder) {
                    _lastError.value = "Сначала завершите очередь для предыдущей папки"
                    return@launch
                }
                // Finish the previous worker before replacing a task with a new version.
                // Otherwise its final progress/DONE update could overwrite the new task.
                val previousJob = job
                previousJob?.cancel()
                activeCall.getAndSet(null)?.cancel()
                previousJob?.cancelAndJoin()
                _tasks.update { current -> current.map {
                    if (it.status == DownloadStatus.RUNNING) it.copy(status = DownloadStatus.PAUSED) else it
                } }
                queueFolder = folder
                val additions = files.map {
                    DownloadTask(path = it.path, size = it.size, mtime = it.mtime, hasArt = it.hasArt, sha256 = it.sha256, version = it.version)
                }
                _tasks.update { current ->
                    val pending = current.filter { it.status != DownloadStatus.DONE }.associateBy { it.path }.toMutableMap()
                    additions.forEach { addition ->
                        val previous = pending[addition.path]
                        if (previous == null || previous.sha256 != addition.sha256 || previous.version != addition.version || previous.size != addition.size) {
                            pending[addition.path] = addition
                        }
                    }
                    pending.values.toList()
                }
                _lastError.value = null
                preferences.database.saveDownloads(DownloadSnapshot(queueFolder, _tasks.value, false))
                resume()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _paused.value = true
                _lastError.value = error.message ?: "Не удалось сохранить очередь"
            }
          }
        }
    }

    fun pause() {
        _paused.value = true
        _statusMessage.value = "Загрузка на паузе"
    }

    @Synchronized fun resume() {
        _paused.value = false
        _tasks.update { tasks -> tasks.map { if (it.status == DownloadStatus.ERROR) it.copy(status = DownloadStatus.QUEUED, error = null) else it } }
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                restored.await()
                runQueue()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _lastError.value = e.message
                _statusMessage.value = e.message ?: "Не удалось начать загрузку"
            }
        }
    }

    fun cancel() {
        job?.cancel()
        activeCall.getAndSet(null)?.cancel()
        job = null
        _running.value = false
        _paused.value = false
        _tasks.value = emptyList()
        _statusMessage.value = "Загрузка отменена"
        context.stopService(Intent(context, MusicDownloadService::class.java))
    }

    private suspend fun runQueue() {
        val folder = preferences.folderUriOnce()?.let(Uri::parse)
        if (queueFolder != null && folder?.toString() != queueFolder) {
            _paused.value = true
            _statusMessage.value = "Выберите исходную папку очереди или отмените загрузку"
            return
        }
        if (folder == null) {
            _paused.value = true
            _statusMessage.value = "Сначала выберите папку для музыки"
            return
        }
        if (!storage.root(folder).canWrite()) {
            _paused.value = true
            _statusMessage.value = "Нет записи в выбранную папку. Выберите папку ещё раз."
            _lastError.value = "SAF не дал write-доступ к папке назначения."
            return
        }
        val endpoint = server.pickEndpoint(preferences.lanUrlOnce(), preferences.tailscaleUrlOnce())
        _endpoint.value = endpoint
        if (endpoint == null) {
            _paused.value = true
            _statusMessage.value = "Сервер недоступен по LAN и Tailscale"
            return
        }
        val mode = preferences.networkModeOnce()
        if (!network.canDownload(mode, endpoint.channel)) {
            _paused.value = true
            _statusMessage.value = "Загрузка запрещена текущей сетью. Разрешите Tailscale или любые сети в настройках."
            return
        }

        startForegroundService()
        _running.value = true
        try {
            ArtLog.clearUi()
            refreshArtContext(endpoint)
            _statusMessage.value = channelLabel(endpoint.channel)
            ArtLog.d("download.queue.start", "tasks=${_tasks.value.size}")

            while (true) {
                currentCoroutineContext().ensureActive()
                val queueJob = currentCoroutineContext()[Job]
                if (_paused.value) {
                    delay(400)
                    continue
                }
                val next = mutex.withLock {
                    var selected: DownloadTask? = null
                    _tasks.update { list ->
                        val index = list.indexOfFirst { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.PAUSED }
                        if (index < 0 || job !== queueJob) {
                            selected = null
                            list
                        } else {
                            selected = list[index].copy(status = DownloadStatus.RUNNING, error = null)
                            list.mapIndexed { position, task -> if (position == index) selected!! else task }
                        }
                    }
                    selected
                } ?: break

                if (!network.canDownload(preferences.networkModeOnce(), endpoint.channel)) {
                    markPaused(next.path, "Сеть недоступна для загрузки")
                    _paused.value = true
                    _statusMessage.value = "Загрузка приостановлена: нет подходящей сети"
                    continue
                }

                try {
                    ArtLog.d("download.audio.start", next.path)
                    downloadOne(folder, endpoint, next)
                    ArtLog.d("download.audio.done", next.path)
                } catch (_: PausedException) {
                    // already marked as paused
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (_paused.value) { markPaused(next.path, null); continue }
                    if (e is IOException && (e.message?.contains("timeout", true) == true ||
                            e.message?.contains("Failed to connect", true) == true ||
                            e.message?.contains("Unable to resolve", true) == true)
                    ) {
                        markPaused(next.path, e.message)
                        _paused.value = true
                        _statusMessage.value = "Связь с сервером пропала. Загрузка на паузе."
                        _lastError.value = e.message
                    } else {
                        updateTask(next.path) { it.copy(status = DownloadStatus.ERROR, error = e.message) }
                        val fileName = next.path.substringAfterLast('/')
                        val reason = e.message ?: "Неизвестная ошибка"
                        _lastError.value = "$fileName: $reason"
                    }
                }
            }
            val hasErrors = _tasks.value.any { it.status == DownloadStatus.ERROR }
            if (hasErrors) _paused.value = true
            _statusMessage.value = if (hasErrors) {
                _lastError.value?.let { "Готово, есть ошибки. $it" } ?: "Готово, есть ошибки"
            } else {
                "Все выбранные треки скачаны"
            }
            onFinished()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _lastError.value = e.message
            _statusMessage.value = e.message ?: "Не удалось выполнить загрузку"
        } finally {
            if (job === currentCoroutineContext()[Job]) {
                _running.value = false
                context.stopService(Intent(context, MusicDownloadService::class.java))
            }
        }
    }

    private suspend fun downloadOne(folder: Uri, endpoint: ServerEndpoint, task: DownloadTask) {
        currentCoroutineContext().ensureActive()
        val staged = DownloadFiles.stagedFile(
            File(context.filesDir, "downloads"), folder.toString(), task.path,
            task.version ?: task.sha256 ?: "${task.mtime}:${task.size}",
        )
        var existing = if (staged.isFile) staged.length() else 0L
        if (existing > task.size) { staged.delete(); existing = 0L }
        if (existing < task.size || !staged.exists()) {
            val call = server.downloadCall(endpoint.baseUrl, task.path, existing, task.sha256)
            activeCall.set(call)
            try {
                call.execute().use { response ->
                    currentCoroutineContext().ensureActive()
                    if (response.code == 412 || response.code == 416) {
                        staged.delete()
                        throw IOException("Файл на сервере изменился. Обновите список синхронизации")
                    }
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${task.path}")
                    if (response.code == 206) {
                        val start = response.header("Content-Range")?.substringAfter("bytes ")
                            ?.substringBefore('-')?.toLongOrNull()
                        if (start != existing) throw IOException("Некорректный диапазон ответа сервера")
                    } else existing = 0L
                    val body = response.body ?: throw IOException("Пустой ответ")
                    FileOutputStream(staged, existing > 0).use { output ->
                        var downloaded = existing
                        val input = body.byteStream()
                        val buffer = ByteArray(DEFAULT_BUFFER)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            if (_paused.value) {
                                output.fd.sync()
                                markPaused(task.path, null, downloaded)
                                throw PausedException()
                            }
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            if (downloaded % (DEFAULT_BUFFER * 8) < DEFAULT_BUFFER) {
                                updateTask(task.path) { it.copy(bytesDownloaded = downloaded) }
                            }
                        }
                        output.fd.sync()
                    }
                }
            } finally { activeCall.compareAndSet(call, null) }
        }
        currentCoroutineContext().ensureActive()
        try { DownloadFiles.verify(staged, task.size, task.sha256) }
        catch (error: IOException) { staged.delete(); throw error }
        val checksum = task.sha256 ?: DownloadFiles.sha256(staged)
        val downloadJob = currentCoroutineContext()[Job]
        val uri = storage.replaceVerified(folder, task.path, staged, checksum) { downloadJob?.isActive == true && !_paused.value }
        staged.delete()
        updateTask(task.path) { it.copy(bytesDownloaded = task.size, status = DownloadStatus.DONE, error = null) }
        downloadSidecar(folder, endpoint.baseUrl, task, uri)
    }

    private suspend fun downloadSidecar(
        folder: Uri,
        baseUrl: String,
        task: DownloadTask,
        audioUri: Uri,
    ) {
        val basename = task.path.substringAfterLast('/')
        val extraPaths = arrayOf(basename, basename.substringBeforeLast('.'))
        ArtLog.d(
            "download.sidecar.start",
            "path=${task.path} hasArt=${task.hasArt}",
        )
        _statusMessage.value = "Обложка: $basename…"

        withTimeoutOrNull(SIDECAR_TOTAL_TIMEOUT_MS) {
            if (!task.hasArt) {
                ArtLog.d("download.sidecar.prefetch", task.path)
                _statusMessage.value = "Обложка: запрос на сервере для $basename…"
                runCatching { server.prefetchArtPaths(baseUrl, listOf(task.path), force = true) }
                    .onSuccess { result ->
                        ArtLog.d(
                            "download.sidecar.prefetch.result",
                            "ready=${result?.ready} missing=${result?.missing} status=${result?.status}",
                        )
                    }
                    .onFailure { ArtLog.w("download.sidecar.prefetch.fail", task.path, it) }
            }

            var bytes: ByteArray? = null
            for (attempt in 0 until SIDECAR_RETRY_COUNT) {
                if (attempt > 0) {
                    ArtLog.d("download.sidecar.retry", "path=${task.path} attempt=${attempt + 1}/$SIDECAR_RETRY_COUNT")
                    delay(SIDECAR_RETRY_DELAY_MS * attempt)
                }
                _statusMessage.value = "Обложка: $basename (${attempt + 1}/$SIDECAR_RETRY_COUNT)…"
                bytes = sidecarArtSync.downloadAndSave(
                    treeUri = folder,
                    serverBaseUrl = baseUrl,
                    audioRelativePath = task.path,
                    mtime = task.mtime,
                    audioUri = audioUri,
                    serverIndex = trackArtRepository.serverIndex,
                    extraPaths = extraPaths,
                    force = true,
                )
                if (bytes != null) {
                    ArtLog.d(
                        "download.sidecar.got",
                        "path=${task.path} bytes=${bytes.size}",
                    )
                    break
                }
                ArtLog.w("download.sidecar.attempt.miss", "path=${task.path} attempt=${attempt + 1}")
            }

            bytes?.takeIf { it.isNotEmpty() }?.let {
                trackArtRepository.onSidecarSaved(
                    relativePath = task.path,
                    version = task.mtime,
                    contentUri = audioUri,
                    bytes = it,
                )
            }
            trackArtRepository.notifySidecarFromDisk(task.path, task.mtime, audioUri)
        } ?: ArtLog.w("download.sidecar.timeout", "path=${task.path} after ${SIDECAR_TOTAL_TIMEOUT_MS}ms")

        ArtLog.d("download.sidecar.end", task.path)
    }

    private suspend fun refreshArtContext(endpoint: ServerEndpoint) {
        val index = withTimeoutOrNull(15_000L) {
            runCatching {
                ServerArtIndex.fromLibrary(server.library(endpoint.baseUrl))
            }.getOrDefault(trackArtRepository.serverIndex)
        } ?: trackArtRepository.serverIndex
        trackArtRepository.updateServerContext(endpoint.baseUrl, index)
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
        _tasks.update { tasks -> tasks.map { if (it.path == path) transform(it) else it } }
    }

    private fun channelLabel(channel: ConnectionChannel): String = when (channel) {
        ConnectionChannel.LAN -> "Загрузка по Wi‑Fi (LAN)"
        ConnectionChannel.TAILSCALE -> "Загрузка через Tailscale"
        ConnectionChannel.NONE -> "Сервер недоступен"
    }

    private class PausedException : IOException("paused")

    companion object {
        private const val DEFAULT_BUFFER = 64 * 1024
        private const val SIDECAR_RETRY_COUNT = 6
        private const val SIDECAR_RETRY_DELAY_MS = 1000L
        private const val SIDECAR_TOTAL_TIMEOUT_MS = 45_000L
    }
}
