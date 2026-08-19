package com.example.music.data.remote

import com.example.music.data.update.AppReleaseInfo
import com.example.music.domain.model.ConnectionChannel
import com.example.music.domain.model.RemoteFile
import com.example.music.domain.model.RemoteLibrary
import com.example.music.domain.model.ServerEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class ServerClient(private val json: Json) {

    private val healthClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .build()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun health(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext false
        val request = Request.Builder()
            .url(normalize(baseUrl) + "/api/health")
            .get()
            .build()
        try {
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

    fun downloadCall(baseUrl: String, relativePath: String, offset: Long) =
        http.newCall(downloadRequest(baseUrl, relativePath, offset))

    private fun downloadRequest(baseUrl: String, relativePath: String, offset: Long): Request {
        val encoded = relativePath.split('/')
            .joinToString("/") { part ->
                URLEncoder.encode(part, StandardCharsets.UTF_8.name()).replace("+", "%20")
            }
        val builder = Request.Builder().url(normalize(baseUrl) + "/files/" + encoded)
        if (offset > 0) {
            builder.header("Range", "bytes=$offset-")
        }
        return builder.get().build()
    }

    private fun normalize(url: String): String = url.trim().trimEnd('/')
}
