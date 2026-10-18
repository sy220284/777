package com.labteto.dshmobile.uipack.zhaoxi.scenes

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labteto.dshmobile.uicontract.DeletePersonaGalleryCommand
import com.labteto.dshmobile.uicontract.ExportPersonaDocumentCommand
import com.labteto.dshmobile.uicontract.PersonaAuthoringUiPort
import com.labteto.dshmobile.uicontract.PersonaGalleryDeletionUiPort
import com.labteto.dshmobile.uicontract.PersonaGalleryUiPort
import com.labteto.dshmobile.uicontract.PersonaPortraitUiPort
import com.labteto.dshmobile.uicontract.PersonaStoryUiPort
import com.labteto.dshmobile.uicontract.PersonaTransferUiPort
import com.labteto.dshmobile.uicontract.UiPersonaAuthoringReceipt
import com.labteto.dshmobile.uicontract.UiPersonaGalleryReadReceipt
import com.labteto.dshmobile.uicontract.UiPersonaGalleryEntry
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxBadge
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxBadgeTone
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxChip
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxDialog
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxField
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxEmpty
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxFooterNote
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxPersonaCard
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxPortraitCard
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxSection
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxSkeleton
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxSkeletonVariant
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxStoryCard
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxStoryChapter
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxTabRow
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxTransferSheet
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme
import com.labteto.dshmobile.uihost.UiSceneRenderScope
import kotlinx.coroutines.launch
import com.labteto.dshmobile.uicontract.*
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * 人物/图集/群聊/日记域场景（E8.14 实装）。
 * 数据全部来自 S1 端口系投影；归档变更（删除/迁移）走显式 Owner 命令，结果以 receipt 呈现。
 */

/** Import success and failure are separate: an IO failure may have partially committed. */
internal fun UiPersonaImportReceipt.zxImportFeedback(): String = when (this) {
    is UiPersonaImportReceipt.Imported -> "人物已导入"
    is UiPersonaImportReceipt.Failed -> message?.takeIf(String::isNotBlank) ?: "导入状态不确定，请先刷新人物图集"
}

/** chat.gallery / route.persona_gallery / chat.persona_picker — 图集。 */
@Composable
fun ZhaoxiPersonaGalleryScene(
    gallery: PersonaGalleryUiPort,
    scope: UiSceneRenderScope,
    onOpenPersona: (String) -> Unit,
    authoring: PersonaAuthoringUiPort? = null,
    transfer: PersonaTransferUiPort? = null,
    portraits: PersonaPortraitUiPort? = null,
) {
    var revision by remember { mutableStateOf(0) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var showPresets by rememberSaveable { mutableStateOf(false) }
    val jobs = androidx.compose.runtime.rememberCoroutineScope()
    val portraitTargetPx = (180f * LocalDensity.current.density).toInt()
    val state by produceState<UiPersonaGalleryReadReceipt?>(null, revision) { value = gallery.read() }
    val importer = transfer?.let { owner ->
        com.labteto.dshmobile.uihost.rememberUiDocumentImporter(owner.maxImportBytes) { result ->
            if (result is com.labteto.dshmobile.uihost.UiDocumentPickReceipt.Picked && !busy) jobs.launch {
                busy = true
                try {
                    val receipt = owner.import(ImportPersonaDocumentCommand(
                        result.document.bytes, result.document.name, result.document.mimeType))
                    feedback = receipt.zxImportFeedback()
                    revision++ // A failed import may have committed; refresh before retry.
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    feedback = failure.message ?: "导入失败，请先刷新图集再重试"
                    revision++
                } finally { busy = false }
            }
        }
    }
    ZxPage("人物图集", onBack = { scope.onBack() }) {
        feedback?.let { Text(it, color = ZhaoxiTheme.colors.ink2) }
        ZxSection("人物")
        ZxField(search, { search = it }, Modifier.fillMaxWidth(), placeholder = "搜索人物")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (authoring != null) {
                ZxChip("新建人物", false) { onOpenPersona("new") }
                ZxChip("预设", showPresets) { showPresets = !showPresets }
            }
            if (importer != null) ZxChip("导入", false) { if (!busy) importer() }
        }
        if (showPresets && authoring != null) {
            ZxSection("人物预设")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                authoring.presets().forEach { preset ->
                    ZxChip(preset.name, false) { if (!busy) jobs.launch {
                        busy = true
                        try {
                            val receipt = authoring.installPreset(preset.id)
                            feedback = receipt.zxAuthoringFeedback()
                            if (receipt is UiPersonaAuthoringReceipt.Saved) revision++
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            feedback = failure.message ?: "预设安装失败"
                        } finally { busy = false }
                    } }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        when (val r = state) {
            null -> repeat(3) { ZxSkeleton(ZxSkeletonVariant.Card, Modifier.padding(vertical = 6.dp)) }
            is UiPersonaGalleryReadReceipt.Failed ->
                com.labteto.dshmobile.uipack.zhaoxi.components.ZxBanner("读取失败 · ${r.message ?: "未知"}", com.labteto.dshmobile.uipack.zhaoxi.components.ZxTone.Err)
            is UiPersonaGalleryReadReceipt.Available -> {
                val visible = r.entries.filter { search.isBlank() || it.name.contains(search, ignoreCase = true) }
                if (visible.isEmpty()) ZxEmpty(if (search.isBlank()) "暂无人物" else "没有匹配的人物", "可以新建或导入人物")
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.heightIn(min = 148.dp, max = 600.dp),
                ) {
                    items(visible, key = { it.id }) { e ->
                        val receipt by produceState<UiPersonaPortraitPreviewReceipt?>(null, e.id, portraits) {
                            value = portraits?.preview(e.id)
                        }
                        val bytes = (receipt as? UiPersonaPortraitPreviewReceipt.Ready)?.bytes
                        val portrait by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, bytes, portraitTargetPx) {
                            if (bytes != null) {
                                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                    val opts = android.graphics.BitmapFactory.Options().apply {
                                        inJustDecodeBounds = true
                                    }
                                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                                    var sample = 1
                                    while (opts.outWidth / (sample * 2) >= portraitTargetPx) sample *= 2
                                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(
                                        bytes, 0, bytes.size,
                                        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
                                    )
                                    bitmap?.asImageBitmap()
                                }
                            }
                        }
                        val stage = e.stories.lastOrNull()?.storyStage?.takeIf(String::isNotBlank)
                        ZxPersonaCard(
                            e.name,
                            "${e.stories.size} 段故事" + (stage?.let { " · ${it}" } ?: ""),
                            onClick = { onOpenPersona(e.id) },
                            portrait = portrait,
                            portraitHint = when (receipt) {
                                is UiPersonaPortraitPreviewReceipt.Failed -> "画像暂不可用"
                                UiPersonaPortraitPreviewReceipt.Missing -> "暂无画像"
                                else -> null
                            },
                        )
                    }
                }

            }
        }
    }
}

/** chat.persona_editor / chat.world_book / chat.character_tuning — 人物编辑器（四 tab）。 */
@Composable
fun ZhaoxiPersonaEditorScene(
    authoring: PersonaAuthoringUiPort,
    galleryId: String,
    scope: UiSceneRenderScope,
    initialTab: Int = 0,
) {
    val state by produceState<UiPersonaAuthoringReceipt?>(null, galleryId) {
        value = if (galleryId == "new") null else authoring.read(galleryId)
    }
    when (val result = state) {
        is UiPersonaAuthoringReceipt.Loaded -> ZhaoxiAuthoringForm(authoring, result.snapshot, scope, initialTab)
        null -> if (galleryId == "new") ZhaoxiAuthoringForm(authoring, null, scope, initialTab) else ZxEmpty("加载中", "正在读取人物")
        else -> ZxEmpty("人物暂不可编辑", "请返回图集重试")
    }
}

/** Convert archive errors to readable product copy, never Kotlin data-class output. */
internal fun zxGalleryDeletionFailure(receipt: UiPersonaGalleryDeletionReceipt): String = when (receipt) {
    UiPersonaGalleryDeletionReceipt.Missing -> "人物已经不存在"
    is UiPersonaGalleryDeletionReceipt.Failed -> receipt.message?.takeIf(String::isNotBlank) ?: "删除失败"
    is UiPersonaGalleryDeletionReceipt.Deleted -> "人物已删除"
}

/** chat.gallery_delete — 人物归档删除，危险操作有明确确认、取消与失败恢复。 */
@Composable
fun ZxGalleryDeleteScene(
    deletion: PersonaGalleryDeletionUiPort,
    galleryId: String,
    personaName: String,
    identity: UiSessionIdentity?,
    scope: UiSceneRenderScope,
    gallery: PersonaGalleryUiPort? = null,
) {
    val jobs = androidx.compose.runtime.rememberCoroutineScope()
    val entry by produceState<UiPersonaGalleryEntry?>(null, galleryId) {
        value = (gallery?.read() as? UiPersonaGalleryReadReceipt.Available)
            ?.entries?.firstOrNull { it.id == galleryId }
    }
    var result by remember(galleryId) { mutableStateOf<UiPersonaGalleryDeletionReceipt?>(null) }
    var busy by remember(galleryId) { mutableStateOf(false) }
    var confirmDelete by remember(galleryId) { mutableStateOf(true) }
    ZxPage("删除人物", onBack = { scope.onBack() }) {
        when (val receipt = result) {
            is UiPersonaGalleryDeletionReceipt.Deleted -> {
                Text("人物已删除", color = ZhaoxiTheme.colors.ink)
                receipt.portraitCleanupWarning?.let {
                    com.labteto.dshmobile.uipack.zhaoxi.components.ZxBanner(
                        it, com.labteto.dshmobile.uipack.zhaoxi.components.ZxTone.Warn)
                }
                if (receipt.sessionSync == UiStorySessionSync.DEFERRED) {
                    ZxFooterNote("当前会话稍后同步")
                }
                ZxChip("返回图集", false) {
                    scope.onNavigate(UiSceneId("route.persona_gallery"))
                }
            }
            UiPersonaGalleryDeletionReceipt.Missing -> {
                ZxEmpty("人物已不存在", "可返回图集查看",
                    actionLabel = "返回图集",
                    onAction = { scope.onNavigate(UiSceneId("route.persona_gallery")) })
            }
            else -> {
                if (busy) {
                    Text("正在删除…", color = ZhaoxiTheme.colors.ink2)
                } else {
                    if (receipt is UiPersonaGalleryDeletionReceipt.Failed) {
                        com.labteto.dshmobile.uipack.zhaoxi.components.ZxBanner(
                            zxGalleryDeletionFailure(receipt),
                            com.labteto.dshmobile.uipack.zhaoxi.components.ZxTone.Err)
                        ZxButton("重新尝试", { confirmDelete = true })
                    }
                    if (!confirmDelete) ZxButton("返回", { scope.onBack() })
                }
                if (confirmDelete && !busy) ZxDialog(
                    "删除 ${entry?.name ?: personaName}？",
                    "将删除人物归档、故事及公开日记，无法恢复。",
                    "确认删除",
                    {
                        confirmDelete = false
                        if (!busy) jobs.launch {
                            busy = true
                            try {
                                result = deletion.delete(DeletePersonaGalleryCommand(identity, galleryId))
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                result = UiPersonaGalleryDeletionReceipt.Failed(failure.message)
                            } finally {
                                busy = false
                            }
                        }
                    },
                    { confirmDelete = false; scope.onBack() },
                )
            }
        }
    }
}

/** Owner-aligned bounds; surface errors before sending an invalid group mutation. */
internal fun zxGroupMemberCountError(count: Int, min: Int, max: Int): String? = when {
    count < min -> "至少选择 ${min} 位成员"
    count > max -> "最多选择 ${max} 位成员"
    else -> null
}

internal fun zxAnnouncementLengthError(length: Int, limit: Int): String? =
    if (length > limit) "公告不能超过 ${limit} 字" else null

/** chat.group_members — show member bounds and real save receipt. */
@Composable
fun ZxGroupMembersScene(groups: ChatGroupUiPort, scope: UiSceneRenderScope) {
    val snap by groups.snapshots.collectAsState(initial = groups.snapshot())
    var selected by remember(snap.identity, snap.memberIds) { mutableStateOf(snap.memberIds) }
    var receipt by remember(snap.identity) { mutableStateOf<UiCommandReceipt?>(null) }
    val selectionError = zxGroupMemberCountError(selected.size, snap.minimumMembers, snap.maximumMembers)
    ZxPage("群成员", onBack = { scope.onBack() }) {
        CommandFeedback(receipt)
        if (snap.available.isEmpty()) ZxEmpty("没有可选人物", "先创建人物再设置群聊")
        snap.available.forEach { choice ->
            val picked = choice.galleryId in selected
            ZxRow(choice.displayName, if (picked) "已选" else "未选",
                onClick = {
                    selected = if (picked) selected - choice.galleryId
                        else if (selected.size < snap.maximumMembers) selected + choice.galleryId else selected
                }, trailing = {
                    ZxBadge(if (picked) "已选" else "未选", ZxBadgeTone.Neutral)
                })
        }
        Text("${selected.size} / ${snap.maximumMembers} 位成员", color = ZhaoxiTheme.colors.ink2)
        selectionError?.let { Text(it, color = ZhaoxiTheme.colors.err) }
        ZxButton("保存成员", {
            snap.identity?.let { receipt = groups.submit(ChatGroupUiCommand.ConfigureMembers(it, selected.toList())) }
        }, enabled = !snap.busy && snap.identity != null &&
            selectionError == null && selected != snap.memberIds)
    }
}

/** chat.group_announcement — display validation and preserve the draft after rejection. */
@Composable
fun ZxGroupAnnouncementScene(groups: ChatGroupUiPort, scope: UiSceneRenderScope) {
    val snap by groups.snapshots.collectAsState(initial = groups.snapshot())
    var text by com.labteto.dshmobile.uihost.rememberUiPrivateText(
        "zhaoxi.announcement.${snap.identity?.sessionId}", snap.announcement) { snap.announcement }
    var receipt by remember(snap.identity) { mutableStateOf<UiCommandReceipt?>(null) }
    val error = zxAnnouncementLengthError(text.length, snap.maximumAnnouncementChars)
    ZxPage("群公告", onBack = { scope.onBack() }) {
        CommandFeedback(receipt)
        ZxField(text, { text = it }, Modifier.fillMaxWidth().height(120.dp),
            placeholder = "公告", error = error != null, errorMessage = error)
        Text("${text.length} / ${snap.maximumAnnouncementChars} 字",
            color = if (error != null) ZhaoxiTheme.colors.err else ZhaoxiTheme.colors.ink3)
        error?.let { Text(it, color = ZhaoxiTheme.colors.err) }
        ZxButton("保存公告", {
            snap.identity?.let { receipt = groups.submit(ChatGroupUiCommand.SaveAnnouncement(it, text)) }
        }, enabled = !snap.busy && snap.identity != null &&
            error == null && text != snap.announcement)
    }
}

/** chat.reply_suggestions — 回复建议层。 */
@Composable
fun ZxReplySuggestionsScene(
    suggestions: com.labteto.dshmobile.uicontract.ReplySuggestionsUiPort,
    onPick: (String) -> Unit,
    scope: UiSceneRenderScope,
) {
    val snap by suggestions.snapshots.collectAsState(
        initial = com.labteto.dshmobile.uicontract.UiReplySuggestionsSnapshot(null, emptyList()))
    ZxPage("回复建议", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        if (snap.values.isEmpty()) ZxEmpty("暂时没有建议", "对话进行一轮后再来")
        snap.values.forEach { s ->
            ZxRow(s.text, s.label + if (s.bold) " · 加粗" else "", onClick = { onPick(s.text) })
        }
        ZxFooterNote("点选填入草稿 · 不直接发送")
    }
}
