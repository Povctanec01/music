package com.example.music.data.remote

import com.example.music.data.update.AppReleaseInfo
import com.example.music.domain.art.ArtLog
import com.example.music.domain.model.ConnectionChannel
import com.example.music.domain.model.RemoteFile
import com.example.music.domain.model.RemoteLibrary
import com.example.music.domain.model.ServerEndpoint
import com.example.music.domain.model.UploadResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Interceptor
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class ServerClient(private val json: Json, private val tokenProvider: () -> String = { "" }) {
    private val authorization = Interceptor { chain ->
        val request = chain.request()
        val token = tokenProvider()
        val writes = request.method !in setOf("GET", "HEAD", "OPTIONS") ||
            request.url.encodedPath in setOf("/api/art/refresh", "/api/art/prefetch", "/api/library/refresh", "/api/trash")
        chain.proceed(if (writes && token.isNotBlank()) request.newBuilder().header("Authorization", "Bearer $token").build() else request)
    }

    private val healthClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .build()

    private val http = OkHttpClient.Builder()
        .addInterceptor(authorization)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val prefetchHttp = OkHttpClient.Builder()
        .addInterceptor(authorization)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val downloadHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun health(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext false
        try {
            val request = Request.Builder()
                .url(normalize(baseUrl) + "/api/health")
                .get()
                .build()
            healthClient.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun pickEndpoint(lanUrl: String, tailscaleUrl: String): ServerEndpoint? {
        if (lanUrl.isNotBlank() && health(lanUrl)) {
            return ServerEndpoint(normalize(lanUrl), ConnectionChannel.LAN)
        }
        if (tailscaleUrl.isNotBlank() && health(tailscaleUrl)) {
            return ServerEndpoint(normalize(tailscaleUrl), ConnectionChannel.TAILSCALE)
        }
        return null
    }

    suspend fun library(baseUrl: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(normalize(baseUrl) + "/api/library")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Сервер ответил ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            json.decodeFromString<RemoteLibrary>(body).files
        }
    }

    suspend fun refreshAllArt(baseUrl: String): ArtRefreshResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(normalize(baseUrl) + "/api/art/refresh")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Сервер ответил ${response.code}")
            }
            json.decodeFromString<ArtRefreshResult>(response.body?.string().orEmpty())
        }
    }

    suspend fun prefetchArt(baseUrl: String): ArtPrefetchResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(normalize(baseUrl) + "/api/art/prefetch")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Сервер ответил ${response.code}")
            }
            json.decodeFromString<ArtPrefetchResult>(response.body?.string().orEmpty())
        }
    }

    suspend fun prefetchArtPaths(
        baseUrl: String,
        paths: List<String>,
        force: Boolean = false,
    ): ArtPrefetchResult? = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext null
        ArtLog.d("http.prefetchArtPaths", "paths=$paths force=$force")
        val body = json.encodeToString(ArtPrefetchRequest(paths, force))
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(normalize(baseUrl) + "/api/art/prefetch")
            .post(body)
            .build()
        val start = System.currentTimeMillis()
        try {
            prefetchHttp.newCall(request).execute().use { response ->
                ArtLog.d("http.prefetchArtPaths.response", "code=${response.code} ${System.currentTimeMillis() - start}ms")
                if (!response.isSuccessful) return@withContext null
                json.decodeFromString<ArtPrefetchResult>(response.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            ArtLog.w("http.prefetchArtPaths.fail", "${System.currentTimeMillis() - start}ms", e)
            null
        }
    }

    suspend fun latestRelease(baseUrl: String, repo: String): AppReleaseInfo = withContext(Dispatchers.IO) {
        val encodedRepo = URLEncoder.encode(repo.trim(), StandardCharsets.UTF_8.name())
        val request = Request.Builder()
            .url(normalize(baseUrl) + "/api/releases/latest?repo=$encodedRepo")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Релизный источник ответил ${response.code}")
            }
            json.decodeFromString<AppReleaseInfo>(response.body?.string().orEmpty())
        }
    }

    suspend fun upload(
        baseUrl: String,
        relativeDir: String,
        fileName: String,
        bytes: ByteArray,
        mimeType: String,
    ): UploadResponse = withContext(Dispatchers.IO) {
        val encodedPath = URLEncoder.encode(relativeDir.trim(), StandardCharsets.UTF_8.name())
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "files",
                fileName,
                bytes.toRequestBody(mimeType.toMediaType()),
            )
            .build()
        val request = Request.Builder()
            .url(normalize(baseUrl) + "/api/upload?path=$encodedPath")
            .header("X-File-Size", bytes.size.toString())
            .header("X-Content-SHA256", java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Ошибка загрузки: ${response.code}")
            }
            json.decodeFromString<UploadResponse>(response.body?.string().orEmpty())
        }
    }

    fun downloadCall(baseUrl: String, relativePath: String, offset: Long, sha256: String? = null) =
        downloadHttp.newCall(downloadRequest(baseUrl, relativePath, offset, sha256))

    suspend fun downloadFileBytes(baseUrl: String, relativePath: String): ByteArray? =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            try {
                downloadCall(baseUrl, relativePath, 0).execute().use { response ->
                    ArtLog.d("http.files", "path=$relativePath code=${response.code} ${System.currentTimeMillis() - start}ms")
                    if (!response.isSuccessful) return@withContext null
                    val bytes = response.body?.bytes() ?: return@withContext null
                    if (bytes.size <= 128) {
                        ArtLog.w("http.files.tooSmall", "path=$relativePath bytes=${bytes.size}")
                        return@withContext null
                    }
                    bytes
                }
            } catch (e: Exception) {
                ArtLog.w("http.files.fail", "path=$relativePath ${System.currentTimeMillis() - start}ms", e)
                null
            }
        }

    suspend fun downloadArtBytes(baseUrl: String, relativePath: String, version: Long): ByteArray? =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            try {
                val encoded = relativePath.split('/')
                    .joinToString("/") { part ->
                        URLEncoder.encode(part, StandardCharsets.UTF_8.name()).replace("+", "%20")
                    }
                val request = Request.Builder()
                    .url("${normalize(baseUrl)}/api/art/$encoded?v=$version")
                    .get()
                    .build()
                downloadHttp.newCall(request).execute().use { response ->
                    ArtLog.d("http.apiArt", "path=$relativePath code=${response.code} ${System.currentTimeMillis() - start}ms")
                    if (!response.isSuccessful) return@withContext null
                    val bytes = response.body?.bytes() ?: return@withContext null
                    if (bytes.size <= 128) {
                        ArtLog.w("http.apiArt.tooSmall", "path=$relativePath bytes=${bytes.size}")
                        return@withContext null
                    }
                    bytes
                }
            } catch (e: Exception) {
                ArtLog.w("http.apiArt.fail", "path=$relativePath ${System.currentTimeMillis() - start}ms", e)
                null
            }
        }

    private fun downloadRequest(baseUrl: String, relativePath: String, offset: Long, sha256: String? = null): Request {
        val encoded = relativePath.split('/')
            .joinToString("/") { part ->
                URLEncoder.encode(part, StandardCharsets.UTF_8.name()).replace("+", "%20")
            }
        val builder = Request.Builder().url(normalize(baseUrl) + "/files/" + encoded)
        if (!sha256.isNullOrBlank()) builder.header("If-Match", "\"$sha256\"")
        if (offset > 0) {
            builder.header("Range", "bytes=$offset-")
        }
        return builder.get().build()
    }

    private fun normalize(url: String): String = url.trim().trimEnd('/')
}

@Serializable
data class ArtRefreshResult(
    val total: Int = 0,
    val refreshed: Int = 0,
    val missing: Int = 0,
)

@Serializable
data class ArtPrefetchRequest(
    val paths: List<String>,
    val force: Boolean = false,
)

@Serializable
data class ArtPrefetchResult(
    val total: Int = 0,
    val ready: Int = 0,
    val missing: Int = 0,
    val pending: Int = 0,
    val status: String = "",
)
