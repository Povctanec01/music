package com.example.music

import android.content.Context
import com.example.music.data.download.DownloadManager
import com.example.music.data.library.LibraryRepository
import com.example.music.data.local.MusicScanner
import com.example.music.data.local.SafStorage
import com.example.music.data.preferences.AppPreferences
import com.example.music.data.remote.NetworkMonitor
import com.example.music.data.remote.ServerClient
import com.example.music.data.update.AppUpdateManager
import com.example.music.domain.recommendation.RecommendationEngine
import com.example.music.player.PlaybackController
import kotlinx.serialization.json.Json

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    val preferences = AppPreferences(appContext, json)
    val scanner = MusicScanner(appContext)
    val safStorage = SafStorage(appContext)
    val serverClient = ServerClient(json)
    val networkMonitor = NetworkMonitor(appContext)
    val libraryRepository = LibraryRepository(appContext, preferences, scanner)
    val recommendationEngine = RecommendationEngine()
    val playbackController = PlaybackController(appContext, preferences)
    val appUpdateManager = AppUpdateManager(appContext, preferences, serverClient)
    val downloadManager = DownloadManager(
        context = appContext,
        preferences = preferences,
        server = serverClient,
        storage = safStorage,
        network = networkMonitor,
        onFinished = { libraryRepository.scan() },
    )

    fun start() {
        playbackController.connect()
        appUpdateManager.warmUp()
    }
}
