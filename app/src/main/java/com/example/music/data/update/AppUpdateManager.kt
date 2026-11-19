package com.example.music.data.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.music.data.preferences.AppPreferences
import com.example.music.data.remote.ServerClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class AppUpdateState(
    val currentVersionName: String,
    val currentVersionCode: Int,
    val githubRepo: String = "",
    val serverBaseUrl: String? = null,
    val latestRelease: AppReleaseInfo? = null,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val installing: Boolean = false,
    val downloadProgress: Float? = null, // null => unknown length => indeterminate
    val message: String? = null,
) {
    val updateAvailable: Boolean
        get() = (latestRelease?.versionCode ?: -1) > currentVersionCode && !latestRelease?.apkUrl.isNullOrBlank()
}

class AppUpdateManager(
    private val context: Context,
    private val preferences: AppPreferences,
    private val server: ServerClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkMutex = Mutex()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .build()

    private val initialState = run {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        AppUpdateState(
            currentVersionName = packageInfo.versionName.orEmpty(),
            currentVersionCode = packageInfo.longVersionCode.toInt(),
        )
    }

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    fun warmUp() {
        scope.launch {
            val repo = preferences.githubRepoOnce()
            _state.update { it.copy(githubRepo = repo) }
            if (repo.isNotBlank()) {
                checkForUpdates()
            }
        }
    }

    fun refreshRepo(repo: String) {
        _state.update { it.copy(githubRepo = repo.trim()) }
    }

    suspend fun checkForUpdates() {
        checkMutex.withLock {
        if (_state.value.downloading || _state.value.installing) return
        val repo = preferences.githubRepoOnce().trim()
        _state.update {
            it.copy(
                githubRepo = repo,
                checking = true,
                message = if (repo.isBlank()) "Укажи GitHub repo в формате owner/repo" else null,
                downloading = false,
                installing = false,
                downloadProgress = null,
            )
        }
        if (repo.isBlank()) {
            _state.update { it.copy(checking = false, latestRelease = null, serverBaseUrl = null) }
            return
        }
        runCatching {
            val endpoint = server.pickEndpoint(preferences.lanUrlOnce(), preferences.tailscaleUrlOnce())
                ?: error("Orange Pi недоступен и по LAN, и по Tailscale")
            val release = server.latestRelease(endpoint.baseUrl, repo)
            endpoint.baseUrl to release
        }.onSuccess { (baseUrl, release) ->
            val status = when {
                release.versionCode == null -> "В релизе нет versionCode. Используй тег вида v1.2.0+12."
                release.apkUrl.isNullOrBlank() -> "В последнем релизе не найден APK asset."
                release.versionCode > state.value.currentVersionCode -> "Доступно обновление ${release.versionName ?: release.tagName}"
                else -> "Установлена актуальная версия."
            }
            _state.update {
                it.copy(
                    serverBaseUrl = baseUrl,
                    latestRelease = release,
                    checking = false,
                    message = status,
                )
            }
        }.onFailure { error ->
            _state.update {
                it.copy(
                    checking = false,
                    latestRelease = null,
                    message = error.message ?: "Не удалось проверить обновления",
                )
            }
        }
        }
    }

    suspend fun downloadAndInstall() {
        if (_state.value.downloading || _state.value.installing) return
        val release = state.value.latestRelease ?: return
        val fileName = release.apkFileName?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.takeIf { it.endsWith(".apk", ignoreCase = true) } ?: "music-update.apk"
        val targetVersionCode = release.versionCode ?: return
        val baseUrl = state.value.serverBaseUrl ?: return
        val encodedRepo = URLEncoder.encode(release.repo, StandardCharsets.UTF_8.name())
        val apkUrl = "${baseUrl}/api/apk/latest?repo=$encodedRepo"
        _state.update {
            it.copy(
                downloading = true,
                installing = false,
                downloadProgress = null,
                message = "Скачиваю ${release.versionName ?: release.tagName}…",
            )
        }
        runCatching {
            val targetDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val targetFile = File(targetDir, fileName)
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(apkUrl).get().build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Не удалось скачать APK: ${response.code}")

                    val body = response.body ?: error("Пустой ответ при скачивании APK")
                    val totalBytes = body.contentLength()
                    var downloadedBytes = 0L

                    targetFile.outputStream().use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                downloadedBytes += read

                                val progress = if (totalBytes > 0) {
                                    (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    null
                                }
                                _state.update { it.copy(downloadProgress = progress) }
                            }
                        }
                    }
                }
            }

            preferences.setPendingUpdateRestart(targetVersionCode)
            _state.update {
                it.copy(
                    downloading = false,
                    installing = true,
                    downloadProgress = 1f,
                    message = "APK скачан. Открываю установщик…",
                )
            }
            openInstaller(targetFile)
        }.onSuccess {
            // Фактическая “установка” произойдёт в системном UI.
            // Мы перезапустим приложение, когда пользователь вернётся назад.
        }.onFailure { error ->
            _state.update {
                it.copy(
                    downloading = false,
                    installing = false,
                    downloadProgress = null,
                    message = error.message ?: (error.toString().ifBlank { "Не удалось скачать обновление" }),
                )
            }
        }
    }

    private fun openInstaller(file: File) {
        val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            error("Не найден установщик пакетов на устройстве")
        }
    }

    suspend fun onInstallerReturned(): Boolean {
        val targetVersion = preferences.pendingUpdateTargetVersionCodeOnce()
        preferences.clearPendingUpdateRestart()
        val installedVersion = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        val installed = targetVersion != null && installedVersion >= targetVersion
        _state.update {
            it.copy(installing = false, message = if (installed) "Обновление установлено" else "Установка не завершена")
        }
        return installed
    }
}
