package com.example.music.ui.trim

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.music.domain.model.ConnectionChannel
import com.example.music.domain.model.RemoteFile
import com.example.music.domain.model.Track
import com.example.music.ui.components.TrackArtwork
import com.example.music.ui.components.TrackArtworkFromPath

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimScreen(viewModel: TrimViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val localTracks by viewModel.filteredLocalTracks.collectAsStateWithLifecycle()
    val remoteTracks by viewModel.filteredRemoteTracks.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val previewPlayer = remember(state.fileUri) {
        state.fileUri?.let {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(it))
                prepare()
            }
        }
    }

    DisposableEffect(previewPlayer) {
        val player = previewPlayer
        if (player == null) {
            onDispose { }
        } else {
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    viewModel.setPlaying(isPlaying)
                }
            }
            player.addListener(listener)
            onDispose {
                player.removeListener(listener)
                player.release()
            }
        }
    }

    LaunchedEffect(previewPlayer, state.playing, state.endSec) {
        val player = previewPlayer ?: return@LaunchedEffect
        while (true) {
            if (player.isPlaying) {
                val sec = player.currentPosition / 1000f
                viewModel.setCurrentSec(sec)
                if (sec >= state.endSec - 0.05f) {
                    player.pause()
                    player.seekTo((state.endSec * 1000).toLong())
                }
            }
            kotlinx.coroutines.delay(50)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Редактор") },
                actions = {
                    IconButton(onClick = viewModel::refreshEndpoint, enabled = !state.checking) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить списки")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Обрезка трека", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Выбери трек из списка, отметь начало и конец, затем загрузи WAV на Orange Pi.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val channel = state.endpoint?.channel ?: ConnectionChannel.NONE
                        Text(
                            when {
                                state.checking -> "Проверка сервера…"
                                state.endpoint != null ->
                                    "Сервер: ${channel.trimLabel()} — ${state.endpoint?.baseUrl}"
                                else -> "Сервер недоступен (локальные треки доступны)"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    label = { Text("Поиск") },
                    placeholder = { Text("Название, исполнитель, путь…") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !state.busy,
                )
            }

            if (state.fileName != null) {
                item {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Выбран: ${state.fileName}",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                }
            }

            state.error?.let { error ->
                item {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                    }
                }
            }
            state.message?.let { message ->
                item {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Text(message, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
                    }
                }
            }

            if (state.loadingLibrary) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }

            if (state.fileUri != null && state.durationMs > 0L) {
                item {
                    WaveformTrimEditor(
                        fileName = state.fileName.orEmpty(),
                        durationSec = state.durationMs / 1000f,
                        startSec = state.startSec,
                        endSec = state.endSec,
                        currentSec = state.currentSec.coerceIn(state.startSec, state.endSec),
                        peaks = state.waveformPeaks,
                        loadingPeaks = state.loadingWaveform,
                        busy = state.busy,
                        playing = state.playing,
                        onStartChange = viewModel::setStartSec,
                        onEndChange = viewModel::setEndSec,
                        onSeek = { sec ->
                            viewModel.setCurrentSec(sec)
                            previewPlayer?.seekTo((sec * 1000).toLong())
                        },
                        onTogglePlay = {
                            val player = previewPlayer ?: return@WaveformTrimEditor
                            if (player.isPlaying) {
                                player.pause()
                            } else {
                                if (player.currentPosition < state.startSec * 1000 ||
                                    player.currentPosition >= state.endSec * 1000
                                ) {
                                    player.seekTo((state.startSec * 1000).toLong())
                                }
                                player.play()
                            }
                        },
                        onReset = viewModel::resetRange,
                        onClose = viewModel::clearSelection,
                        onSave = viewModel::trimAndUpload,
                    )
                }
                item {
                    OutlinedTextField(
                        value = state.uploadDir,
                        onValueChange = viewModel::setUploadDir,
                        label = { Text("Подпапка на Orange Pi") },
                        placeholder = { Text("Например: edits") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !state.busy,
                    )
                }
            }

            if (state.fileUri == null && localTracks.isNotEmpty()) {
                item {
                    Text("Локальная библиотека", style = MaterialTheme.typography.titleSmall)
                }
                items(localTracks, key = { it.id }) { track ->
                    TrackPickRow(
                        track = track,
                        selected = state.fileUri == track.uri,
                        enabled = !state.busy,
                        onClick = { viewModel.selectLocalTrack(track) },
                    )
                }
            }

            if (state.fileUri == null && remoteTracks.isNotEmpty()) {
                item {
                    Text("На Orange Pi", style = MaterialTheme.typography.titleSmall)
                }
                items(remoteTracks, key = { it.path }) { file ->
                    RemotePickRow(
                        file = file,
                        selected = state.fileName == file.path.substringAfterLast('/'),
                        enabled = !state.busy && state.endpoint != null,
                        onClick = { viewModel.selectRemoteTrack(file) },
                    )
                }
            }

            if (state.fileUri == null && !state.loadingLibrary && localTracks.isEmpty() && remoteTracks.isEmpty()) {
                item {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Список пуст. Выберите папку в настройках или проверьте связь с Orange Pi.",
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }

            item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp)) }
        }
    }
}

@Composable
private fun TrackPickRow(
    track: Track,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        leadingContent = {
            TrackArtwork(
                track = track,
                modifier = Modifier.size(56.dp),
                cornerSize = 12.dp,
            )
        },
        headlineContent = {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(track.artistOrUnknown, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        colors = if (selected) {
            androidx.compose.material3.ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            )
        } else {
            androidx.compose.material3.ListItemDefaults.colors()
        },
    )
}

@Composable
private fun RemotePickRow(
    file: RemoteFile,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val name = file.path.substringAfterLast('/')
    ListItem(
        leadingContent = {
            TrackArtworkFromPath(
                relativePath = file.path,
                version = file.mtime,
                modifier = Modifier.size(56.dp),
                cornerSize = 12.dp,
                fallbackLabel = name.firstOrNull()?.uppercase(),
            )
        },
        headlineContent = {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(file.path.substringBeforeLast('/', missingDelimiterValue = ""), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        colors = if (selected) {
            androidx.compose.material3.ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            )
        } else {
            androidx.compose.material3.ListItemDefaults.colors()
        },
    )
}
