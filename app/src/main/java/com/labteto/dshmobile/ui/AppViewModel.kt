package com.labteto.dshmobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.connection.AppSettings
import com.labteto.dshmobile.connection.ConnectionManager
import com.labteto.dshmobile.connection.ConnectionUiState
import com.labteto.dshmobile.connection.HostsStore
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

    private val _updateInstallStatus = MutableStateFlow<String?>(null)
    val updateInstallStatus: StateFlow<String?> = _updateInstallStatus.asStateFlow()

    /**
     * Manual update flow only.
     *
     * The app never contacts GitHub on launch. A tap in Settings checks once, and when a newer
     * signed APK is available it immediately downloads, verifies and hands it to Android's system
     * installer.
     */
    fun checkForUpdateAndInstall(currentVersion: String) {
        if (_updateInstallStatus.value?.startsWith("正在") == true) return
        viewModelScope.launch {
            _updateInstallStatus.value = "正在检查更新…"
            val update = runCatching { updateChecker.checkNow(currentVersion) }
                .getOrElse { error ->
                    _updateInstallStatus.value =
                        "检查更新失败：" + (error.message ?: error::class.java.simpleName)
                    return@launch
                }

            if (update == null) {
                _updateInstallStatus.value = "当前已是最新版本。"
                return@launch
            }

            _updateInstallStatus.value = "发现新版本 ${update.version}，正在下载并校验…"
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
