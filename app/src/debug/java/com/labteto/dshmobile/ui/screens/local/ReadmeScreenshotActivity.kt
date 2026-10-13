package com.labteto.dshmobile.ui.screens.local


import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.chat.CharacterBehaviorTuning
import com.labteto.dshmobile.local.chat.CharacterEvolutionState
import com.labteto.dshmobile.local.model.DeepSeekUsageSnapshot
import com.labteto.dshmobile.local.presentation.LocalWorkUiState
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalSessionSummary
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.presentation.LocalConversationSurfaceState
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.local.work.LocalGoal
import com.labteto.dshmobile.local.work.LocalTodoItem
import com.labteto.dshmobile.ui.shenyan.SyComposerAction
import com.labteto.dshmobile.ui.shenyan.SyComposerField
import com.labteto.dshmobile.ui.shenyan.SyConversationComposer
import com.labteto.dshmobile.ui.screens.settings.UsageCalculationPage
import com.labteto.dshmobile.local.model.MiniMaxProviderUsage
import com.labteto.dshmobile.ui.screens.settings.MiniMaxQuotaCard
import com.labteto.dshmobile.ui.shenyan.SyTopBar
import com.labteto.dshmobile.ui.theme.rootSurface
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.ui.theme.ThemePreference

/**
 * Debug-only README screenshot host.
 *
 * The CI runner launches one deterministic production-component surface at a time, waits for the
 * READY log emitted after multiple rendered frames, and captures the device framebuffer directly.
 * No screenshot bytes pass through app-private storage.
 */
class ReadmeScreenshotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = intent.getBooleanExtra("readme_dark", false)
        val matte = intent.getBooleanExtra("readme_matte", false)
        val background = intent.getStringExtra("readme_background").orEmpty()
        val wallpaperPath = if (background.isBlank()) null else {
            val bitmap = android.graphics.Bitmap.createBitmap(120, 240, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint()
            for (y in 0 until 240 step 20) for (x in 0 until 120 step 20) {
                paint.color = when (background) {
                    "purple" -> android.graphics.Color.rgb(100, 26, 160)
                    "forest" -> android.graphics.Color.rgb(19, 107, 81)
                    else -> if ((x / 20 + y / 20) % 2 == 0) android.graphics.Color.WHITE else android.graphics.Color.rgb(22, 77, 146)
                }
                canvas.drawRect(x.toFloat(), y.toFloat(), x + 20f, y + 20f, paint)
            }
            java.io.File(cacheDir, "material-quality-wallpaper.png").also { file ->
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }.absolutePath
        }
        val bars = if (dark || matte) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        val screen = intent.getStringExtra(EXTRA_SCREEN).orEmpty()
        require(screen in SUPPORTED_SCREENS) { "未知 README 截图页面：$screen" }

        setContent {
            DshTheme(preference = if (matte) ThemePreference.MATTE_BLACK else if (dark) ThemePreference.DARK else ThemePreference.LIGHT,
                backgroundPath = wallpaperPath) {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.shenyan.designsystem.foundation.LocalSyReduceMotion provides true,
                    com.labteto.dshmobile.ui.shenyan.LocalSyReducedMotion provides true,
                ) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        ReadmeScreenshotSurface(screen)
                        ScreenshotReadySignal(screen, wallpaperPath != null)
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_SCREEN = "readme_screen"
        const val LOG_TAG = "ReadmeScreenshot"
        val SUPPORTED_SCREENS = setOf(
            "material-quality",
            "navigation",
            "chat",
            "work",
            "character-tuning",
            "usage",
            "quota",
            "design-work-goal", "design-work-plan", "design-work-decision", "design-work-execution", "design-work-outputs",
            "design-persona-0", "design-persona-1", "design-persona-2", "design-persona-3", "design-gallery", "design-capability",
        )
    }
}

@Composable
private fun ScreenshotReadySignal(screen: String, expectsWallpaper: Boolean) {
    val wallpaperReady = com.labteto.dshmobile.ui.theme.LocalAppBackgroundState.current.hasImage
    LaunchedEffect(screen, wallpaperReady) {
        if (expectsWallpaper && !wallpaperReady) return@LaunchedEffect
        repeat(3) { withFrameNanos { } }
        Log.i(ReadmeScreenshotActivity.LOG_TAG, "READY:$screen")
    }
}

@Composable
private fun ReadmeScreenshotSurface(screen: String) {
    if (screen.startsWith("design-")) { FluidDesignScreenshot(screen.removePrefix("design-")); return }
    when (screen) {
        "material-quality" -> MaterialQualityScreenshot()
        "navigation" -> NavigationScreenshot()
        "chat" -> ChatScreenshot()
        "work" -> FluidDesignScreenshot("work-execution")
        "character-tuning" -> CharacterTuningScreenshot()
        "usage" -> UsageScreenshot()
        "quota" -> QuotaScreenshot()
    }
}

@Composable
private fun NavigationScreenshot() {
    Box(
        Modifier
            .fillMaxSize()
            .background(DsTheme.colors.rootSurface()),
    ) {
        LocalModeDrawer(
            currentSessionId = "chat-current",
            sessions = listOf(
                LocalSessionSummary(
                    id = "chat-current",
                    title = "和绫华的日常",
                    updatedAt = 1_799_999_999_000L,
                    usageMode = LocalUsageMode.CHAT,
                    summaryPreview = "今天想去哪里走走？",
                ),
                LocalSessionSummary(
                    id = "chat-2",
                    title = "故事线 · 稻妻",
                    updatedAt = 1_799_996_399_000L,
                    usageMode = LocalUsageMode.CHAT,
                    summaryPreview = "雨停之后，街上慢慢热闹起来。",
                ),
            ),
            gallery = emptyList(),
            usageMode = LocalUsageMode.CHAT,
            modeSwitchEnabled = true,
            drawerOpen = true,
            pinnedSessionIds = setOf("chat-current"),
            sessionTitleOverrides = emptyMap(),
            currentGalleryId = null,
            currentGalleryStoryId = null,
            currentPersonaName = "神里绫华",
            currentPersonaIdentity = "白鹭公主",
            workModelLabel = "MiniMax-M3",
            groupChatEnabled = false,
            running = false,
            onUsageModeChange = {},
            onNewSession = {},
            onSwitchSession = {},
            onDeleteSessions = { 0 },
            onRenameSession = { _, _ -> true },
            onTogglePinSession = {},
            onWorkspaceFiles = {},
            searchTasks = emptyList(),
            loadSearchFiles = { emptyList() },
            onSearchTask = {},
            onSearchFile = {},
            onOpenRunCenter = {},
            onProjects = {},
            onOpenGroupChat = {},
            onOpenPersonaGallery = {},
            onOpenPersonaSearchResult = {},
            onOpenDiary = {},
            onTasks = {},
            onTools = {},
            onSettings = {},
            onMiniMaxQuota = {},
        )
    }
}

@Composable
private fun ChatScreenshot() {
    Column(
        Modifier
            .fillMaxSize()
            .background(DsTheme.colors.rootSurface())
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        ChatSurfaceHeader(
            personaName = "神里绫华",
            portraitPath = "",
            secondary = "延续对话",
            groupEnabled = false,
            groupMembers = emptyList(),
            activeSpeakerName = null,
            running = false,
            behaviorTuningCustomized = true,
            onContextClick = {},
            onOpenCharacterTuning = {},
            onExitGroupChat = {},
            onNewSession = {},
            sessionPinned = true,
            onTogglePin = {},
            onRenameSession = {},
            onDeleteSession = {},
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            LocalMessageRow(
                message = LocalHarnessMessage(
                    id = "u1",
                    role = "user",
                    content = "今天难得清闲，陪我出去走走？",
                    createdAt = 1L,
                ),
                chatMode = true,
                groupMode = false,
                canEdit = true,
                canRegenerate = false,
                canSelectVariant = false,
                branchInfo = null,
                onEdit = {},
                onSelectVariant = { _, _ -> true },
                onRegenerate = { true },
            )
            LocalMessageRow(
                message = LocalHarnessMessage(
                    id = "a1",
                    role = "assistant",
                    content = "好呀。刚才还在想，天气这么好，一直待在屋里反倒可惜了。\n\n我们慢慢走，不赶时间。",
                    createdAt = 2L,
                ),
                chatMode = true,
                groupMode = false,
                canEdit = false,
                canRegenerate = true,
                canSelectVariant = false,
                branchInfo = null,
                onEdit = {},
                onSelectVariant = { _, _ -> true },
                onRegenerate = { true },
            )
        }
        // Render the exact shipping conversation composer with deterministic read-only
        // sample state; do not compare the deprecated generic SyConversationComposer.
        LocalConversationComposer(
            state = LocalConversationSurfaceState(
                loading = false,
                configured = true,
                sessionId = "design-chat",
                usageMode = LocalUsageMode.CHAT,
                chatPersona = PersonaProfile(id = "design-persona", name = "神里绫华"),
            ),
            activeModelProfile = null,
            input = "",
            attachments = emptyList(),
            onInputChange = {},
            onRemoveAttachment = {},
            onClearAttachments = {},
            onOpenAttachmentPicker = {},
            onShowReplySuggestions = {},
            onGenerateReplySuggestions = { false },
            onSend = { _, _ -> LocalSendResult.Empty },
            onStop = {},
            onPlanModeChange = {},
        )
    }
}

@Composable
private fun WorkScreenshot() {
    Column(
        Modifier
            .fillMaxSize()
            .background(DsTheme.colors.rootSurface())
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        WorkSurfaceHeader(
            modelLabel = "GPT-5.6 Sol",
            configured = true,
            running = true,
            onModelClick = {},
            onOpenRunCenter = {},
            onNewSession = {},
            sessionPinned = false,
            onTogglePin = {},
            onRenameSession = {},
            onDeleteSession = {},
        )
        Spacer(Modifier.padding(top = 10.dp))
        LocalMessageRow(
            message = LocalHarnessMessage(
                id = "work-process",
                role = "reasoning",
                content = "正在检查模型路由、会话恢复和工具执行链…",
                createdAt = 3L,
            ),
            chatMode = false,
            groupMode = false,
            canEdit = false,
            canRegenerate = false,
            canSelectVariant = false,
            branchInfo = null,
            onEdit = {},
            onSelectVariant = { _, _ -> true },
            onRegenerate = { true },
        )
        Spacer(Modifier.padding(top = 10.dp))
        ExecutionStatusCard(
            state = LocalWorkUiState(
                sessionId = "work-current",
                running = true,
                goal = LocalGoal("完成全量代码审计并修复发现的问题"),
                plan = listOf(
                    "检查关键调用链与模型路由",
                    "定位并修复高风险问题",
                    "运行回归验证并复查结果",
                ),
                todos = listOf(
                    LocalTodoItem("模型与账户身份隔离", "completed"),
                    LocalTodoItem("工具与恢复路径复核", "in_progress"),
                    LocalTodoItem("最终回归验证", "pending"),
                ),
                activeAgents = 2,
                maxAgents = 4,
                activeTerminals = 1,
                maxTerminals = 2,
                contextChars = 18_420,
                contextBudgetChars = 64_000,
            ),
            onJobOutput = { "" },
            onStopJob = { "" },
            onSendAgentMessage = { _, _ ->
                LocalWorkUiActionResult(false, "")
            },
            onOpenResults = {},
        )
        Spacer(Modifier.weight(1f))
        SyConversationComposer {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SyComposerAction(
                    icon = Icons.Outlined.AttachFile,
                    contentDescription = "添加附件",
                    onClick = {},
                )
                SyComposerField(
                    value = "",
                    onValueChange = {},
                    placeholder = "继续交代任务…",
                    modifier = Modifier.weight(1f),
                )
                SyComposerAction(
                    icon = Icons.Filled.ArrowUpward,
                    contentDescription = "发送",
                    onClick = {},
                    tint = DsTheme.colors.accent,
                )
            }
        }
    }
}

@Composable
private fun CharacterTuningScreenshot() {
    Box(
        Modifier
            .fillMaxSize()
            .background(DsTheme.colors.rootSurface()),
    ) {
        CharacterBehaviorTuningDialog(
            personaName = "神里绫华",
            portraitPath = "",
            relationshipState = "亲密",
            mood = "放松",
            evolution = CharacterEvolutionState(),
            initial = CharacterBehaviorTuning(),
            onSave = { Result.success(Unit) },
            onDismiss = {},
        )
    }
}

@Composable
private fun UsageScreenshot() {
    Column(
        Modifier
            .fillMaxSize()
            .background(DsTheme.colors.rootSurface())
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        UsageCalculationPage(
            usage = DeepSeekUsageSnapshot(
                inputTokens = 126_800,
                cacheHitTokens = 71_400,
                cacheMissTokens = 55_400,
                outputTokens = 18_600,
                reasoningTokens = 9_200,
                requestCount = 46,
                estimatedCostCny = 3.2846,
                updatedAt = 1_799_999_999_000L,
            ),
            onOpenPricing = {},
        )
    }
}

/** Deterministic fixture data for visual QA only; production queries the provider. */
@Composable
private fun QuotaScreenshot() {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SyTopBar(title = "MiniMax 套餐额度", onBack = {}, backContentDescription = "返回")
        MiniMaxQuotaCard(MiniMaxProviderUsage.ModelQuota(
            name = "general",
            interval = MiniMaxProviderUsage.Window(null, null, 83.0),
            weekly = MiniMaxProviderUsage.Window(null, null, 94.0),
            intervalResetMillis = 13_080_000L,
            weeklyResetMillis = 579_600_000L,
        ))
    }
}
