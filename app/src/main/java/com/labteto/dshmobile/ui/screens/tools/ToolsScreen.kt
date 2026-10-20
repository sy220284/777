package com.labteto.dshmobile.ui.screens.tools

import com.shenyan.designsystem.foundation.SyMetric

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
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
    val servers: List<McpServerSnapshot> = emptyList(),
    val githubConfigured: Boolean = false,
    val localPlugins: List<String> = emptyList(),
    val skills: List<com.labteto.dshmobile.local.presentation.LocalSkillUiEntry> = emptyList(),
    val presets: List<com.labteto.dshmobile.local.presentation.LocalPresetSkillUiEntry> = emptyList(),
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
    val networkSearchEnabled: StateFlow<Boolean> = networkSearchSettings.enabled

    fun setNetworkSearchEnabled(enabled: Boolean) = networkSearchSettings.setEnabled(enabled)
    private val operationGate = ToolsOperationGate()
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
                    skills = skills,
                    presets = presets,
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

    fun installPreset(id: String) = changeSkills { localTools.installPreset(id) }
    fun createSkill(id: String, displayName: String, description: String, instructions: String) =
        changeSkills(createdId = id) { localTools.createSkill(id, displayName, description, instructions) }
    fun importSkill(uri: Uri) {
        launchOperation {
            _state.value = _state.value.copy(loading = true, feedback = null, feedbackRes = null)
            try {
                val (filename, bytes) = withContext(Dispatchers.IO) {
                    val resolver = appContext.contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: ""
                    val input = resolver.openInputStream(uri) ?: error(appContext.getString(R.string.skills_import_open_failed))
                    val output = ByteArrayOutputStream()
                    input.use { stream ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 8 * 1024 * 1024) { appContext.getString(R.string.skills_import_too_large) }
                            output.write(buffer, 0, count)
                        }
                    }
                    name to output.toByteArray()
                }
                val id = localTools.importSkill(filename, bytes)
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

    fun connectHttp(serverId: String, endpoint: String) {
        launchOperation {
            _state.value = _state.value.copy(loading = true, notice = ToolsNotice.CONNECTING)
            try {
                localTools.connectHttp(serverId, endpoint)
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
    val networkSearchEnabled by viewModel.networkSearchEnabled.collectAsStateWithLifecycle()
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
    var stdioCommand by remember { mutableStateOf("") }
    var stdioWorkingDirectory by remember { mutableStateOf("") }
    var githubToken by remember { mutableStateOf("") }
    var showGitHubConfig by remember { mutableStateOf(false) }
    var showGitHubBrowser by remember { mutableStateOf(false) }
    var showExternalConfig by remember { mutableStateOf(false) }
    var expandedMcpServer by remember { mutableStateOf<String?>(null) }
    var showAgentSettings by remember { mutableStateOf(false) }
    var confirmClearGitHub by remember { mutableStateOf(false) }
    // Single page with three tabs. The registered tool catalogue is not user-facing.
    var selectedTab by rememberSaveable(startAtPlugins, startAtSkills) {
        mutableIntStateOf(if (startAtSkills) 1 else 0)
    }
    // Switching tabs must not discard the plugin/skill search and filter context.
    val inventoryTabStates = rememberSaveableStateHolder()

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

    BackHandler(enabled = showGitHubBrowser || showGitHubConfig || showAgentSettings || handleRootSystemBack) {
        when {
            showGitHubBrowser -> showGitHubBrowser = false
            showGitHubConfig -> showGitHubConfig = false
            showAgentSettings -> showAgentSettings = false
            else -> onClose()
        }
    }
    LaunchedEffect(state.notice, noticeMessage) {
        when (state.notice) {
            ToolsNotice.CONNECTED -> {
                serverId = ""
                endpoint = ""
                stdioCommand = ""
                stdioWorkingDirectory = ""
                githubToken = ""
                showGitHubConfig = false
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
                                    connectedServices = state.servers,
                                    skills = state.skills,
                                    presets = state.presets,
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
                                    onCreateSkill = viewModel::createSkill,
                                    onImportSkill = { skillFilePicker.launch(arrayOf("*/*")) },
                                    onRemoveSkill = viewModel::removeSkill,
                                    onOpenSkillEditor = viewModel::openSkillEditor,
                                    onCloseSkillEditor = viewModel::closeSkillEditor,
                                    onSaveSkillDocument = viewModel::saveSkillDocument,
                                    onSetSkillModelInvocable = viewModel::setSkillModelInvocable,
                                )
                            }
                            else -> Column(Modifier.fillMaxSize()) {
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
                                            iconPainter = painterResource(R.drawable.ic_ui_plugin),
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
            onDismiss = { showGitHubConfig = false },
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
                        showGitHubBrowser = true
                        viewModel.loadGitHubRepositories()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    variant = SyButtonVariant.Ghost,
                )
            }
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

    if (showExternalConfig) {
        SyResponsiveSheet(
            title = stringResource(R.string.tools_external_services),
            subtitle = stringResource(R.string.tools_external_services_hint),
            onDismiss = { showExternalConfig = false },
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
            SyButton(
                text = stringResource(
                    if (state.loading) R.string.tools_processing else R.string.tools_connect,
                ),
                onClick = {
                    viewModel.connectHttp(serverId.trim(), endpoint.trim())
                },
                enabled = !state.loading && serverId.isNotBlank() && endpoint.isNotBlank(),
                variant = SyButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth(),
                icon = SyEntryIcons.Connections,
            )
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
            SySegment("permissions", stringResource(R.string.settings_page_permissions)),
        ),
        selectedKey = when (selectedTab) {
            1 -> "skills"
            2 -> "permissions"
            else -> "plugins"
        },
        onSelect = { key ->
            onSelect(when (key) {
                "skills" -> 1
                "permissions" -> 2
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
    iconPainter: Painter? = null,
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
                when {
                    iconPainter != null -> Icon(
                        painter = iconPainter,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                    icon != null -> com.shenyan.designsystem.components.SyIconBox(icon = icon)
                }
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
