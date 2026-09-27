package com.labteto.dshmobile.ui.screens.local

import android.graphics.BitmapFactory
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalChatBranchInfo
import com.labteto.dshmobile.local.LocalChatMode
import com.labteto.dshmobile.local.LocalConversationMode
import com.labteto.dshmobile.local.LocalGroupChatMember
import com.labteto.dshmobile.local.LocalHarnessMessage
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.chatBranchInfo
import com.labteto.dshmobile.local.chatMessageHasAttachmentContext
import com.labteto.dshmobile.local.editableChatUserText
import com.labteto.dshmobile.local.groupMessageVisibleContent
import com.labteto.dshmobile.local.LocalImportedAttachment
import com.labteto.dshmobile.local.LocalImageInputMode
import com.labteto.dshmobile.local.LocalSessionSummary
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.ui.AgentOperationKind
import com.labteto.dshmobile.ui.agentOperationKind
import com.labteto.dshmobile.ui.agentOperationLabelRes
import com.labteto.dshmobile.ui.agentOperationStatusRes
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.AppBrandIcon
import com.labteto.dshmobile.ui.components.ConversationScrollShortcut
import com.labteto.dshmobile.ui.components.ConversationScrollTarget
import com.labteto.dshmobile.ui.components.rememberConversationScrollHint
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsCategoryRow
import com.labteto.dshmobile.ui.components.DsGroupCard
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsIconBox
import com.labteto.dshmobile.ui.components.DsIconFamily
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.DsQuickActionTile
import com.labteto.dshmobile.ui.components.DsStatus
import com.labteto.dshmobile.ui.components.DsStatusPill
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.MarkdownText
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.UserBubble
import com.labteto.dshmobile.ui.components.WhaleMark
import com.labteto.dshmobile.ui.components.relativeTime
import com.labteto.dshmobile.ui.theme.BackgroundRegion
import com.labteto.dshmobile.ui.theme.DsMetrics
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.LocalAppBackgroundState
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import kotlin.math.roundToInt
import com.labteto.dshmobile.ui.theme.rootSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun localResourcePressureLabel(pressure: String): String = stringResource(
    when (pressure.lowercase()) {
        "high" -> R.string.local_resource_pressure_high
        "medium" -> R.string.local_resource_pressure_medium
        else -> R.string.local_resource_pressure_low
    },
)

@Composable
internal fun localConversationModeLabel(mode: LocalConversationMode): String = stringResource(
    when (mode) {
        LocalConversationMode.INDEPENDENT -> R.string.local_context_source_independent
        LocalConversationMode.PROJECT -> R.string.local_context_source_project
        LocalConversationMode.CONTINUATION -> R.string.local_context_source_continuation
    },
)

@Composable
internal fun localJobStatusLabel(status: String): String = when (status) {
    "running" -> stringResource(R.string.jobs_running)
    "completed" -> stringResource(R.string.jobs_completed)
    "killed", "cancelled" -> stringResource(R.string.jobs_killed)
    "failed" -> stringResource(R.string.jobs_failed)
    "interrupted" -> stringResource(R.string.local_run_job_interrupted)
    else -> status
}

@Composable
internal fun LocalMessageRow(
    message: LocalHarnessMessage,
    chatMode: Boolean,
    groupMode: Boolean,
    canEdit: Boolean,
    canRegenerate: Boolean,
    branchInfo: LocalChatBranchInfo?,
    onEdit: (LocalHarnessMessage) -> Unit,
    onSelectVariant: (String, Int) -> Boolean,
    onRegenerate: (String) -> Boolean,
) {
    val colors = DsTheme.colors
    val backgroundState = LocalAppBackgroundState.current
    val clipboard = LocalClipboardManager.current

    when (message.role) {
        "user" -> Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
        ) {
            UserBubble(message.content)
            if (chatMode) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    MessageVariantControls(
                        messageId = message.id,
                        branchInfo = branchInfo,
                        onSelectVariant = onSelectVariant,
                    )
                    DsIconButton(
                        icon = FeatherIcons.Edit3,
                        contentDescription = stringResource(R.string.local_edit_user_message),
                        onClick = { onEdit(message) },
                        enabled = canEdit,
                        tint = colors.labelTertiary.copy(alpha = if (canEdit) 0.78f else 0.38f),
                    )
                }
            }
        }

        "system" -> Unit

        "reasoning", "tool", "progress" -> WorkProcessRow(listOf(message))

        else -> {
            val readingLayer = chatMode && backgroundState.hasImage
            val readingModifier = if (readingLayer) {
                Modifier
                    .fillMaxWidth()
                    .background(
                        backgroundState.wallpaperSurface(
                            base = colors.bgBase,
                            level = WallpaperSurfaceLevel.CARD,
                            region = BackgroundRegion.MIDDLE,
                        ),
                        RoundedCornerShape(18.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            } else {
                Modifier.fillMaxWidth()
            }
            Column(
                readingModifier,
                verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                if (groupMode) {
                    message.speakerName?.takeIf(String::isNotBlank)?.let { speaker ->
                        Text(
                            speaker,
                            style = DsType.small13Strong,
                            color = colors.accent,
                        )
                    }
                }
                val visibleContent = if (groupMode) groupMessageVisibleContent(message) else message.content
                MarkdownText(visibleContent)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (chatMode) {
                        MessageVariantControls(
                            messageId = message.id,
                            branchInfo = branchInfo,
                            onSelectVariant = onSelectVariant,
                        )
                    }
                    DsIconButton(
                        icon = Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.chat_copy_answer),
                        onClick = { clipboard.setText(AnnotatedString(visibleContent)) },
                        tint = colors.labelTertiary.copy(alpha = 0.78f),
                    )
                    if (canRegenerate) DsIconButton(
                        icon = Icons.Outlined.Refresh,
                        contentDescription = stringResource(R.string.local_regenerate_reply),
                        onClick = { onRegenerate(message.id) },
                        tint = colors.labelTertiary.copy(alpha = 0.78f),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageVariantControls(
    messageId: String,
    branchInfo: LocalChatBranchInfo?,
    onSelectVariant: (String, Int) -> Boolean,
) {
    val info = branchInfo ?: return
    val colors = DsTheme.colors
    DsIconButton(
        icon = Icons.Filled.KeyboardArrowLeft,
        contentDescription = stringResource(R.string.local_previous_variant),
        onClick = { onSelectVariant(messageId, info.index - 1) },
        enabled = info.hasPrevious,
        tint = colors.labelTertiary.copy(alpha = 0.78f),
    )
    Text(
        text = stringResource(R.string.local_variant_position, info.index + 1, info.count),
        style = DsType.caption11,
        color = colors.labelTertiary,
    )
    DsIconButton(
        icon = Icons.Filled.KeyboardArrowRight,
        contentDescription = stringResource(R.string.local_next_variant),
        onClick = { onSelectVariant(messageId, info.index + 1) },
        enabled = info.hasNext,
        tint = colors.labelTertiary.copy(alpha = 0.78f),
    )
}

@Composable
internal fun WorkProcessRow(messages: List<LocalHarnessMessage>) {
    if (messages.isEmpty()) return

    val colors = DsTheme.colors
    val toolMessages = messages.filter { it.role == "tool" }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
    ) {
        Column(
            Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Text(
                stringResource(R.string.local_work_process),
                style = DsType.small13Strong,
                color = colors.labelPrimary,
            )
            if (toolMessages.isEmpty()) {
                WorkProcessOperationRow(
                    kind = AgentOperationKind.Generic,
                    toolName = null,
                    failed = false,
                    running = true,
                    count = 1,
                )
            } else {
                val grouped = linkedMapOf<AgentOperationKind, MutableList<LocalHarnessMessage>>()
                toolMessages.forEach { message ->
                    grouped.getOrPut(agentOperationKind(message.toolName)) { mutableListOf() }.add(message)
                }
                grouped.forEach { (kind, group) ->
                    WorkProcessOperationRow(
                        kind = kind,
                        toolName = group.first().toolName,
                        failed = group.any { toolResultFailed(it.content) },
                        running = false,
                        count = group.size,
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkProcessOperationRow(
    kind: AgentOperationKind,
    toolName: String?,
    failed: Boolean,
    running: Boolean,
    count: Int,
) {
    val colors = DsTheme.colors
    val icon = when (kind) {
        AgentOperationKind.Inspect -> FeatherIcons.FileText
        AgentOperationKind.Search -> Icons.Outlined.Search
        AgentOperationKind.Update -> Icons.Outlined.Tune
        AgentOperationKind.Execute -> Icons.Outlined.Terminal
        AgentOperationKind.Web -> Icons.Outlined.Extension
        AgentOperationKind.Device -> Icons.Outlined.QrCodeScanner
        AgentOperationKind.Image -> Icons.Outlined.Image
        AgentOperationKind.Background -> Icons.Outlined.Schedule
        AgentOperationKind.Delegate -> Icons.Outlined.PersonSearch
        AgentOperationKind.External -> Icons.Outlined.Extension
        AgentOperationKind.Generic -> Icons.Outlined.Tune
    }
    val family = when (kind) {
        AgentOperationKind.Update -> DsIconFamily.Green
        AgentOperationKind.Execute -> DsIconFamily.Cyan
        AgentOperationKind.Search,
        AgentOperationKind.Web -> DsIconFamily.Accent
        AgentOperationKind.Device -> DsIconFamily.Amber
        AgentOperationKind.Delegate -> DsIconFamily.Purple
        else -> DsIconFamily.Neutral
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        DsIconBox(icon = icon, family = family)
        Text(
            stringResource(agentOperationLabelRes(toolName)),
            style = DsType.std14,
            color = colors.labelSecondary,
            modifier = Modifier.weight(1f),
        )
        if (count > 1) {
            DsPill(text = count.toString())
        }
        DsStatusPill(
            state = when {
                failed -> DsStatus.Failed
                running -> DsStatus.Running
                else -> DsStatus.Done
            },
            label = stringResource(agentOperationStatusRes(running = running, failed = failed)),
        )
    }
}

private fun toolResultFailed(content: String): Boolean =
    "工具执行失败" in content || "[TOOL_TIMEOUT]" in content || "[MODEL_TIMEOUT]" in content ||
        "[NETWORK_ERROR]" in content || "[DNS_FAILED]" in content || "[SSRF_BLOCKED]" in content

private const val LOCAL_TRANSCRIPT_INITIAL_WINDOW_MESSAGES = 200
private const val MAX_LOCAL_IMAGE_SELECTION = 20


internal fun decodeLocalAttachmentThumbnail(
    workspacePath: String,
    relativePath: String,
): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    val root = File(workspacePath).canonicalFile
    val file = File(root, relativePath).canonicalFile
    require(file.toPath().startsWith(root.toPath()) && file.isFile)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 160 || bounds.outHeight / (sample * 2) >= 160) {
        sample *= 2
    }
    BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )?.asImageBitmap()
}.getOrNull()

