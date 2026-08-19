package com.example.music.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.example.music.MusicApplication
import com.example.music.domain.formatDuration
import com.example.music.player.loadEmbeddedArtAsync
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as MusicApplication).container
    val playback = container.playbackController
    val state by playback.state.collectAsStateWithLifecycle()
    val favorites by container.preferences.favorites.collectAsStateWithLifecycle(initialValue = emptySet())
    val tracks by container.libraryRepository.tracks.collectAsStateWithLifecycle()
    val current = playback.currentTrack(tracks)
    val scope = rememberCoroutineScope()
    var cover by remember { mutableStateOf<Bitmap?>(null) }
    var sliderActive by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(0f) }
    var sleepMenu by remember { mutableStateOf(false) }

    LaunchedEffect(current?.uri) {
        cover = current?.uri?.let { loadEmbeddedArtAsync(context, it) }
    }

    val duration = state.durationMs.coerceAtLeast(0L)
    val position = if (sliderActive) sliderValue.toLong() else state.positionMs
    val favorite = state.currentId != null && state.currentId in favorites

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Сейчас играет") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { sleepMenu = true }) {
                            Icon(Icons.Default.Timer, contentDescription = "Таймер сна")
                        }
                        DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                            listOf(15, 30, 45, 60).forEach { minutes ->
                                DropdownMenuItem(
                                    text = { Text("$minutes мин") },
                                    onClick = {
                                        playback.startSleepTimer(minutes * 60_000L, fade = false)
                                        sleepMenu = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("$minutes мин с затуханием") },
                                    onClick = {
                                        playback.startSleepTimer(minutes * 60_000L, fade = true)
                                        sleepMenu = false
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Отменить таймер") },
                                onClick = {
                                    playback.cancelSleepTimer()
                                    sleepMenu = false
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = cover
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text("♪", style = MaterialTheme.typography.displayLarge)
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                state.title.ifBlank { "Ничего не играет" },
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                state.artist,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.sleepRemainingMs > 0) {
                Text(
                    "Сон через ${formatDuration(state.sleepRemainingMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            val sliderPosition = if (duration > 0) position.toFloat().coerceIn(0f, duration.toFloat()) else 0f
            val sliderRangeEnd = duration.toFloat().takeIf { it > 0f } ?: 1f
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val density = LocalDensity.current
                val trackHorizontalPadding = 10.dp
                val bubbleWidth = 70.dp
                val sliderFraction = (sliderPosition / sliderRangeEnd).coerceIn(0f, 1f)
                val thumbCenterOffsetPx = with(density) {
                    (trackHorizontalPadding.toPx() + (maxWidth - trackHorizontalPadding * 2).toPx() * sliderFraction).roundToInt()
                }
                val bubbleOffsetPx = with(density) { (bubbleWidth.toPx() / 2f).roundToInt() }

                if (sliderActive) {
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(thumbCenterOffsetPx - bubbleOffsetPx, 0) }
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(14.dp),
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = formatDuration(sliderPosition.toLong()),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Slider(
                    value = sliderPosition,
                    onValueChange = {
                        sliderActive = true
                        sliderValue = it
                    },
                    onValueChangeFinished = {
                        playback.seekTo(sliderValue.toLong())
                        sliderActive = false
                    },
                    valueRange = 0f..sliderRangeEnd,
                    enabled = duration > 0,
                    thumb = {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(RoundedCornerShape(percent = 50))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (sliderActive) 40.dp else 0.dp),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(position), style = MaterialTheme.typography.labelSmall)
                Text(formatDuration(duration), style = MaterialTheme.typography.labelSmall)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = playback::toggleShuffle) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = "Перемешать",
                        tint = if (state.shuffle) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = playback::skipPrevious) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Предыдущий", modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = playback::togglePlayPause, modifier = Modifier.size(72.dp)) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                    )
                }
                IconButton(onClick = playback::skipNext) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Следующий", modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = playback::cycleRepeat) {
                    Icon(
                        if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        contentDescription = "Повтор",
                        tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(
                onClick = {
                    val id = state.currentId ?: return@IconButton
                    scope.launch { container.preferences.toggleFavorite(id) }
                },
                enabled = state.currentId != null,
            ) {
                Icon(
                    if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Избранное",
                    tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Очередь", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Start))
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = true)) {
                itemsIndexed(state.queue, key = { index, item -> "${item.id}-$index" }) { index, item ->
                    val selected = index == state.currentIndex
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { playback.jumpToQueueIndex(index) }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(
                            item.title.ifBlank { "Трек" },
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            item.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
