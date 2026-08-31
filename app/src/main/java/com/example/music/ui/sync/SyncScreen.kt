package com.example.music.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.music.domain.art.ArtLog
import com.example.music.domain.formatBytes
import com.example.music.domain.model.ConnectionChannel
import com.example.music.domain.model.DownloadStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(viewModel: SyncViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tasks by viewModel.downloads.tasks.collectAsStateWithLifecycle()
    val running by viewModel.downloads.running.collectAsStateWithLifecycle()
    val paused by viewModel.downloads.paused.collectAsStateWithLifecycle()
    val message by viewModel.downloads.statusMessage.collectAsStateWithLifecycle()
    val lastError by viewModel.downloads.lastError.collectAsStateWithLifecycle()
    val artLogs by ArtLog.recentLines.collectAsStateWithLifecycle()
    val selectedCount = state.newFiles.count { it.path in state.selected }
    val failedTasks = tasks.filter { it.status == DownloadStatus.ERROR && !it.error.isNullOrBlank() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Синхронизация") },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.checking) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val channel = state.endpoint?.channel ?: ConnectionChannel.NONE
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Источник синхронизации", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            state.checking -> "Проверка сервера…"
                            state.endpoint != null -> "Канал: ${channel.label()} — ${state.endpoint?.baseUrl}"
                            else -> "Сервер не найден"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "На сервере: ${state.remoteCount} • новых: ${state.newFiles.size}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!state.folderSelected) {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Сначала выберите папку в настройках — туда будут качаться новые треки.",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            state.error?.let {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                }
            }
            message?.let {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
                }
            }
            lastError?.let {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                }
            }

            if (running && artLogs.isNotEmpty()) {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Лог обложек", style = MaterialTheme.typography.titleSmall)
                        artLogs.takeLast(8).forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            if (tasks.isNotEmpty()) {
                val done = tasks.count { it.status == DownloadStatus.DONE }
                val current = tasks.firstOrNull { it.status == DownloadStatus.RUNNING }
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        LinearProgressIndicator(
                            progress = { current?.progress ?: if (tasks.isEmpty()) 0f else done.toFloat() / tasks.size },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                        )
                        Text("$done из ${tasks.size}" + (current?.let { " • ${it.path.substringAfterLast('/')}" } ?: ""))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                            if (running && !paused) OutlinedButton(onClick = viewModel::pause) { Text("Пауза") }
                            if (paused) OutlinedButton(onClick = viewModel::resume) { Text("Продолжить") }
                            TextButton(onClick = viewModel::cancel) { Text("Отмена") }
                        }
                    }
                }
            }

            if (failedTasks.isNotEmpty()) {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Ошибки загрузки", style = MaterialTheme.typography.titleMedium)
                        failedTasks.forEach { task ->
                            Text(
                                "${task.path.substringAfterLast('/')} — ${task.error}",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row {
                        TextButton(onClick = { viewModel.selectAll(state.newFiles) }) { Text("Все") }
                        TextButton(onClick = viewModel::selectNone) { Text("Снять") }
                    }
                    Button(
                        onClick = viewModel::downloadSelected,
                        enabled = state.folderSelected && selectedCount > 0 && !running,
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Скачать ($selectedCount)")
                    }
                }
            }

            if (state.newFiles.isEmpty() && !state.checking && state.endpoint != null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Новых файлов нет — библиотека совпадает с сервером.", modifier = Modifier.padding(top = 16.dp))
                }
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(state.newFiles, key = { it.path }) { file ->
                    ListItem(
                        headlineContent = {
                            Text(file.path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text("${file.path.substringBeforeLast('/', "")} • ${formatBytes(file.size)}")
                        },
                        leadingContent = {
                            Checkbox(
                                checked = file.path in state.selected,
                                onCheckedChange = { viewModel.toggle(file.path) },
                            )
                        },
                    )
                }
            }
        }
    }
}
