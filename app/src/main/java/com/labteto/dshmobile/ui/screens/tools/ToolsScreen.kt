package com.labteto.dshmobile.ui.screens.tools

import com.shenyan.designsystem.foundation.SyMetric

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import android.content.ClipData
import android.content.Intent
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import androidx.core.content.FileProvider
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.R
import com.shenyan.designsystem.components.SyTextField
import com.labteto.dshmobile.uicontract.UiMcpServerSnapshot as McpServerSnapshot
import com.labteto.dshmobile.local.presentation.LocalToolsUiFacade
import com.labteto.dshmobile.local.tools.LocalNetworkSearchSettings
import com.labteto.dshmobile.local.network.requiresAndroid17LocalNetworkPermission
import com.labteto.dshmobile.local.interaction.LocalApprovalMode
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SySegment
import com.labteto.dshmobile.ui.shenyan.SySegmented
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyDialog
import com.shenyan.designsystem.components.SySwitchControl
import com.labteto.dshmobile.ui.shenyan.SyToastHost
import com.labteto.dshmobile.ui.shenyan.SyTopBar
import com.shenyan.designsystem.components.SyLoadingState
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.SyEntryIcons
import com.labteto.dshmobile.ui.components.rememberHapticPulse
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.shenyan.rememberSyToast
import com.labteto.dshmobile.ui.screens.settings.SettingsDestination
import com.labteto.dshmobile.ui.screens.settings.SettingsViewModel
import com.labteto.dshmobile.ui.screens.settings.LocalAgentSettingsCard
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.labteto.dshmobile.ui.theme.rootSurface
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ToolsNotice {
    LOAD_FAILED,
    CONNECTING,
    CONNECTED,
    CONNECT_FAILED,
    DISCONNECTED,
    DISCONNECT_FAILED,
}

data class ToolsUiState(
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val mcpLoginServerId: String? = null,
    val servers: List<McpServerSnapshot> = emptyList(),
    val githubConfigured: Boolean = false,
    val localPlugins: List<String> = emptyList(),
    val activeToolHookIds: List<String> = emptyList(),
    val skills: List<com.labteto.dshmobile.local.presentation.LocalSkillUiEntry> = emptyList(),
    val presets: List<com.labteto.dshmobile.local.presentation.LocalPresetSkillUiEntry> = emptyList(),
    val curatedSkills: List<String> = emptyList(),
    val codexPlugins: List<com.labteto.dshmobile.uicontract.UiCodexPluginSnapshot> = emptyList(),
    val codexLoading: Boolean = false,
    val codexError: String? = null,
    val curatedLoading: Boolean = false,
    val curatedError: String? = null,
    val pendingSkillUpdates: Map<String, String> = emptyMap(),
    val upToDateSkills: Set<String> = emptySet(),
    val createdSkillId: String? = null,
    val skillEditor: com.labteto.dshmobile.local.presentation.LocalSkillEditorUiEntry? = null,
    val savedSkillId: String? = null,
    val notice: ToolsNotice? = null,
    val feedback: String? = null,
    val feedbackRes: Int? = null,
)

/** Safe user-facing classification; never surface raw URLs, tokens or command-line contents. */
internal fun localToolConnectionErrorMessageRes(error: Throwable): Int {
    var cause: Throwable = error
    repeat(4) {
        val nested = cause.cause ?: return@repeat
        cause = nested
    }
    return when (cause) {
        is java.net.UnknownHostException -> R.string.tools_error_dns
        is java.net.SocketTimeoutException -> R.string.tools_error_timeout
        is java.net.ConnectException -> R.string.tools_error_unreachable
        is SecurityException -> R.string.tools_error_permission
        is IllegalArgumentException -> R.string.tools_error_config
        else -> R.string.tools_error_generic
    }
}

internal class ToolsOperationGate {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}

@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val localTools: LocalToolsUiFacade,
    @ApplicationContext private val appContext: Context,
    private val networkSearchSettings: LocalNetworkSearchSettings,
) : ViewModel() {
    private val _state = MutableStateFlow(ToolsUiState())
    val state: StateFlow<ToolsUiState> = _state.asStateFlow()
    private val _agentBackendStatus = MutableStateFlow("正在读取执行器设置")
    val agentBackendStatus: StateFlow<String> = _agentBackendStatus.asStateFlow()
    fun refreshAgentBackend() { viewModelScope.launch {
        try { _agentBackendStatus.value = localTools.agentBackendStatus() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { _agentBackendStatus.value = "执行器设置读取失败，原授权已保留" }
    } }
    fun probeAgentBackend() { viewModelScope.launch {
        _agentBackendStatus.value = "正在验证 TLS 证书、连接口令与服务授权…"
        try {
            _agentBackendStatus.value = localTools.probeAgentBackend()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            _agentBackendStatus.value = "远端连接验证失败：" + (failure.message ?: "连接中断")
        }
    } }
    fun configureAgentBackend(
        host: String,
        port: Int,
        pin: String,
        token: String,
        consent: Boolean,
        onCompleted: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            _agentBackendStatus.value = "正在保存远端授权…"
            try {
                operationGate.run { localTools.configureAgentBackend(host, port, pin, token, consent) }
                // The UI may discard its in-memory token only after the persistence call succeeds.
                _agentBackendStatus.value = "已保存远端授权，待连接验证"
                onCompleted(true)
                refreshAgentBackend()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _agentBackendStatus.value = "保存失败，请检查主机、端口、证书和连接口令"
                onCompleted(false)
            }
        }
    }
    fun revokeAgentBackend() { viewModelScope.launch {
        try { operationGate.run { localTools.revokeAgentBackend() }; refreshAgentBackend() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { _agentBackendStatus.value = "撤销失败，请重试；原授权状态未确认" }
    } }
    val networkSearchEnabled: StateFlow<Boolean> = networkSearchSettings.enabled
    val deepSeekSearchPreferred: StateFlow<Boolean> = networkSearchSettings.deepSeekPreferred

    fun setNetworkSearchEnabled(enabled: Boolean) = networkSearchSettings.setEnabled(enabled)
    fun setDeepSeekSearchPreferred(enabled: Boolean) = networkSearchSettings.setDeepSeekPreferred(enabled)
    private val operationGate = ToolsOperationGate()
    private var mcpLoginJob: kotlinx.coroutines.Job? = null
    private val _githubBrowse = MutableStateFlow(GitHubBrowseState())
    internal val githubBrowse: StateFlow<GitHubBrowseState> = _githubBrowse.asStateFlow()

    fun loadGitHubRepositories() {
        launchOperation {
            _githubBrowse.value = GitHubBrowseState(loading = true)
            try {
                _githubBrowse.value = GitHubBrowseState(repositories = localTools.githubRepositories())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _githubBrowse.value = GitHubBrowseState(error = error.message ?: "仓库读取失败")
            }
        }
    }

    fun openGitHubRepository(fullName: String) {
        if (_githubBrowse.value.repositories.none { it.fullName == fullName }) return
        loadGitHubDirectory(fullName, "")
    }

    fun openGitHubFolder(path: String) {
        val browse = _githubBrowse.value
        val repo = browse.selectedRepository ?: return
        if (browse.entries.none { it.directory && it.path == path }) return
        loadGitHubDirectory(repo, path)
    }

    fun backGitHubBrowse() {
        val browse = _githubBrowse.value
        val repo = browse.selectedRepository ?: return
        if (browse.currentDirectory.isEmpty()) {
            _githubBrowse.value = browse.copy(selectedRepository = null, entries = emptyList(), error = null)
        } else {
            loadGitHubDirectory(repo, browse.currentDirectory.substringBeforeLast('/', ""))
        }
    }

    fun refreshGitHubBrowse() {
        val browse = _githubBrowse.value
        val repository = browse.selectedRepository
        if (repository == null) loadGitHubRepositories()
        else loadGitHubDirectory(repository, browse.currentDirectory)
    }

    private fun loadGitHubDirectory(repo: String, directory: String) {
        launchOperation {
            _githubBrowse.value = _githubBrowse.value.copy(loading = true, error = null)
            try {
                val entries = localTools.githubEntries(repo, directory)
                _githubBrowse.value = _githubBrowse.value.copy(
                    loading = false, selectedRepository = repo, currentDirectory = directory,
                    entries = entries, error = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _githubBrowse.value = _githubBrowse.value.copy(
                    loading = false, error = error.message ?: "目录读取失败",
                )
            }
        }
    }

    private fun launchOperation(block: suspend () -> Unit) {
        viewModelScope.launch {
            operationGate.run(block)
        }
    }

    init {
        refresh()
        viewModelScope.launch {
            localTools.skillChanges().collectLatest {
                // A directory may be created just before its SKILL.md is written.
                delay(220)
                operationGate.run {
                    val skills = localTools.installedSkills()
                    val presets = localTools.presetSkills()
                    val activeIds = skills.map { it.name }.toSet()
                    _state.value = _state.value.copy(
                        skills = skills, presets = presets,
                        pendingSkillUpdates = _state.value.pendingSkillUpdates.filterKeys { it in activeIds },
                        upToDateSkills = _state.value.upToDateSkills.filterTo(mutableSetOf()) { it in activeIds },
                    )
                }
            }
        }
    }

    fun acknowledgeNotice(notice: ToolsNotice) {
        if (_state.value.notice == notice) {
            _state.value = _state.value.copy(notice = null)
        }
    }

    fun acknowledgeFeedback() {
        _state.value = _state.value.copy(feedback = null, feedbackRes = null)
    }

    fun refresh() {
        launchOperation {
            _state.value = _state.value.copy(loading = true, loadFailed = false, notice = null)
            try {
                val skills = localTools.installedSkills()
                val presets = localTools.presetSkills()
                _state.value = _state.value.copy(skills = skills, presets = presets)
                val (servers, plugins) = localTools.servers() to localTools.installedPluginIds()
                _state.value = ToolsUiState(
                    loading = false,
                    servers = servers,
                    githubConfigured = localTools.githubConfigured(),
                    localPlugins = plugins,
                    activeToolHookIds = localTools.activeToolHookIds(),
                    skills = skills,
                    presets = presets,
                    curatedSkills = _state.value.curatedSkills,
                    codexPlugins = _state.value.codexPlugins,
                    codexLoading = _state.value.codexLoading,
                    codexError = _state.value.codexError,
                    curatedLoading = _state.value.curatedLoading,
                    curatedError = _state.value.curatedError,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    loadFailed = true,
                    notice = ToolsNotice.LOAD_FAILED,
                )
            }
        }
    }

    private fun changeSkills(createdId: String? = null, action: suspend () -> Unit) {
        launchOperation {
            _state.value = _state.value.copy(loading = true, feedback = null, createdSkillId = null)
            try {
                action()
                _state.value = _state.value.copy(
                    loading = false,
                    skills = localTools.installedSkills(),
                    presets = localTools.presetSkills(),
                    pendingSkillUpdates = emptyMap(),
                    upToDateSkills = emptySet(),
                    createdSkillId = createdId,
                    feedbackRes = R.string.skills_saved,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    feedback = error.message,
                    feedbackRes = if (error.message == null) R.string.skills_operation_failed else null,
                )
            }
        }
    }

    /** Fetch on user entry to Skills; discovery never installs a package. */
    fun refreshCuratedSkills() {
        if (_state.value.curatedLoading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(curatedLoading = true, curatedError = null)
            try {
                val names = localTools.curatedGitHubSkills()
                    .filter { it.matches(Regex("[a-z][a-z0-9-]{1,47}")) }
                    .distinct().sorted()
                _state.value = _state.value.copy(curatedLoading = false, curatedSkills = names)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(curatedLoading = false,
                    curatedError = "官方精选目录读取失败：" + (error.message ?: "请检查网络"))
            }
        }
    }

    /** Fetches official Codex catalog metadata, without installing or authorizing anything. */
    fun refreshCodexPlugins() {
        if (_state.value.codexLoading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(codexLoading = true, codexError = null)
            try {
                val plugins = localTools.codexOfficialPlugins()
                _state.value = _state.value.copy(codexLoading = false, codexPlugins = plugins)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.value = _state.value.copy(
                    codexLoading = false,
                    codexError = "Codex 官方市场读取失败：" + (failure.message ?: "请检查网络"),
                )
            }
        }
    }

    fun installCuratedSkill(id: String) {
        if (id !in _state.value.curatedSkills) return
        launchOperation {
            _state.value = _state.value.copy(loading = true, feedback = null, feedbackRes = null)
            try {
                val installedId = localTools.installCuratedSkill(id)
                _state.value = _state.value.copy(
                    loading = false,
                    skills = localTools.installedSkills(),
                    presets = localTools.presetSkills(),
                    createdSkillId = installedId,
                    feedback = "官方精选技能已安装：$installedId")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(loading = false,
                    feedback = "精选技能安装失败：" + (error.message ?: "请检查网络或技能包"))
            }
        }
    }

    /** User-selected GitHub source; no network fetch or execution occurs before installation. */
    fun installGitHubSkill(link: String) {
        val source = link.trim()
        if (!source.startsWith("https://github.com/")) return
        launchOperation {
            _state.value = _state.value.copy(loading = true, feedback = null, feedbackRes = null)
            try {
                val installed = localTools.installGitHubSkill(source)
                _state.value = _state.value.copy(
                    loading = false, skills = localTools.installedSkills(),
                    presets = localTools.presetSkills(), createdSkillId = installed,
                    feedback = "已安装技能：$installed；请查看其运行依赖状态",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.value = _state.value.copy(
                    loading = false, feedback = failure.message ?: "GitHub 技能安装失败",
                )
            }
        }
    }

    /** Network access happens only after the user explicitly asks to check one source. */
    fun checkSkillUpdate(id: String) {
        if (_state.value.skills.none { it.name == id && it.sourceUrl != null }) return
        launchOperation {
            _state.value = _state.value.copy(loading = true, feedback = null, feedbackRes = null)
            try {
                val revision = localTools.checkSkillUpdate(id)
                val previous = _state.value.pendingSkillUpdates.toMutableMap()
                val current = _state.value.upToDateSkills.toMutableSet()
                if (revision != null) {
                    previous[id] = revision
                    current.remove(id)
                } else {
                    previous.remove(id)
                    current.add(id)
                }
                _state.value = _state.value.copy(loading = false,
                    pendingSkillUpdates = previous, upToDateSkills = current,
                    feedback = if (revision == null) "技能已是最新版本" else "发现新版本：${revision.take(12)}")
            } catch (cancelled: CancellationException) {
                _state.value = _state.value.copy(loading = false)
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(loading = false,
                    feedback = "检查技能更新失败：" + (error.message ?: "网络连接异常"))
            }
        }
    }

    fun upgradeSkill(id: String, revision: String) {
        if (_state.value.pendingSkillUpdates[id] != revision) return
        launchOperation {
            _state.value = _state.value.copy(loading = true, feedback = null, feedbackRes = null)
            try {
                localTools.upgradeSkill(id, revision)
                _state.value = _state.value.copy(loading = false,
                    skills = localTools.installedSkills(),
                    pendingSkillUpdates = _state.value.pendingSkillUpdates - id,
                    upToDateSkills = _state.value.upToDateSkills + id,
                    feedback = "已升级技能 $id，上一版本可在技能详情恢复")
            } catch (cancelled: CancellationException) {
                _state.value = _state.value.copy(loading = false)
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(loading = false,
                    feedback = "升级失败：" + (error.message ?: "请重新检查技能版本"))
            }
        }
    }

    fun rollbackSkill(id: String) {
        if (_state.value.skills.none { it.name == id && it.rollbackAvailable }) return
        launchOperation {
            _state.value = _state.value.copy(loading = true, feedback = null, feedbackRes = null)
            try {
                localTools.rollbackSkill(id)
                _state.value = _state.value.copy(loading = false,
                    skills = localTools.installedSkills(),
                    pendingSkillUpdates = _state.value.pendingSkillUpdates - id,
                    upToDateSkills = _state.value.upToDateSkills - id,
                    feedback = "已恢复上一版本：$id")
            } catch (cancelled: CancellationException) {
                _state.value = _state.value.copy(loading = false)
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(loading = false,
                    feedback = "恢复失败：" + (error.message ?: "请检查技能存储"))
            }
        }
    }

    fun installPreset(id: String) = changeSkills { localTools.installPreset(id) }
    fun createSkill(id: String, displayName: String, description: String, instructions: String) =
        changeSkills(createdId = id) { localTools.createSkill(id, displayName, description, instructions) }
    fun importSkill(uri: Uri) {
        launchOperation {
            _state.value = _state.value.copy(loading = true, feedback = null, feedbackRes = null)
            try {
                val id = withContext(Dispatchers.IO) {
                    val resolver = appContext.contentResolver
                    val filename = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                        ?: uri.lastPathSegment?.substringAfterLast('/') ?: ""
                    resolver.openInputStream(uri)?.use { stream ->
                        localTools.importSkillStream(filename, stream)
                    } ?: error(appContext.getString(R.string.skills_import_open_failed))
                }
                _state.value = _state.value.copy(
                    loading = false,
                    skills = localTools.installedSkills(),
                    presets = localTools.presetSkills(),
                    createdSkillId = id,
                    feedback = appContext.getString(R.string.skills_import_success, id),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    feedback = error.message ?: appContext.getString(R.string.skills_import_failed),
                )
            }
        }
    }

    fun shareSkillPackage(id: String) {
        launchOperation {
            _state.value = _state.value.copy(loading = true, feedback = null, feedbackRes = null)
            try {
                val file = withContext(Dispatchers.IO) {
                    val folder = File(appContext.cacheDir, "skill-package-shares")
                    require(!Files.isSymbolicLink(folder.toPath()) &&
                        (folder.isDirectory || folder.mkdirs())) { "无法创建技能分享缓存" }
                    val pending = Files.createTempFile(folder.toPath(), "skill-", ".partial")
                    try {
                        localTools.exportSkillPackageToFile(id, pending.toFile())
                        val target = File(folder, "$id.zip").toPath()
                        try {
                            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING)
                        } catch (_: AtomicMoveNotSupportedException) {
                            Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING)
                        }
                        target.toFile()
                    } finally {
                        Files.deleteIfExists(pending)
                    }
                }
                val uri = FileProvider.getUriForFile(
                    appContext, "${appContext.packageName}.files", file,
                )
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri(file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                appContext.startActivity(Intent.createChooser(send, "分享完整技能包").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                _state.value = _state.value.copy(loading = false,
                    feedback = "技能包已生成，可选择分享目标")
            } catch (cancelled: CancellationException) {
                _state.value = _state.value.copy(loading = false)
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(loading = false,
                    feedback = error.message ?: "技能包分享失败")
            }
        }
    }

    fun removeSkill(id: String) = changeSkills { localTools.removeSkill(id) }

    fun openSkillEditor(id: String) {
        launchOperation {
            _state.value = _state.value.copy(loading = true, skillEditor = null, savedSkillId = null)
            try {
                _state.value = _state.value.copy(
                    loading = false,
                    skillEditor = com.labteto.dshmobile.local.presentation.LocalSkillEditorUiEntry(
                        id, localTools.readSkillDocument(id),
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(loading = false, feedback = error.message)
            }
        }
    }

    fun closeSkillEditor() {
        _state.value = _state.value.copy(skillEditor = null, savedSkillId = null)
    }

    fun saveSkillDocument(id: String, content: String) {
        launchOperation {
            _state.value = _state.value.copy(loading = true, feedback = null, savedSkillId = null)
            try {
                localTools.updateSkillDocument(id, content)
                _state.value = _state.value.copy(
                    loading = false, skillEditor = null, savedSkillId = id,
                    skills = localTools.installedSkills(), presets = localTools.presetSkills(),
                    feedbackRes = R.string.skills_saved,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(loading = false, feedback = error.message)
            }
        }
    }

    fun setSkillModelInvocable(id: String, enabled: Boolean) = changeSkills {
        localTools.setSkillModelInvocable(id, enabled)
    }

    fun connectHttp(serverId: String, endpoint: String, bearer: String = "") {
        launchOperation {
            _state.value = _state.value.copy(loading = true, notice = ToolsNotice.CONNECTING)
            try {
                localTools.connectHttpWithCredential(serverId, endpoint, bearer)
                _state.value = _state.value.copy(
                    loading = false,
                    servers = localTools.servers(),
                    localPlugins = localTools.installedPluginIds(),
                    notice = ToolsNotice.CONNECTED,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    notice = null,
                    feedbackRes = localToolConnectionErrorMessageRes(error),
                )
            }
        }
    }

    fun loginMcp(serverId: String, endpoint: String, clientId: String) {
        mcpLoginJob = viewModelScope.launch { operationGate.run {
            _state.value = _state.value.copy(loading = true, mcpLoginServerId = serverId,
                feedback = "请在浏览器完成 MCP 登录，再返回此处。", feedbackRes = null)
            try {
                localTools.loginMcp(serverId, endpoint, clientId.takeIf(String::isNotBlank))
                _state.value = _state.value.copy(servers = localTools.servers(),
                    localPlugins = localTools.installedPluginIds(), feedback = "MCP 已登录并连接。")
            } catch (cancelled: CancellationException) {
                _state.value = _state.value.copy(feedback = "MCP 登录已取消。")
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(feedback = "MCP 登录未完成。请核对服务地址；不支持自动注册的服务需填写预注册 Client ID。")
            } finally {
                _state.value = _state.value.copy(loading = false, mcpLoginServerId = null)
            }
        } }
    }

    fun cancelMcpLogin() {
        mcpLoginJob?.cancel()
        _state.value.mcpLoginServerId?.let(localTools::cancelMcpLogin)
    }

    fun refreshMcpLogin(serverId: String, endpoint: String) {
        launchOperation {
            _state.value = _state.value.copy(loading = true, feedback = null, feedbackRes = null)
            try {
                localTools.refreshMcpLogin(serverId, endpoint)
                _state.value = _state.value.copy(servers = localTools.servers(), feedback = "MCP 登录已刷新并重新连接。")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.value = _state.value.copy(feedback = "MCP 登录刷新未完成，请重新授权。") }
            finally { _state.value = _state.value.copy(loading = false) }
        }
    }

    fun revokeMcpLogin(serverId: String, endpoint: String) {
        launchOperation {
            _state.value = _state.value.copy(loading = true, feedback = null, feedbackRes = null)
            try {
                val remote = localTools.revokeMcpLogin(serverId, endpoint)
                _state.value = _state.value.copy(servers = localTools.servers(), feedback = if (remote)
                    "MCP 授权已撤销，本机凭据已清除。" else
                    "已断开并清除本机凭据；服务未确认远程撤销，可前往服务账户页检查授权。")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.value = _state.value.copy(feedback = "MCP 授权清理未完成，请重试。") }
            finally { _state.value = _state.value.copy(loading = false) }
        }
    }

    fun connectStdio(serverId: String, command: List<String>, workingDirectory: String?) {
        launchOperation {
            _state.value = _state.value.copy(loading = true, notice = ToolsNotice.CONNECTING)
            try {
                localTools.connectStdio(serverId, command, workingDirectory)
                _state.value = _state.value.copy(
                    loading = false,
                    servers = localTools.servers(),
                    localPlugins = localTools.installedPluginIds(),
                    notice = ToolsNotice.CONNECTED,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    notice = null,
                    feedbackRes = localToolConnectionErrorMessageRes(error),
                )
            }
        }
    }

    fun clearMcpCredential(serverId: String) {
        launchOperation {
            _state.value = _state.value.copy(loading = true, feedback = null)
            try {
                localTools.clearMcpCredential(serverId)
                _state.value = _state.value.copy(
                    loading = false,
                    feedback = "MCP 已断开，本机凭据已清除。",
                    servers = localTools.servers(),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    loading = false, feedbackRes = localToolConnectionErrorMessageRes(error),
                )
            }
        }
    }

    fun configureGitHub(token: String) {
        launchOperation {
            _state.value = _state.value.copy(loading = true, notice = ToolsNotice.CONNECTING)
            try {
                localTools.configureGitHub(token)
                _state.value = _state.value.copy(
                    loading = false,
                    githubConfigured = true,
                    localPlugins = localTools.installedPluginIds(),
                    notice = ToolsNotice.CONNECTED,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    notice = null,
                    feedbackRes = localToolConnectionErrorMessageRes(error),
                )
            }
        }
    }

    fun clearGitHub() {
        launchOperation {
            _state.value = _state.value.copy(loading = true, notice = null)
            try {
                localTools.clearGitHub()
                _state.value = _state.value.copy(
                    loading = false,
                    githubConfigured = false,
                    notice = ToolsNotice.DISCONNECTED,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    notice = null,
                    feedbackRes = localToolConnectionErrorMessageRes(error),
                )
            }
        }
    }

    fun disconnect(serverId: String) {
        launchOperation {
            _state.value = _state.value.copy(loading = true, notice = null)
            try {
                localTools.disconnect(serverId)
                _state.value = _state.value.copy(
                    loading = false,
                    servers = localTools.servers(),
                    localPlugins = localTools.installedPluginIds(),
                    notice = ToolsNotice.DISCONNECTED,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    notice = null,
                    feedbackRes = localToolConnectionErrorMessageRes(error),
                )
            }
        }
    }


}

@Composable
fun ToolsScreen(
    onClose: () -> Unit,
    startAtPlugins: Boolean = false,
    startAtSkills: Boolean = false,
    onUseCapability: ((String) -> Unit)? = null,
    onOpenSettings: (SettingsDestination) -> Unit = {},
    handleRootSystemBack: Boolean = true,
    viewModel: ToolsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val githubBrowse by viewModel.githubBrowse.collectAsStateWithLifecycle()
    val skillFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importSkill(uri)
    }
    // API 37 permission path stays dormant while targetSdk remains 36.
    val localNetworkPermission = "android.permission.ACCESS_LOCAL_NETWORK"
    var pendingLocalNetworkAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var localNetworkPermissionFeedback by remember { mutableStateOf<String?>(null) }
    val localNetworkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingLocalNetworkAction
        pendingLocalNetworkAction = null
        if (granted) {
            localNetworkPermissionFeedback = "局域网访问已授权。"
            action?.invoke()
        } else {
            localNetworkPermissionFeedback = "未获得局域网访问权限；可从系统设置授权后重试。"
        }
    }
    val eligibleForLocalNetworkPermission = Build.VERSION.SDK_INT >= 37 &&
        context.applicationInfo.targetSdkVersion >= 37
    val localNetworkPermissionGranted = ContextCompat.checkSelfPermission(
        context, localNetworkPermission
    ) == PackageManager.PERMISSION_GRANTED
    val connectWithLocalNetworkPermission: (String, () -> Unit) -> Unit = { url, action ->
        if (requiresAndroid17LocalNetworkPermission(
                Build.VERSION.SDK_INT, context.applicationInfo.targetSdkVersion, url
            ) && ContextCompat.checkSelfPermission(
                context, localNetworkPermission
            ) != PackageManager.PERMISSION_GRANTED) {
            pendingLocalNetworkAction = action
            localNetworkLauncher.launch(localNetworkPermission)
        } else {
            action()
        }
    }
    val networkSearchEnabled by viewModel.networkSearchEnabled.collectAsStateWithLifecycle()
    val deepSeekSearchPreferred by viewModel.deepSeekSearchPreferred.collectAsStateWithLifecycle()
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val approvalMode by settingsViewModel.approvalMode.collectAsStateWithLifecycle()
    val autoApprovalEnabled = approvalMode == LocalApprovalMode.AUTO
    val pulse = rememberHapticPulse()
    var confirmAutoApproval by remember { mutableStateOf(false) }
    val toggleAutoApproval: (Boolean) -> Unit = { enabled ->
        pulse()
        if (enabled) confirmAutoApproval = true
        else settingsViewModel.configureApprovalMode(LocalApprovalMode.DEFAULT)
    }
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val toast = rememberSyToast()
    var serverId by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var mcpBearer by remember { mutableStateOf("") }
    var mcpClientId by remember { mutableStateOf("") }
    var stdioCommand by remember { mutableStateOf("") }
    var stdioWorkingDirectory by remember { mutableStateOf("") }
    var githubToken by remember { mutableStateOf("") }
    var showGitHubConfig by remember { mutableStateOf(false) }
    var editGitHubCredentials by remember { mutableStateOf(false) }
    var showGitHubBrowser by remember { mutableStateOf(false) }
    var showExternalConfig by remember { mutableStateOf(false) }
    var showMcpAdvanced by rememberSaveable { mutableStateOf(false) }
    var showMcpStdio by rememberSaveable { mutableStateOf(false) }
    var expandedMcpServer by remember { mutableStateOf<String?>(null) }
    var showAgentSettings by remember { mutableStateOf(false) }
    var showConnectorSettings by rememberSaveable { mutableStateOf(false) }
    var confirmClearGitHub by remember { mutableStateOf(false) }
    // Single page with three tabs. The registered tool catalogue is not user-facing.
    var selectedTab by rememberSaveable(startAtPlugins, startAtSkills) {
        mutableIntStateOf(if (startAtSkills) 1 else 0)
    }
    // Switching tabs must not discard the plugin/skill search and filter context.
    val inventoryTabStates = rememberSaveableStateHolder()
    LaunchedEffect(selectedTab) {
        if (selectedTab == 0 && state.codexPlugins.isEmpty() &&
            !state.codexLoading && state.codexError == null) viewModel.refreshCodexPlugins()
        if (selectedTab in 0..1 && state.curatedSkills.isEmpty() &&
            !state.curatedLoading && state.curatedError == null) viewModel.refreshCuratedSkills()
    }

    val noticeMessage = state.notice?.let { notice ->
        stringResource(
            when (notice) {
                ToolsNotice.LOAD_FAILED -> R.string.tools_load_failed
                ToolsNotice.CONNECTING -> R.string.tools_connecting
                ToolsNotice.CONNECTED -> R.string.tools_connected
                ToolsNotice.CONNECT_FAILED -> R.string.tools_connect_failed
                ToolsNotice.DISCONNECTED -> R.string.tools_disconnected
                ToolsNotice.DISCONNECT_FAILED -> R.string.tools_disconnect_failed
            },
        )
    }

    BackHandler(enabled = showGitHubBrowser || showGitHubConfig || showAgentSettings || showConnectorSettings || handleRootSystemBack) {
        when {
            showGitHubBrowser -> showGitHubBrowser = false
            showGitHubConfig -> {
                showGitHubConfig = false
                editGitHubCredentials = false
                githubToken = ""
            }
            showAgentSettings -> showAgentSettings = false
            showConnectorSettings && selectedTab == 2 -> showConnectorSettings = false
            else -> onClose()
        }
    }
    LaunchedEffect(state.notice, noticeMessage) {
        when (state.notice) {
            ToolsNotice.CONNECTED -> {
                serverId = ""
                endpoint = ""
                mcpBearer = ""
                stdioCommand = ""
                stdioWorkingDirectory = ""
                githubToken = ""
                showGitHubConfig = false
                editGitHubCredentials = false
                showExternalConfig = false
                noticeMessage?.let(toast.second)
            }
            ToolsNotice.LOAD_FAILED,
            ToolsNotice.CONNECT_FAILED,
            ToolsNotice.DISCONNECTED,
            ToolsNotice.DISCONNECT_FAILED -> noticeMessage?.let(toast.second)
            ToolsNotice.CONNECTING, null -> Unit
        }
        state.notice?.takeUnless { it == ToolsNotice.CONNECTING }
            ?.let(viewModel::acknowledgeNotice)
    }

    val feedbackMessage = state.feedback ?: state.feedbackRes?.let { stringResource(it) }
    LaunchedEffect(feedbackMessage) {
        feedbackMessage?.let { message ->
            toast.second(message)
            viewModel.acknowledgeFeedback()
        }
    }

    if (showAgentSettings) {
        val agentSettings by settingsViewModel.localHarnessState.collectAsStateWithLifecycle()
        Surface(Modifier.fillMaxSize(), color = colors.rootSurface()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                SyTopBar(
                    title = stringResource(R.string.advanced_agent_settings),
                    onBack = { showAgentSettings = false },
                    backContentDescription = stringResource(R.string.common_back),
                )
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                        .padding(horizontal = SyMetric.gapSection, vertical = SyMetric.gapLg),
                ) {
                    LocalAgentSettingsCard(agentSettings, settingsViewModel, toast.second)
                }
                SyToastHost(toast)
            }
        }
    } else {
        Box(Modifier.fillMaxSize()) {
            Surface(Modifier.fillMaxSize(), color = colors.rootSurface()) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    SyTopBar(
                        title = stringResource(R.string.tools_title),
                        onBack = onClose,
                        backContentDescription = stringResource(R.string.common_back),
                        actionIcon = FeatherIcons.RefreshCw,
                        actionContentDescription = stringResource(R.string.tools_refresh),
                        actionEnabled = !state.loading,
                        onAction = viewModel::refresh,
                    )
                    CapabilityCenterTabs(selectedTab) { next ->
                        if (next != selectedTab) {
                            selectedTab = next
                            // Re-read disk-installed skills when returning to this tab.
                            if (next == 1) viewModel.refresh()
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        when (selectedTab) {
                            0, 1 -> inventoryTabStates.SaveableStateProvider(selectedTab) {
                                PluginInventoryBrowser(
                                    localIds = state.localPlugins,
                                    activeToolHookIds = state.activeToolHookIds,
                                    connectedServices = state.servers,
                                    skills = state.skills,
                                    presets = state.presets,
                                    curatedSkills = state.curatedSkills,
                                    codexPlugins = state.codexPlugins,
                                    codexLoading = state.codexLoading,
                                    codexError = state.codexError,
                                    onRefreshCodex = viewModel::refreshCodexPlugins,
                                    curatedLoading = state.curatedLoading,
                                    curatedError = state.curatedError,
                                    pendingSkillUpdates = state.pendingSkillUpdates,
                                    upToDateSkills = state.upToDateSkills,
                                    createdSkillId = state.createdSkillId,
                                    skillEditor = state.skillEditor,
                                    savedSkillId = state.savedSkillId,
                                    skillsOnly = selectedTab == 1,
                                    embedded = true,
                                    loading = state.loading,
                                    error = if (state.loadFailed)
                                        stringResource(R.string.tools_load_failed) else null,
                                    onRetry = viewModel::refresh,
                                    onBack = onClose,
                                    onManageConnections = { selectedTab = 2 },
                                    onReturnToChat = onClose,
                                    onUseCapability = onUseCapability,
                                    onInstallPreset = viewModel::installPreset,
                                    onInstallCurated = viewModel::installCuratedSkill,
                                    onRefreshCurated = viewModel::refreshCuratedSkills,
                                    onCreateSkill = viewModel::createSkill,
                                    onImportSkill = { skillFilePicker.launch(arrayOf("*/*")) },
                                     onInstallGitHubSkill = viewModel::installGitHubSkill,
                                    onRemoveSkill = viewModel::removeSkill,
                                    onShareSkillPackage = viewModel::shareSkillPackage,
                                    onCheckSkillUpdate = viewModel::checkSkillUpdate,
                                    onUpgradeSkill = viewModel::upgradeSkill,
                                    onRollbackSkill = viewModel::rollbackSkill,
                                    onOpenSkillEditor = viewModel::openSkillEditor,
                                    onCloseSkillEditor = viewModel::closeSkillEditor,
                                    onSaveSkillDocument = viewModel::saveSkillDocument,
                                    onSetSkillModelInvocable = viewModel::setSkillModelInvocable,
                                )
                            }
                            else -> if (!showConnectorSettings) {
                                ConnectorInventoryBrowser(
                                    servers = state.servers,
                                    githubConfigured = state.githubConfigured,
                                    loading = state.loading,
                                    onGitHub = { showGitHubConfig = true },
                                    onMcp = { showExternalConfig = true },
                                    onOpenSettings = { showConnectorSettings = true },
                                )
                            } else Column(Modifier.fillMaxSize()) {
                                val initialLoading = state.loading &&
                                    state.localPlugins.isEmpty() &&
                                    state.servers.isEmpty() &&
                                    !state.githubConfigured
                                if (initialLoading) {
                                    SyLoadingState(
                                        icon = SyEntryIcons.Tools,
                                        label = stringResource(R.string.tools_loading),
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                    )
                                } else Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .verticalScroll(rememberScrollState())
                                        .padding(horizontal = SyMetric.gapSection, vertical = SyMetric.gapLg),
                                    verticalArrangement = Arrangement.spacedBy(SyMetric.gapLg),
                                ) {
                                    SyButton(
                                        text = "返回连接器目录",
                                        onClick = { showConnectorSettings = false },
                                        variant = SyButtonVariant.Ghost,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    AgentBackendSettings(viewModel)
                                    Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapXs)) {
                                        Text(
                                            stringResource(R.string.cap_center_settings_group),
                                            style = DsType.std14Strong.withReadingWeight(),
                                            color = colors.textSecondary,
                                        )
                                        Text(
                                            stringResource(R.string.cap_center_settings_group_hint),
                                            style = DsType.small13.withReadingWeight(),
                                            color = colors.textSecondary,
                                        )
                                    }
                                    Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm)) {
                                        ToolCapabilityRow(
                                            icon = SyEntryIcons.Search,
                                            title = stringResource(R.string.tools_network_search),
                                            status = stringResource(
                                                if (networkSearchEnabled) R.string.common_enabled else R.string.common_disabled,
                                            ),
                                            state = if (networkSearchEnabled) StateDotState.Done else StateDotState.Idle,
                                            onClick = { pulse(); viewModel.setNetworkSearchEnabled(!networkSearchEnabled) },
                                            trailing = {
                                                SySwitchControl(
                                                    checked = networkSearchEnabled,
                                                    onCheckedChange = { pulse(); viewModel.setNetworkSearchEnabled(it) },
                                                )
                                            },
                                        )
                                        ToolCapabilityRow(
                                            icon = SyEntryIcons.Search,
                                            title = stringResource(R.string.tools_deepseek_search),
                                            status = stringResource(
                                                if (deepSeekSearchPreferred) R.string.common_enabled else R.string.common_disabled,
                                            ),
                                            state = if (deepSeekSearchPreferred) StateDotState.Done else StateDotState.Idle,
                                            onClick = { pulse(); viewModel.setDeepSeekSearchPreferred(!deepSeekSearchPreferred) },
                                            trailing = {
                                                SySwitchControl(
                                                    checked = deepSeekSearchPreferred,
                                                    onCheckedChange = { pulse(); viewModel.setDeepSeekSearchPreferred(it) },
                                                )
                                            },
                                        )
                                        Text(
                                            stringResource(R.string.tools_deepseek_search_hint),
                                            style = DsType.caption11.withReadingWeight(),
                                            color = colors.textSecondary,
                                            modifier = Modifier.padding(horizontal = SyMetric.gapSm),
                                        )
                                        ToolCapabilityRow(
                                            icon = SyEntryIcons.Permissions,
                                            title = stringResource(R.string.cap_center_auto_approval),
                                            status = stringResource(
                                                when (approvalMode) {
                                                    LocalApprovalMode.AUTO -> R.string.common_enabled
                                                    LocalApprovalMode.MANUAL -> R.string.settings_approval_mode_manual
                                                    LocalApprovalMode.DEFAULT -> R.string.common_disabled
                                                },
                                            ),
                                            state = if (autoApprovalEnabled) StateDotState.Done else StateDotState.Idle,
                                            onClick = { toggleAutoApproval(!autoApprovalEnabled) },
                                            trailing = {
                                                SySwitchControl(
                                                    checked = autoApprovalEnabled,
                                                    onCheckedChange = toggleAutoApproval,
                                                )
                                            },
                                        )
                                        ToolCapabilityRow(
                                            icon = SyEntryIcons.Advanced,
                                            title = stringResource(R.string.advanced_agent_settings),
                                            status = stringResource(R.string.tools_capability_available),
                                            state = StateDotState.Done,
                                            onClick = { showAgentSettings = true },
                                        )

                                    }

                                    Text(
                                        stringResource(R.string.cap_center_extensions_group),
                                        style = DsType.std14Strong.withReadingWeight(),
                                        color = colors.textSecondary,
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm)) {
                                        ToolCapabilityRow(
                                            icon = SyEntryIcons.Connections,
                                            title = stringResource(R.string.tools_github_connector),
                                            status = stringResource(
                                                if (state.githubConfigured) R.string.tools_github_configured
                                                else R.string.tools_github_unconfigured,
                                            ),
                                            state = if (state.githubConfigured) StateDotState.Done else StateDotState.Idle,
                                            onClick = { showGitHubConfig = true },
                                        )
                                        ToolCapabilityRow(
                                            icon = SyEntryIcons.Plugins,
                                            title = stringResource(R.string.tools_external_services),
                                            status = state.servers.size.toString(),
                                            state = if (state.servers.isNotEmpty()) StateDotState.Done else StateDotState.Idle,
                                            onClick = { showExternalConfig = true },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            SyToastHost(toast, Modifier.safeDrawingPadding())
        }
    }

    if (showGitHubBrowser) {
        GitHubRepositoryBrowserSheet(
            state = githubBrowse,
            onBack = viewModel::backGitHubBrowse,
            onOpenRepository = viewModel::openGitHubRepository,
            onOpenFolder = viewModel::openGitHubFolder,
            onRefresh = viewModel::refreshGitHubBrowse,
            onOpenFile = { url ->
                try {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: android.content.ActivityNotFoundException) {
                    toast.second("找不到可打开 GitHub 文件的浏览器")
                }
            },
            onDismiss = { showGitHubBrowser = false },
        )
    }

    if (showGitHubConfig) {
        SyResponsiveSheet(
            title = stringResource(R.string.tools_github_connector),
            subtitle = stringResource(R.string.tools_github_connector_hint),
            onDismiss = {
                showGitHubConfig = false
                editGitHubCredentials = false
                githubToken = ""
            },
        ) {
            Text(
                stringResource(R.string.tools_github_capabilities),
                style = DsType.small13.withReadingWeight(),
                color = colors.textSecondary,
            )
            Text(
                stringResource(R.string.tools_github_write_approval),
                style = DsType.caption11.withReadingWeight(),
                color = colors.textTertiary,
            )
            if (state.githubConfigured) {
                SyButton(
                    text = "浏览 GitHub 仓库",
                    onClick = {
                        showGitHubConfig = false
                        editGitHubCredentials = false
                        githubToken = ""
                        showGitHubBrowser = true
                        viewModel.loadGitHubRepositories()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    variant = SyButtonVariant.Ghost,
                )
            }
            if (state.githubConfigured && !editGitHubCredentials) {
                Text(
                    "GitHub 已连接。需要更新或撤销凭据时再展开操作。",
                    style = DsType.small13.withReadingWeight(),
                    color = colors.textSecondary,
                )
                SyButton(
                    text = "更新访问凭据",
                    onClick = { editGitHubCredentials = true },
                    variant = SyButtonVariant.Outline,
                    modifier = Modifier.fillMaxWidth(),
                )
                SyButton(
                    text = stringResource(R.string.tools_github_clear),
                    onClick = { confirmClearGitHub = true },
                    variant = SyButtonVariant.Ghost,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
            SyTextField(
                value = githubToken,
                onValueChange = { githubToken = it.take(4096) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.tools_github_token)) },
                supportingText = { Text(stringResource(R.string.tools_github_token_hint)) },
                enabled = !state.loading,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                shape = DsShapes.row,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
            ) {
                SyButton(
                    text = stringResource(
                        if (state.loading) R.string.tools_processing else R.string.tools_github_save,
                    ),
                    onClick = { viewModel.configureGitHub(githubToken) },
                    enabled = !state.loading && githubToken.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    variant = SyButtonVariant.Primary,
                )
                if (state.githubConfigured) {
                    SyButton(
                        text = stringResource(R.string.tools_github_clear),
                        onClick = { confirmClearGitHub = true },
                        enabled = !state.loading,
                        variant = SyButtonVariant.Ghost,
                    )
                }
            }
            }
        }
    }

    if (showExternalConfig) {
        SyResponsiveSheet(
            title = stringResource(R.string.tools_external_services),
            subtitle = stringResource(R.string.tools_external_services_hint),
            onDismiss = {
                pendingLocalNetworkAction = null
                viewModel.cancelMcpLogin()
                showExternalConfig = false
                showMcpAdvanced = false
                showMcpStdio = false
                mcpBearer = ""
                mcpClientId = ""
            },
        ) {
            if (state.servers.isEmpty()) {
                Text(
                    stringResource(R.string.tools_no_services),
                    style = DsType.small13.withReadingWeight(),
                    color = colors.textTertiary,
                )
            } else {
                state.servers.forEach { server ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = SyMetric.gapSm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
                    ) {
                        StateDot(StateDotState.Done)
                        Column(Modifier.weight(1f)) {
                            Text(
                                server.id,
                                style = DsType.std14Strong.withReadingWeight(),
                                color = colors.text,
                            )
                            Text(
                                stringResource(
                                    R.string.tools_server_summary,
                                    server.transport.uppercase(),
                                    server.tools.size,
                                ),
                                style = DsType.caption11.withReadingWeight(),
                                color = colors.textTertiary,
                            )
                        }
                        SyButton(
                            text = stringResource(R.string.cap_center_view_tools),
                            onClick = { expandedMcpServer = if (expandedMcpServer == server.id) null else server.id },
                            enabled = server.tools.isNotEmpty(),
                            size = SyButtonSize.Small,
                            variant = SyButtonVariant.Ghost,
                        )
                        SyButton(
                            text = stringResource(R.string.tools_disconnect),
                            onClick = { viewModel.disconnect(server.id) },
                            enabled = !state.loading,
                            size = SyButtonSize.Small,
                            variant = SyButtonVariant.Ghost,
                        )
                    }
                    if (expandedMcpServer == server.id) {
                        Text(
                            server.tools.joinToString(" · "),
                            style = DsType.caption11.withReadingWeight(),
                            color = colors.textSecondary,
                            modifier = Modifier.padding(bottom = SyMetric.gapMd),
                        )
                    }
                }
            }

            SyTextField(
                value = serverId,
                onValueChange = { serverId = it.take(24) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.tools_server_name)) },
                enabled = !state.loading,
                singleLine = true,
                shape = DsShapes.row,
            )
            SyTextField(
                value = endpoint,
                onValueChange = { endpoint = it.take(2000) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.tools_endpoint)) },
                supportingText = { Text(stringResource(R.string.tools_endpoint_hint)) },
                enabled = !state.loading,
                singleLine = true,
                shape = DsShapes.row,
            )
            if (eligibleForLocalNetworkPermission && !localNetworkPermissionGranted) {
                Text(
                    "Android 17：如私有域名实际连接局域网，可先授权「附近设备」。",
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.textSecondary,
                )
                SyButton(
                    text = "允许连接局域网服务",
                    onClick = { localNetworkLauncher.launch(localNetworkPermission) },
                    enabled = !state.loading,
                    variant = SyButtonVariant.Ghost,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            localNetworkPermissionFeedback?.let { message ->
                Text(message, style = DsType.caption11.withReadingWeight(), color = colors.textSecondary)
            }
            SyButton(
                text = if (showMcpAdvanced) "收起高级连接选项" else "高级连接选项",
                onClick = { showMcpAdvanced = !showMcpAdvanced },
                variant = SyButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth().testTag("mcp_advanced_toggle"),
            )
            if (showMcpAdvanced) {
            SyTextField(
                value = mcpClientId,
                onValueChange = { mcpClientId = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("OAuth Client ID（可选）") },
                supportingText = { Text("可填写预注册 ID 或客户端元数据 HTTPS 地址；留空尝试服务支持的自动注册。") },
                enabled = !state.loading, singleLine = true, shape = DsShapes.row,
            )
            }
            SyButton(
                text = if (state.mcpLoginServerId != null) "等待浏览器授权…" else "浏览器登录并连接",
                onClick = {
                    val id = serverId.trim()
                    val url = endpoint.trim()
                    val client = if (showMcpAdvanced) mcpClientId.trim() else ""
                    connectWithLocalNetworkPermission(url) { viewModel.loginMcp(id, url, client) }
                },
                enabled = !state.loading && serverId.isNotBlank() && endpoint.isNotBlank(),
                variant = SyButtonVariant.Outline, modifier = Modifier.fillMaxWidth(),
            )
            if (state.mcpLoginServerId != null) {
                SyButton(text = "取消登录", onClick = viewModel::cancelMcpLogin,
                    variant = SyButtonVariant.Ghost, modifier = Modifier.fillMaxWidth())
            }
            if (showMcpAdvanced) {
            Row(horizontalArrangement = Arrangement.spacedBy(SyMetric.gapSm)) {
                SyButton(text = "刷新登录", onClick = {
                    val id = serverId.trim()
                    val url = endpoint.trim()
                    connectWithLocalNetworkPermission(url) { viewModel.refreshMcpLogin(id, url) }
                },
                    enabled = !state.loading && serverId.isNotBlank() && endpoint.isNotBlank(),
                    variant = SyButtonVariant.Ghost, modifier = Modifier.weight(1f))
                SyButton(text = "撤销授权", onClick = {
                    val id = serverId.trim()
                    val url = endpoint.trim()
                    connectWithLocalNetworkPermission(url) { viewModel.revokeMcpLogin(id, url) }
                },
                    enabled = !state.loading && serverId.isNotBlank() && endpoint.isNotBlank(),
                    variant = SyButtonVariant.Ghost, modifier = Modifier.weight(1f))
            }
            }
            if (showMcpAdvanced) {
            SyTextField(
                value = mcpBearer,
                onValueChange = { mcpBearer = it.take(4096) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("MCP Bearer Token（可选）") },
                supportingText = { Text("加密保存、绑定该服务地址；留空使用已有凭据。远程认证需使用 HTTPS。") },
                enabled = !state.loading,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                shape = DsShapes.row,
            )
            }
            SyButton(
                text = stringResource(
                    if (state.loading) R.string.tools_processing else R.string.tools_connect,
                ),
                onClick = {
                    val id = serverId.trim()
                    val url = endpoint.trim()
                    val bearer = if (showMcpAdvanced) mcpBearer else ""
                    connectWithLocalNetworkPermission(url) { viewModel.connectHttp(id, url, bearer) }
                },
                enabled = !state.loading && serverId.isNotBlank() && endpoint.isNotBlank(),
                variant = SyButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth(),
                icon = SyEntryIcons.Connections,
            )
            if (showMcpAdvanced) SyButton(
                text = "清除该 MCP 服务的认证信息",
                onClick = { viewModel.clearMcpCredential(serverId.trim()) },
                enabled = !state.loading && serverId.isNotBlank(),
                variant = SyButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
            SyButton(
                text = if (showMcpStdio) "收起本机命令连接" else "通过本机命令连接（高级）",
                onClick = { showMcpStdio = !showMcpStdio },
                variant = SyButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth().testTag("mcp_stdio_toggle"),
            )
            if (showMcpStdio) {
            Text(
                stringResource(R.string.tools_stdio_title),
                style = DsType.std14Strong.withReadingWeight(),
                color = colors.text,
            )
            SyTextField(
                value = stdioCommand,
                onValueChange = { stdioCommand = it.take(4000) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.tools_stdio_command)) },
                supportingText = { Text(stringResource(R.string.tools_stdio_command_hint)) },
                enabled = !state.loading,
                minLines = 2,
                maxLines = 6,
                shape = DsShapes.row,
            )
            SyTextField(
                value = stdioWorkingDirectory,
                onValueChange = { stdioWorkingDirectory = it.take(1000) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.tools_stdio_working_directory)) },
                supportingText = { Text(stringResource(R.string.tools_stdio_working_directory_hint)) },
                enabled = !state.loading,
                singleLine = true,
                shape = DsShapes.row,
            )
            val stdioArgs = stdioCommand.lineSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .toList()
            SyButton(
                text = stringResource(
                    if (state.loading) R.string.tools_processing else R.string.tools_stdio_connect,
                ),
                onClick = {
                    viewModel.connectStdio(
                        serverId.trim(),
                        stdioArgs,
                        stdioWorkingDirectory.trim().takeIf(String::isNotBlank),
                    )
                },
                enabled = !state.loading && serverId.isNotBlank() && stdioArgs.isNotEmpty(),
                variant = SyButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth(),
                icon = SyEntryIcons.Tools,
            )
            }
        }
    }

    if (confirmAutoApproval) {
        SyDialog(
            title = stringResource(R.string.cap_center_auto_approval_confirm_title),
            onDismiss = { confirmAutoApproval = false },
        ) {
            Text(
                stringResource(R.string.cap_center_auto_approval_confirm_body),
                style = DsType.std14.withReadingWeight(),
                color = colors.textSecondary,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SyButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { confirmAutoApproval = false },
                    variant = SyButtonVariant.Ghost,
                )
                SyButton(
                    text = stringResource(R.string.cap_center_auto_approval_confirm_action),
                    onClick = {
                        confirmAutoApproval = false
                        settingsViewModel.configureApprovalMode(LocalApprovalMode.AUTO)
                    },
                    variant = SyButtonVariant.Danger,
                )
            }
        }
    }

    if (confirmClearGitHub) {
        SyDialog(
            title = stringResource(R.string.tools_github_clear_confirm_title),
            onDismiss = { confirmClearGitHub = false },
        ) {
            Text(
                stringResource(R.string.tools_github_clear_confirm_body),
                style = DsType.std14.withReadingWeight(),
                color = colors.textSecondary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                SyButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { confirmClearGitHub = false },
                    enabled = !state.loading,
                    variant = SyButtonVariant.Ghost,
                )
                SyButton(
                    text = stringResource(R.string.tools_github_clear),
                    onClick = {
                        confirmClearGitHub = false
                        viewModel.clearGitHub()
                    },
                    enabled = !state.loading,
                    variant = SyButtonVariant.Danger,
                )
            }
        }
    }
}

/** All three user-facing sections stay in the same screen and navigation entry. */
@Composable
internal fun CapabilityCenterTabs(selectedTab: Int, onSelect: (Int) -> Unit) {
    SySegmented(
        segments = listOf(
            SySegment("plugins", stringResource(R.string.tools_catalog_title)),
            SySegment("skills", stringResource(R.string.skills_title)),
            SySegment("connectors", "连接器"),
        ),
        selectedKey = when (selectedTab) {
            1 -> "skills"
            2 -> "connectors"
            else -> "plugins"
        },
        onSelect = { key ->
            onSelect(when (key) {
                "skills" -> 1
                "connectors" -> 2
                else -> 0
            })
        },
        role = Role.Tab,
        hapticOnChange = true,
        stretch = true,
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = SyMetric.gapSection, vertical = SyMetric.gapSm)
            .testTag("capability-center-tabs"),
    )
}

@Composable
private fun ToolCapabilityRow(
    title: String,
    status: String,
    state: StateDotState,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        shape = DsShapes.row,
        color = colors.materialL2,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = SyMetric.gapMd, vertical = SyMetric.gapSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
        ) {
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                icon?.let { com.shenyan.designsystem.components.SyIconBox(icon = it) }
            }
            BoxWithConstraints(Modifier.weight(1f)) {
                val stackedStatus = maxWidth < 200.dp || LocalDensity.current.fontScale > 1.15f
                Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapXs)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
                    ) {
                        Text(
                            title,
                            modifier = Modifier.weight(1f),
                            style = DsType.std14Strong.withReadingWeight(),
                            color = colors.text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!stackedStatus) ToolCapabilityStatus(status, state)
                    }
                    if (stackedStatus) ToolCapabilityStatus(status, state)
                }
            }
            if (trailing != null) {
                trailing()
            } else if (onClick != null) {
                Icon(
                    FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolCapabilityStatus(status: String, state: StateDotState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
    ) {
        StateDot(state, size = 7.dp)
        Text(
            status,
            style = DsType.xsmall12.withReadingWeight(),
            color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun capabilityStateLabel(available: Boolean): String =
    stringResource(
        if (available) R.string.tools_capability_available
        else R.string.tools_capability_unavailable,
    )
