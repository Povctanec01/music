package com.example.music.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.music.domain.model.DownloadNetworkMode
import com.example.music.domain.model.GroupMode
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    var lan by remember { mutableStateOf(state.lanUrl) }
    var tailscale by remember { mutableStateOf(state.tailscaleUrl) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let(viewModel::selectFolder)
    }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.lanUrl, state.tailscaleUrl) {
        if (lan != state.lanUrl && state.lanUrl.isNotBlank()) lan = state.lanUrl
        if (tailscale != state.tailscaleUrl) tailscale = state.tailscaleUrl
    }

    var didAutoPing by remember { mutableStateOf(false) }
    // Auto-ping servers once when opening Settings.
    LaunchedEffect(state.lanUrl, state.tailscaleUrl) {
        if (didAutoPing) return@LaunchedEffect
        if (state.lanUrl.isNotBlank() || state.tailscaleUrl.isNotBlank()) {
            didAutoPing = true
            viewModel.checkConnection(
                lan.ifBlank { state.lanUrl },
                tailscale.ifBlank { state.tailscaleUrl },
            )
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Настройки") }) },
    ) { padding ->
        var draggingIndex by remember { mutableStateOf<Int?>(null) }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState, enabled = draggingIndex == null),
        ) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Локальная папка", style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.folderUri ?: "Папка не выбрана",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                    OutlinedButton(onClick = { picker.launch(null) }) { Text("Выбрать папку") }
                    OutlinedButton(onClick = viewModel::restoreLastDeleted) { Text("Восстановить последний удалённый трек") }
                }
            }

            Spacer(Modifier.height(24.dp))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Пинг сервера", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = state.apiToken,
                        onValueChange = viewModel::setServerToken,
                        label = { Text("Ключ доступа сервера") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    Text(
                        "При открытии настроек серверы проверяются автоматически.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    OutlinedTextField(
                        value = lan,
                        onValueChange = { lan = it },
                        label = { Text("LAN URL") },
                        placeholder = { Text("http://192.168.1.80:8080") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = tailscale,
                        onValueChange = { tailscale = it },
                        label = { Text("Tailscale URL") },
                        placeholder = { Text("http://100.x.x.x:8080") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        singleLine = true,
                    )
                    Button(
                        onClick = { viewModel.checkConnection(lan, tailscale) },
                        enabled = !state.checking,
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        Text(if (state.checking) "Проверка…" else "Проверить соединение")
                    }
                    state.checkResult?.let {
                        Text(it, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("APK обновления", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Приложение само проверяет новые версии при запуске. Android всё равно покажет системный экран установки.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "Текущая версия: ${updateState.currentVersionName} (${updateState.currentVersionCode})",
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    updateState.latestRelease?.let { release ->
                        Text(
                            "Последний релиз: ${release.versionName ?: release.tagName} (${release.versionCode ?: "?"})",
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Row(modifier = Modifier.padding(top = 12.dp)) {
                        Button(
                            onClick = viewModel::downloadAndInstallUpdate,
                            enabled = updateState.updateAvailable && !updateState.downloading && !updateState.checking,
                        ) {
                            Text(if (updateState.downloading) "Скачивание…" else "Скачать и установить")
                        }
                    }
                    updateState.message?.let {
                        Text(it, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Сеть для загрузок", style = MaterialTheme.typography.titleMedium)
                    Column(Modifier.selectableGroup().padding(top = 8.dp)) {
                        NetworkOption(
                            title = "Только Wi‑Fi",
                            selected = state.networkMode == DownloadNetworkMode.WIFI_ONLY,
                            onClick = { viewModel.setNetworkMode(DownloadNetworkMode.WIFI_ONLY) },
                        )
                        NetworkOption(
                            title = "Wi‑Fi и Tailscale (включая мобильную сеть)",
                            selected = state.networkMode == DownloadNetworkMode.WIFI_OR_TAILSCALE,
                            onClick = { viewModel.setNetworkMode(DownloadNetworkMode.WIFI_OR_TAILSCALE) },
                        )
                        NetworkOption(
                            title = "Любые сети",
                            selected = state.networkMode == DownloadNetworkMode.ANY,
                            onClick = { viewModel.setNetworkMode(DownloadNetworkMode.ANY) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Порядок вкладок", style = MaterialTheme.typography.titleMedium)

                    val density = LocalDensity.current
                    val itemHeight = 64.dp
                    val itemStepPx = with(density) { (itemHeight + 8.dp).toPx() }

                    var localOrder by remember { mutableStateOf(state.groupModeOrder) }
                    var dragOffsetY by remember { mutableFloatStateOf(0f) }
                    var orderBeforeDrag by remember { mutableStateOf<List<GroupMode>?>(null) }
                    var pendingSave by remember { mutableStateOf(false) }

                    // Keep settings list in sync with preferences, but never while a drag/save is active.
                    LaunchedEffect(state.groupModeOrder, draggingIndex, pendingSave) {
                        if (draggingIndex == null && !pendingSave) {
                            localOrder = state.groupModeOrder
                        }
                        if (pendingSave && localOrder == state.groupModeOrder) {
                            pendingSave = false
                        }
                    }

                    val latestOrder by rememberUpdatedState(localOrder)
                    val latestDraggingIndex by rememberUpdatedState(draggingIndex)

                    Column(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        localOrder.forEachIndexed { index, mode ->
                            key(mode) {
                                val isDragging = draggingIndex == index
                                ElevatedCard(
                                    colors = CardDefaults.elevatedCardColors(
                                        containerColor = if (isDragging) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        },
                                    ),
                                    elevation = CardDefaults.elevatedCardElevation(
                                        defaultElevation = if (isDragging) 10.dp else 2.dp,
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(itemHeight)
                                        .graphicsLayer {
                                            translationY = if (isDragging) dragOffsetY else 0f
                                            scaleX = if (isDragging) 1.02f else 1f
                                            scaleY = if (isDragging) 1.02f else 1f
                                        },
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        DragHandle(
                                            active = isDragging,
                                            modifier = Modifier.pointerInput(mode) {
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = {
                                                        val startIndex = latestOrder.indexOf(mode)
                                                        if (startIndex >= 0) {
                                                            orderBeforeDrag = latestOrder
                                                            draggingIndex = startIndex
                                                            dragOffsetY = 0f
                                                        }
                                                    },
                                                    onDragCancel = {
                                                        orderBeforeDrag?.let { localOrder = it }
                                                        orderBeforeDrag = null
                                                        draggingIndex = null
                                                        dragOffsetY = 0f
                                                        pendingSave = false
                                                    },
                                                    onDragEnd = {
                                                        val finalOrder = latestOrder
                                                        orderBeforeDrag = null
                                                        dragOffsetY = 0f
                                                        pendingSave = true
                                                        viewModel.setGroupModeOrder(finalOrder)
                                                        draggingIndex = null
                                                    },
                                                    onDrag = { change, dragAmount ->
                                                        val from = latestDraggingIndex
                                                            ?: return@detectDragGesturesAfterLongPress
                                                        dragOffsetY += dragAmount.y
                                                        val current = latestOrder
                                                        val target = (from + (dragOffsetY / itemStepPx).roundToInt())
                                                            .coerceIn(0, current.lastIndex)
                                                        if (target != from) {
                                                            val next = current.toMutableList().apply {
                                                                add(target, removeAt(from))
                                                            }
                                                            localOrder = next
                                                            draggingIndex = target
                                                            dragOffsetY -= (target - from) * itemStepPx
                                                            // Keep dragged row visible if near edges.
                                                            scope.launch {
                                                                if (dragOffsetY > itemStepPx) {
                                                                    scrollState.scrollBy(itemStepPx / 2)
                                                                } else if (dragOffsetY < -itemStepPx) {
                                                                    scrollState.scrollBy(-itemStepPx / 2)
                                                                }
                                                            }
                                                        }
                                                        change.consume()
                                                    },
                                                )
                                            },
                                        )
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Text(
                                            mode.label(),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = if (isDragging) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Text(
                        "Тяните вкладку за 6 точек. Можно переносить на несколько позиций за раз — порядок сразу сохранится и появится в хотбаре библиотеки.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            val appVersion = remember {
                runCatching {
                    val ctx = context
                    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
                }.getOrDefault("?")
            }
            Text(
                "Версия приложения: $appVersion",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun DragHandle(active: Boolean, modifier: Modifier = Modifier) {
    val dotColor = if (active) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(3) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(2) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                }
            }
        }
    }
}

private fun GroupMode.label(): String = when (this) {
    GroupMode.TRACKS -> "Треки"
    GroupMode.ARTISTS -> "Исполнители"
    GroupMode.ALBUMS -> "Альбомы"
    GroupMode.FOLDERS -> "Папки"
    GroupMode.FAVORITES -> "Избранное"
}

@Composable
private fun NetworkOption(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(title, modifier = Modifier.padding(start = 8.dp))
    }
}
