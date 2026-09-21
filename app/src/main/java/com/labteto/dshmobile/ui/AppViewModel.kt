package com.labteto.dshmobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.connection.AppSettings
import com.labteto.dshmobile.connection.ConnectionManager
import com.labteto.dshmobile.connection.ConnectionUiState
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.update.AvailableUpdate
import com.labteto.dshmobile.update.UpdateChecker
import com.labteto.dshmobile.update.UpdateInstaller
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AppViewModel @Inject constructor(
    hostsStore: HostsStore,
    private val connectionManager: ConnectionManager,
    private val updateChecker: UpdateChecker,
    private val updateInstaller: UpdateInstaller,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = hostsStore.settings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppSettings(),
    )

    val connectionState: StateFlow<ConnectionUiState> = connectionManager.state

    /** A newer release to offer, or null. See [UpdateChecker]. */
    val availableUpdate: StateFlow<AvailableUpdate?> = updateChecker.available

    private val _updateInstallStatus = MutableStateFlow<String?>(null)
    val updateInstallStatus: StateFlow<String?> = _updateInstallStatus.asStateFlow()

    fun checkForUpdate(currentVersion: String) {
        viewModelScope.launch { updateChecker.checkOnce(currentVersion) }
    }

    fun dismissUpdate(version: String) {
        viewModelScope.launch { updateChecker.dismiss(version) }
    }

    fun installUpdate(update: AvailableUpdate) {
        if (_updateInstallStatus.value?.startsWith("正在") == true) return
        viewModelScope.launch {
            _updateInstallStatus.value = "正在下载并校验更新…"
            _updateInstallStatus.value = runCatching {
                updateInstaller.downloadVerifyAndLaunch(update).message
            }.getOrElse { error ->
                "更新失败：" + (error.message ?: error::class.java.simpleName)
            }
        }
    }

    fun clearUpdateInstallStatus() {
        _updateInstallStatus.value = null
    }

    fun disconnectRemote() {
        connectionManager.disconnect()
    }
}
