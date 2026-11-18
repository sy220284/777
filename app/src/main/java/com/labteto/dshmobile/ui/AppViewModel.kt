package com.labteto.dshmobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.connection.AppSettings
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.update.AvailableUpdate
import com.labteto.dshmobile.update.UpdateChecker
import com.labteto.dshmobile.update.UpdateInstaller
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AppViewModel @Inject constructor(
    hostsStore: HostsStore,
    private val updateChecker: UpdateChecker,
    private val updateInstaller: UpdateInstaller,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = hostsStore.settings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppSettings(),
    )

    private val _updateInstallStatus = MutableStateFlow<String?>(null)
    val updateInstallStatus: StateFlow<String?> = _updateInstallStatus.asStateFlow()
    private val _updateCandidate = MutableStateFlow<AvailableUpdate?>(null)
    val updateCandidate: StateFlow<AvailableUpdate?> = _updateCandidate.asStateFlow()
    private var awaitingInstallPermission: AvailableUpdate? = null
    private var updateJob: Job? = null
    private val _downloadingUpdate = MutableStateFlow(false)
    val downloadingUpdate = _downloadingUpdate.asStateFlow()

    fun cancelUpdateDownload() {
        if (_downloadingUpdate.value) updateJob?.cancel()
    }

    fun checkForUpdate(currentVersion: String) {
        if (updateJob?.isActive == true) return
        awaitingInstallPermission?.let {
            _updateCandidate.value = it
            return
        }
        updateJob = viewModelScope.launch {
            _updateInstallStatus.value = "正在检查更新…"
            val update = try {
                updateChecker.checkNow(currentVersion)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _updateInstallStatus.value =
                    "检查更新失败：" + (error.message ?: error::class.java.simpleName)
                return@launch
            }

            if (update == null) {
                _updateInstallStatus.value = "当前已是最新版本。"
                return@launch
            }

            _updateInstallStatus.value = "发现新版本 ${update.version}，等待确认下载。"
            _updateCandidate.value = update
        }
    }

    fun dismissUpdate() {
        _updateCandidate.value = null
    }

    fun confirmUpdate() {
        if (updateJob?.isActive == true) return
        val update = _updateCandidate.value ?: return
        _updateCandidate.value = null
        awaitingInstallPermission = null
        updateJob = viewModelScope.launch {
            _downloadingUpdate.value = true
            _updateInstallStatus.value = if (update.patchChain.isNotEmpty()) {
                val patchBytes = update.patchChain.sumOf { it.size }
                val mib = patchBytes.toDouble() / (1024.0 * 1024.0)
                "发现新版本 ${update.version}，正在下载增量包（约 %.1f MiB）并校验…".format(mib)
            } else {
                "发现新版本 ${update.version}，正在下载完整 APK 并校验…"
            }
            _updateInstallStatus.value = try {
                val result = updateInstaller.downloadVerifyAndLaunch(update) { received, total ->
                    val mib = received / (1024.0 * 1024.0)
                    _updateInstallStatus.value = if (total != null)
                        "正在下载 %.1f / %.1f MiB…".format(mib, total / (1024.0 * 1024.0))
                    else "正在下载，已接收 %.1f MiB…".format(mib)
                }
                if (!result.launchedInstaller) awaitingInstallPermission = update
                result.message
            } catch (cancelled: CancellationException) {
                _updateInstallStatus.value = "下载已取消。同一版本可继续。"
                throw cancelled
            } catch (error: Exception) {
                "更新失败：" + (error.message ?: error::class.java.simpleName)
            } finally {
                _downloadingUpdate.value = false
            }
        }
    }


}
