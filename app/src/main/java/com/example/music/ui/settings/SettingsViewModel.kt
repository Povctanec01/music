package com.example.music.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.music.MusicApplication
import com.example.music.data.update.AppUpdateState
import com.example.music.domain.model.DownloadNetworkMode
import com.example.music.domain.model.GroupMode
import com.example.music.ui.sync.label
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val folderUri: String? = null,
    val lanUrl: String = "",
    val tailscaleUrl: String = "",
    val githubRepo: String = "",
    val networkMode: DownloadNetworkMode = DownloadNetworkMode.WIFI_ONLY,
    val groupModeOrder: List<GroupMode> = GroupMode.entries.toList(),
    val checking: Boolean = false,
    val checkResult: String? = null,
)

private data class SettingsCore(
    val folderUri: String?,
    val lanUrl: String,
    val tailscaleUrl: String,
    val githubRepo: String,
    val networkMode: DownloadNetworkMode,
    val groupModeOrder: List<GroupMode>,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as MusicApplication).container
    private val preferences = container.preferences
    private val library = container.libraryRepository
    private val server = container.serverClient
    val updateState: StateFlow<AppUpdateState> = container.appUpdateManager.state

    private val checking = MutableStateFlow(false)
    private val checkResult = MutableStateFlow<String?>(null)

    private val coreBase: StateFlow<SettingsCore> =
        combine(
            preferences.folderUri,
            preferences.lanUrl,
            preferences.tailscaleUrl,
            preferences.githubRepo,
            preferences.downloadNetworkMode,
        ) { folderUri, lanUrl, tailscaleUrl, githubRepo, networkMode ->
            SettingsCore(
                folderUri = folderUri,
                lanUrl = lanUrl,
                tailscaleUrl = tailscaleUrl,
                githubRepo = githubRepo,
                networkMode = networkMode,
                groupModeOrder = GroupMode.entries.toList(), // replaced below
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsCore(
            folderUri = null,
            lanUrl = "",
            tailscaleUrl = "",
            githubRepo = "",
            networkMode = DownloadNetworkMode.WIFI_ONLY,
            groupModeOrder = GroupMode.entries.toList(),
        ))

    private val coreWithOrder = combine(coreBase, preferences.groupModeOrder) { core, order ->
        core.copy(groupModeOrder = order)
    }

    val uiState: StateFlow<SettingsUiState> = combine(coreWithOrder, checking, checkResult) { core, isChecking, result ->
        SettingsUiState(
            folderUri = core.folderUri,
            lanUrl = core.lanUrl,
            tailscaleUrl = core.tailscaleUrl,
            githubRepo = core.githubRepo,
            networkMode = core.networkMode,
            groupModeOrder = core.groupModeOrder,
            checking = isChecking,
            checkResult = result,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun selectFolder(uri: Uri) {
        viewModelScope.launch { library.setFolder(uri) }
    }

    fun setLanUrl(url: String) {
        viewModelScope.launch { preferences.setLanUrl(url) }
    }

    fun setTailscaleUrl(url: String) {
        viewModelScope.launch { preferences.setTailscaleUrl(url) }
    }

    fun setNetworkMode(mode: DownloadNetworkMode) {
        viewModelScope.launch { preferences.setNetworkMode(mode) }
    }

    fun setGithubRepo(repo: String) {
        viewModelScope.launch {
            preferences.setGithubRepo(repo)
            container.appUpdateManager.refreshRepo(repo)
        }
    }

    fun checkConnection(lanUrl: String, tailscaleUrl: String) {
        viewModelScope.launch {
            checking.value = true
            checkResult.value = null
            preferences.setLanUrl(lanUrl)
            preferences.setTailscaleUrl(tailscaleUrl)
            val endpoint = server.pickEndpoint(lanUrl, tailscaleUrl)
            checkResult.value = if (endpoint == null) {
                "Сервер недоступен и по LAN, и по Tailscale"
            } else {
                "Доступен: ${endpoint.channel.label()} — ${endpoint.baseUrl}"
            }
            checking.value = false
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch { container.appUpdateManager.checkForUpdates() }
    }

    fun downloadAndInstallUpdate() {
        viewModelScope.launch { container.appUpdateManager.downloadAndInstall() }
    }

    fun setGroupModeOrder(order: List<GroupMode>) {
        viewModelScope.launch { preferences.setGroupModeOrder(order) }
    }
}
