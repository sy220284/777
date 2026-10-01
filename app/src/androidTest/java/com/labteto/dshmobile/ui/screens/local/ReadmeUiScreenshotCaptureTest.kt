package com.labteto.dshmobile.ui.screens.local

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.local.DeepSeekUsageSnapshot
import com.labteto.dshmobile.local.LocalGoal
import com.labteto.dshmobile.local.LocalHarnessMessage
import com.labteto.dshmobile.local.LocalSessionSummary
import com.labteto.dshmobile.local.LocalTodoItem
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.chat.CharacterBehaviorTuning
import com.labteto.dshmobile.local.chat.CharacterEvolutionState
import com.labteto.dshmobile.local.presentation.LocalWorkUiState
import com.labteto.dshmobile.ui.components.DsComposerAction
import com.labteto.dshmobile.ui.components.DsComposerField
import com.labteto.dshmobile.ui.components.DsConversationComposer
import com.labteto.dshmobile.ui.screens.settings.UsageCalculationPage
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.ui.theme.ThemePreference
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test

/**
 * Temporary documentation capture harness.
 *
 * Every screenshot below is composed from the same production components used by the app. The
 * capture workflow removes this test after the PNGs are committed, so README assets never depend
 * on a parallel mock UI.
 */
class ReadmeUiScreenshotCaptureTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun captureNavigation() {
        compose.setContent {
            DshTheme(preference = ThemePreference.LIGHT) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(DsTheme.colors.bgBase),
                ) {
                    LocalModeDrawer(
                        currentSessionId = "chat-current",
                        sessions = listOf(
                            LocalSessionSummary(
                                id = "chat-current",
                                title = "和绫华的日常",
                                updatedAt = System.currentTimeMillis(),
                                usageMode = LocalUsageMode.CHAT,
                                summaryPreview = "今天想去哪里走走？",
                            ),
                            LocalSessionSummary(
                                id = "chat-2",
                                title = "故事线 · 稻妻",
                                updatedAt = System.currentTimeMillis() - 3_600_000L,
                                usageMode = LocalUsageMode.CHAT,
                                summaryPreview = "雨停之后，街上慢慢热闹起来。",
                            ),
                        ),
                        gallery = emptyList(),
                        usageMode = LocalUsageMode.CHAT,
                        modeSwitchEnabled = true,
                        pinnedSessionIds = setOf("chat-current"),
                        sessionTitleOverrides = emptyMap(),
                        onUsageModeChange = {},
                        onNewSession = {},
                        onRemote = {},
                        onSwitchSession = {},
                        onDeleteSessions = {},
                        onWorkspaceFiles = {},
                        onOpenRunCenter = {},
                        galleryCount = 4,
                        groupMemberCount = 3,
                        onOpenGroupChat = {},
                        onOpenPersonaGallery = {},
                        onTasks = {},
                        onTools = {},
                        onSettings = {},
                    )
                }
            }
        }
        save("navigation.png")
    }

    @Test
    fun captureChat() {
        compose.setContent {
            DshTheme(preference = ThemePreference.LIGHT) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(DsTheme.colors.bgBase)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    ChatSurfaceHeader(
                        personaName = "神里绫华",
                        portraitPath = "",
                        secondary = "亲密 · 稻妻日常",
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
                    DsConversationComposer {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DsComposerAction(
                                icon = Icons.Outlined.AttachFile,
                                contentDescription = "添加附件",
                                onClick = {},
                            )
                            DsComposerField(
                                value = "",
                                onValueChange = {},
                                placeholder = "和神里绫华说点什么…",
                                modifier = Modifier.weight(1f),
                            )
                            DsComposerAction(
                                icon = Icons.Filled.ArrowUpward,
                                contentDescription = "发送",
                                onClick = {},
                                tint = DsTheme.colors.accent,
                            )
                        }
                    }
                }
            }
        }
        save("chat.png")
    }

    @Test
    fun captureWork() {
        compose.setContent {
            DshTheme(preference = ThemePreference.LIGHT) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(DsTheme.colors.bgBase)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    WorkSurfaceHeader(
                        sessionTitle = "检查项目并修复问题",
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
                        onOpenResults = {},
                    )
                    Spacer(Modifier.weight(1f))
                    DsConversationComposer {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DsComposerAction(
                                icon = Icons.Outlined.AttachFile,
                                contentDescription = "添加附件",
                                onClick = {},
                            )
                            DsComposerField(
                                value = "",
                                onValueChange = {},
                                placeholder = "继续交代任务…",
                                modifier = Modifier.weight(1f),
                            )
                            DsComposerAction(
                                icon = Icons.Filled.ArrowUpward,
                                contentDescription = "发送",
                                onClick = {},
                                tint = DsTheme.colors.accent,
                            )
                        }
                    }
                }
            }
        }
        save("work.png")
    }

    @Test
    fun captureCharacterTuning() {
        compose.setContent {
            DshTheme(preference = ThemePreference.LIGHT) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(DsTheme.colors.bgBase),
                ) {
                    CharacterBehaviorTuningDialog(
                        personaName = "神里绫华",
                        portraitPath = "",
                        relationshipState = "亲密",
                        mood = "放松",
                        evolution = CharacterEvolutionState(),
                        initial = CharacterBehaviorTuning(),
                        onSave = {},
                        onDismiss = {},
                    )
                }
            }
        }
        save("character-tuning.png")
    }

    @Test
    fun captureUsage() {
        compose.setContent {
            DshTheme(preference = ThemePreference.LIGHT) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(DsTheme.colors.bgBase)
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
        }
        save("usage.png")
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val dir = File(context.filesDir, "readme-screenshots").apply {
            check(exists() || mkdirs()) { "无法创建 README 截图目录" }
        }
        FileOutputStream(File(dir, name)).use { output ->
            check(
                compose.onRoot()
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, output),
            ) { "README 截图写入失败：$name" }
        }
    }
}
