package com.example.music.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.music.domain.model.DownloadNetworkMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    var lan by remember { mutableStateOf(state.lanUrl) }
    var tailscale by remember { mutableStateOf(state.tailscaleUrl) }
    var githubRepo by remember { mutableStateOf(state.githubRepo) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let(viewModel::selectFolder)
    }

    LaunchedEffect(state.lanUrl, state.tailscaleUrl) {
        if (lan != state.lanUrl && state.lanUrl.isNotBlank()) lan = state.lanUrl
        if (tailscale != state.tailscaleUrl) tailscale = state.tailscaleUrl
    }
    LaunchedEffect(state.githubRepo) {
        if (githubRepo != state.githubRepo) githubRepo = state.githubRepo
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Настройки") }) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Локальная папка", style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.folderUri ?: "Папка не выбрана",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                    OutlinedButton(onClick = { picker.launch(null) }) { Text("Выбрать папку") }
                }
            }

            Spacer(Modifier.height(24.dp))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Сервер Orange Pi", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = lan,
                        onValueChange = { lan = it },
                        label = { Text("LAN URL") },
                        placeholder = { Text("http://192.168.1.80:8080") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = tailscale,
                        onValueChange = { tailscale = it },
                        label = { Text("Tailscale URL") },
                        placeholder = { Text("http://100.x.x.x:8080") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
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
                Column(Modifier.padding(16.dp)) {
                    Text("APK обновления", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Тег релиза должен быть в формате v1.2.0+12, где +12 это versionCode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    OutlinedTextField(
                        value = githubRepo,
                        onValueChange = { githubRepo = it },
                        label = { Text("GitHub repo") },
                        placeholder = { Text("owner/repo") },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        singleLine = true,
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
                            onClick = {
                                viewModel.setGithubRepo(githubRepo)
                                viewModel.checkForUpdates()
                            },
                            enabled = !updateState.checking && !updateState.downloading,
                        ) {
                            Text(if (updateState.checking) "Проверка…" else "Проверить обновления")
                        }
                        Spacer(Modifier.width(12.dp))
                        Button(
                            onClick = {
                                viewModel.setGithubRepo(githubRepo)
                                viewModel.downloadAndInstallUpdate()
                            },
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
                Column(Modifier.padding(16.dp)) {
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
            Spacer(Modifier.height(32.dp))
        }
    }
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
