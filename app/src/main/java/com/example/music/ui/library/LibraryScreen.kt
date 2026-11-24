package com.example.music.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.music.domain.formatDuration
import com.example.music.domain.model.GroupMode
import com.example.music.domain.model.LibraryRow
import com.example.music.domain.model.SortMode
import com.example.music.domain.model.Track
import com.example.music.domain.recommendation.RecommendationSection
import com.example.music.ui.components.TrackArtwork

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenPlayer: () -> Unit,
    viewModel: LibraryViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val player by viewModel.playback.state.collectAsStateWithLifecycle()
    val deleted by viewModel.lastDeleted.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(deleted?.id) {
        if (deleted != null && snackbar.showSnackbar(
                "Трек перемещён в корзину", actionLabel = "Отменить",
                withDismissAction = true, duration = SnackbarDuration.Long,
            ) == SnackbarResult.ActionPerformed) viewModel.undoDelete()
    }
    var searchOpen by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var swapFrom by remember { mutableStateOf<GroupMode?>(null) }
    var trackToDelete by remember { mutableStateOf<Track?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let(viewModel::selectFolder)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // When the app is opened/resumed back on this screen:
                // 1) "Список" should be active first
                // 2) show "Треки" as the first group chip
                swapFrom = null
                viewModel.setBrowseMode(LibraryBrowseMode.LIST)
                viewModel.setGroup(GroupMode.TRACKS)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Библиотека") },
                actions = {
                    IconButton(onClick = { searchOpen = !searchOpen }) {
                        Icon(Icons.Default.Search, contentDescription = "Поиск")
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(Icons.Default.Sort, contentDescription = "Сортировка")
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            SortMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.label()) },
                                    onClick = {
                                        viewModel.setSort(mode)
                                        sortMenu = false
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { picker.launch(null) }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Выбрать папку")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (searchOpen) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    placeholder = { Text("Название, исполнитель, альбом") },
                )
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = state.browseMode == LibraryBrowseMode.LIST,
                        onClick = { viewModel.setBrowseMode(LibraryBrowseMode.LIST) },
                        label = { Text("Список") },
                    )
                }
                item {
                    FilterChip(
                        selected = state.browseMode == LibraryBrowseMode.HOME,
                        onClick = { viewModel.setBrowseMode(LibraryBrowseMode.HOME) },
                        label = { Text("Главная") },
                    )
                }
                items(state.groupOrder, key = { it.name }) { mode ->
                    FilterChip(
                        selected = state.groupMode == mode,
                        modifier = Modifier.pointerInput(mode) {
                            detectTapGestures(
                                onLongPress = { swapFrom = mode },
                            )
                        },
                        onClick = {
                            if (swapFrom != null && swapFrom != mode) {
                                viewModel.swapGroupOrder(swapFrom!!, mode)
                            }
                            swapFrom = null
                            viewModel.setBrowseMode(LibraryBrowseMode.LIST)
                            viewModel.setGroup(mode)
                        },
                        label = { Text(mode.label()) },
                    )
                }
            }

            if (swapFrom != null) {
                Text(
                    "Долгое нажатие выбрало вкладку для обмена. Нажмите другую.",
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when {
                !state.folderSelected -> EmptyFolder(onPick = { picker.launch(null) })
                state.isScanning && state.totalCount == 0 -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                else -> PullToRefreshBox(
                    isRefreshing = state.isScanning,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (state.browseMode == LibraryBrowseMode.HOME && state.query.isBlank()) {
                        LibraryHomeContent(
                            state = state,
                            playerCurrentId = player.currentId,
                            onPlay = { track ->
                                val queue = state.recommendationSections
                                    .firstOrNull { track in it.tracks }
                                    ?.tracks
                                    ?: state.visibleTracks.ifEmpty { listOf(track) }
                                viewModel.play(track, queue)
                                onOpenPlayer()
                            },
                            onFavorite = { viewModel.toggleFavorite(it) },
                            onPlayNext = { viewModel.playNext(it) },
                        )
                    } else if (state.rows.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                state.error ?: if (state.groupMode == GroupMode.FAVORITES) {
                                    "В избранном пока пусто"
                                } else {
                                    "В папке нет аудио. Добавьте файлы или откройте синхронизацию."
                                },
                                modifier = Modifier.padding(32.dp),
                            )
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                            itemsIndexed(state.rows, key = { index, row ->
                                when (row) {
                                    is LibraryRow.Header -> "h-$index-${row.title}"
                                    is LibraryRow.Song -> row.track.id
                                }
                            }) { _, row ->
                                when (row) {
                                    is LibraryRow.Header -> Text(
                                        row.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    )

                                    is LibraryRow.Song -> TrackRow(
                                        track = row.track,
                                        selected = row.track.id == player.currentId,
                                        favorite = row.track.id in state.favorites,
                                        onClick = {
                                            viewModel.play(row.track, state.visibleTracks)
                                            onOpenPlayer()
                                        },
                                        onFavorite = { viewModel.toggleFavorite(row.track.id) },
                                        onPlayNext = { viewModel.playNext(row.track) },
                                        onDelete = { trackToDelete = row.track },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    trackToDelete?.let { track ->
        AlertDialog(
            onDismissRequest = { trackToDelete = null },
            title = { Text("Удалить с телефона?") },
            text = {
                Text("Трек «${track.title}» будет перемещён в корзину. Его можно восстановить.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = track
                        trackToDelete = null
                        viewModel.deleteTrack(target)
                    },
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { trackToDelete = null }) {
                    Text("Отмена")
                }
            },
        )
    }
}

@Composable
private fun LibraryHomeContent(
    state: LibraryUiState,
    playerCurrentId: String?,
    onPlay: (Track) -> Unit,
    onFavorite: (String) -> Unit,
    onPlayNext: (Track) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("Твоя музыка", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Локальная библиотека с быстрым доступом к свежим, любимым и рекомендованным трекам.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(
                        modifier = Modifier.padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        StatChip(label = "Треки", value = state.totalCount.toString())
                        StatChip(label = "Избранное", value = state.favorites.size.toString())
                        if (state.isScanning) StatChip(label = "Статус", value = "Сканирование")
                    }
                }
            }
        }

        items(state.recommendationSections, key = { it.key }) { section ->
            RecommendationRow(
                section = section,
                currentId = playerCurrentId,
                favorites = state.favorites,
                onPlay = onPlay,
                onFavorite = onFavorite,
                onPlayNext = onPlayNext,
            )
        }
    }
}

@Composable
private fun RecommendationRow(
    section: RecommendationSection,
    currentId: String?,
    favorites: Set<String>,
    onPlay: (Track) -> Unit,
    onFavorite: (String) -> Unit,
    onPlayNext: (Track) -> Unit,
) {
    Column {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(section.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                section.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(section.tracks, key = { it.id }) { track ->
                ElevatedCard(
                    modifier = Modifier
                        .size(width = 176.dp, height = 292.dp)
                        .clickable { onPlay(track) },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Box {
                            TrackArtwork(
                                track = track,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(150.dp),
                            )
                            if (track.id == currentId) {
                                Surface(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp),
                                    shape = RoundedCornerShape(999.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                ) {
                                    Text(
                                        "Сейчас",
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                        Text(
                            track.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        Text(
                            track.artistOrUnknown,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(formatDuration(track.durationMs), style = MaterialTheme.typography.labelSmall)
                            IconButton(onClick = { onPlayNext(track) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = "Воспроизвести следующим")
                            }
                            IconButton(onClick = { onFavorite(track.id) }, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    if (track.id in favorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = "Избранное",
                                    tint = if (track.id in favorites) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun EmptyFolder(onPick: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ElevatedCard(modifier = Modifier.padding(24.dp)) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Выберите папку с музыкой на телефоне", style = MaterialTheme.typography.titleMedium)
                Text(
                    "После выбора появятся рекомендации, обложки и быстрые подборки.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Button(onClick = onPick, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Выбрать папку")
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: Track,
    selected: Boolean,
    favorite: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    onPlayNext: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        ListItem(
            modifier = Modifier.clickable(onClick = onClick),
            leadingContent = {
                TrackArtwork(track = track, modifier = Modifier.size(56.dp), cornerSize = 16.dp)
            },
            headlineContent = {
                Text(
                    track.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            },
            supportingContent = {
                Text(
                    "${track.artistOrUnknown} • ${track.albumOrUnknown}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatDuration(track.durationMs), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = onPlayNext, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = "Воспроизвести следующим")
                    }
                    IconButton(onClick = onFavorite) {
                        Icon(
                            if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Избранное",
                            tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Ещё")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Воспроизвести следующим") },
                                onClick = {
                                    onPlayNext()
                                    menu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Удалить с телефона") },
                                onClick = {
                                    onDelete()
                                    menu = false
                                },
                            )
                        }
                    }
                }
            },
        )
    }
}

private fun GroupMode.label(): String = when (this) {
    GroupMode.TRACKS -> "Треки"
    GroupMode.ARTISTS -> "Исполнители"
    GroupMode.ALBUMS -> "Альбомы"
    GroupMode.FOLDERS -> "Папки"
    GroupMode.FAVORITES -> "Избранное"
}

private fun SortMode.label(): String = when (this) {
    SortMode.TITLE -> "По названию"
    SortMode.ARTIST -> "По исполнителю"
    SortMode.DATE_ADDED -> "По дате добавления"
    SortMode.DURATION -> "По длительности"
}
