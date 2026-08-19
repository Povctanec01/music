package com.example.music.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.music.MusicApplication
import com.example.music.ui.library.LibraryScreen
import com.example.music.ui.player.MiniPlayer
import com.example.music.ui.player.NowPlayingScreen
import com.example.music.ui.settings.SettingsScreen
import com.example.music.ui.sync.SyncScreen
import kotlinx.coroutines.launch

private object Routes {
    const val Library = "library"
    const val Sync = "sync"
    const val Settings = "settings"
    const val NowPlaying = "nowPlaying"
}

@Composable
fun MusicApp() {
    val context = LocalContext.current
    val container = (context.applicationContext as MusicApplication).container
    val playback = container.playbackController
    val updates = container.appUpdateManager
    val playerState by playback.state.collectAsStateWithLifecycle()
    val updateState by updates.state.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showChrome = route != Routes.NowPlaying
    val scope = rememberCoroutineScope()
    var dismissedTag by remember { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    val notifyPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(updateState.latestRelease?.tagName) {
        dismissedTag = null
    }

    androidx.compose.runtime.DisposableEffect(lifecycleOwner, updates) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (!updates.state.value.checking) {
                    scope.launch { updates.checkForUpdates() }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showChrome) {
                Column {
                    MiniPlayer(
                        state = playerState,
                        onTogglePlay = playback::togglePlayPause,
                        onNext = playback::skipNext,
                        onOpen = { navController.navigate(Routes.NowPlaying) },
                    )
                    NavigationBar {
                        NavigationBarItem(
                            selected = route == Routes.Library,
                            onClick = { navController.navigateTop(Routes.Library) },
                            icon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
                            label = { Text("Библиотека") },
                        )
                        NavigationBarItem(
                            selected = route == Routes.Sync,
                            onClick = { navController.navigateTop(Routes.Sync) },
                            icon = { Icon(Icons.Default.Sync, contentDescription = null) },
                            label = { Text("Синк") },
                        )
                        NavigationBarItem(
                            selected = route == Routes.Settings,
                            onClick = { navController.navigateTop(Routes.Settings) },
                            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            label = { Text("Настройки") },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.Library,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            composable(Routes.Library) {
                LibraryScreen(onOpenPlayer = { navController.navigate(Routes.NowPlaying) })
            }
            composable(Routes.Sync) { SyncScreen() }
            composable(Routes.Settings) { SettingsScreen() }
            composable(Routes.NowPlaying) {
                NowPlayingScreen(onBack = { navController.popBackStack() })
            }
        }
    }

    val shouldShowUpdateDialog = showChrome &&
        updateState.updateAvailable &&
        updateState.latestRelease != null &&
        dismissedTag != updateState.latestRelease?.tagName

    if (shouldShowUpdateDialog) {
        val release = updateState.latestRelease!!
        AlertDialog(
            onDismissRequest = { dismissedTag = release.tagName },
            title = { Text("Доступно обновление") },
            text = {
                Text(
                    "Найдена версия ${release.versionName ?: release.tagName}. " +
                        "Можно скачать APK и открыть системный установщик прямо сейчас.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        dismissedTag = release.tagName
                        scope.launch { updates.downloadAndInstall() }
                    },
                ) {
                    Text("Скачать и установить")
                }
            },
            dismissButton = {
                TextButton(onClick = { dismissedTag = release.tagName }) {
                    Text("Позже")
                }
            },
        )
    }
}

private fun androidx.navigation.NavHostController.navigateTop(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
