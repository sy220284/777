package com.labteto.dshmobile.uipack.zhaoxi.scenes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

/**
 * 人物/图集/群聊/日记域场景（E8.14 实装）。
 * 数据全部来自 S1 端口系投影；归档变更（删除/迁移）走显式 Owner 命令，结果以 receipt 呈现。
 */

/** chat.gallery / route.persona_gallery / chat.persona_picker — 图集。 */
@Composable
fun ZhaoxiPersonaGalleryScene(
    gallery: PersonaGalleryUiPort,
    scope: UiSceneRenderScope,
    onOpenPersona: (String) -> Unit = {},
) {
    val state by produceState<UiPersonaGalleryReadReceipt?>(null) { value = gallery.read() }
    ZxPage("人物图集", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        when (val r = state) {
            null -> repeat(3) { ZxSkeleton(ZxSkeletonVariant.Card, Modifier.padding(vertical = 6.dp)) }
            is UiPersonaGalleryReadReceipt.Failed ->
                com.labteto.dshmobile.uipack.zhaoxi.components.ZxBanner("读取失败 · ${r.message ?: "未知"}", com.labteto.dshmobile.uipack.zhaoxi.components.ZxTone.Err)
            is UiPersonaGalleryReadReceipt.Available -> {
                if (r.entries.isEmpty()) ZxEmpty("图集还空着", "添加预设或新建一个人物开始")
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.height((r.entries.size / 2 + 1) * 148.dp),
                ) {
                    items(r.entries) { e ->
                        ZxPersonaCard(e.name, "${e.stories.size} 段故事", onClick = { onOpenPersona(e.id) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZxChip("＋ 添加（预设 / 新建 / 导入）", false) {}
                    ZxChip("管理", false) {}
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
) {
    val state by produceState<UiPersonaAuthoringReceipt?>(null, galleryId) { value = authoring.read(galleryId) }
    var tab by remember { mutableStateOf(0) }
    ZxPage("人物", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        when (val r = state) {
            null -> repeat(3) { ZxSkeleton(ZxSkeletonVariant.Row, Modifier.padding(vertical = 6.dp)) }
            is UiPersonaAuthoringReceipt.Loaded -> {
                val draft = r.snapshot.draft
                Text(draft.name, fontSize = 17.sp, color = ZhaoxiTheme.colors.ink)
                Text("revision ${r.snapshot.revision} · 亲密与日记数由图集提供", fontSize = 10.5.sp,
                    color = ZhaoxiTheme.colors.ink3)
                Spacer(Modifier.height(8.dp))
                ZxTabRow(listOf("核心身份", "事实 ${draft.facts.size}", "世界书 ${draft.lore.size}", "行为调节"), tab) { tab = it }
                Spacer(Modifier.height(8.dp))
                when (tab) {
                    0 -> {
                        ZxValueRow("核心身份", value = "", sub = draft.coreIdentity)
                        ZxValueRow("开场白", value = "", sub = draft.fields["opening"] ?: "未设置")
                    }
                    1 -> draft.facts.forEach { f -> ZxRow(f.content.take(40), "${f.category} 类事实 · ${f.provenance}") }
                    2 -> draft.lore.forEach { l -> ZxRow(l.title, "关键词 ${l.keywords.joinToString()} · 剧透 Lv${l.spoilerLevel} · 优先级 ${l.priority}") }
                    else -> {
                        ZxValueRow("表现张力", value = "${draft.samplingTemperaturePosition ?: 50}", sub = "影响采样温度位置 · 仅此人物")
                        ZxNavRow("恢复默认", sub = "回到全局采样行为") {}
                    }
                }
                ZxFooterNote("保存只写归档 · 应用到当前会话是独立显式动作")
            }
            is UiPersonaAuthoringReceipt.Failed ->
                com.labteto.dshmobile.uipack.zhaoxi.components.ZxBanner("读取失败 · ${r.message ?: "未知"}", com.labteto.dshmobile.uipack.zhaoxi.components.ZxTone.Err)
            else -> ZxEmpty("不可用", "人物归档不存在或已被移除")
        }
    }
}

/** chat.gallery_portrait — 人物画像（含 chat.gallery_save 存图集入口）。 */
@Composable
fun ZhaoxiPortraitScene(
    portraits: PersonaPortraitUiPort,
    galleryId: String,
    personaName: String,
    scope: UiSceneRenderScope,
) {
    val co = androidx.compose.runtime.rememberCoroutineScope()
    ZxPage("$personaName · 画像", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        ZxPortraitCard(
            personaName = personaName,
            traits = listOf("气质" to "由日记与相处生成", "画像来源" to "preview($galleryId)"),
            onRegenerate = { co.launch { portraits.preview(galleryId) } },
            onSave = { /* PersonaGallerySaveUiPort 由组合根绑定 */ },
        )
    }
}

/** chat.gallery_story — 图集故事章节列表。 */
@Composable
fun ZxGalleryStoryScene(
    entry: UiPersonaGalleryEntry,
    stories: PersonaStoryUiPort?,
    scope: UiSceneRenderScope,
) {
    ZxPage("${entry.name} · 故事", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        if (entry.stories.isEmpty()) ZxEmpty("还没有故事", "故事由相处事件凝结")
        entry.stories.forEach { s ->
            ZxStoryCard(
                ZxStoryChapter(s.id, s.title, s.notes, "${s.messageCount} 条消息 · ${s.storyStage.ifBlank { "未分章" }}"),
                onOpen = {}, onContinue = {},
            )
            Spacer(Modifier.height(8.dp))
        }
        ZxFooterNote("续写消耗一次对话额度")
    }
}

/** chat.gallery_delete — 删除人物（确认弹窗，Danger）。 */
@Composable
fun ZxGalleryDeleteScene(
    deletion: PersonaGalleryDeletionUiPort,
    galleryId: String,
    personaName: String,
    identity: com.labteto.dshmobile.uicontract.UiSessionIdentity?,
    scope: UiSceneRenderScope,
) {
    val co = androidx.compose.runtime.rememberCoroutineScope()
    var done by remember { mutableStateOf<String?>(null) }
    ZxPage("删除人物", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(24.dp))
        ZxDialog(
            title = "删除 $personaName？",
            body = "删除归档、故事与公开日记 · 不可恢复 · 私密日记按策略处理",
            confirmText = "删除",
            onConfirm = {
                co.launch {
                    val r = deletion.delete(DeletePersonaGalleryCommand(identity, galleryId))
                    done = r.toString()
                }
            },
            onDismiss = { scope.onBack() },
        )
        done?.let { Text(it.take(120), fontSize = 11.sp, color = ZhaoxiTheme.colors.ink3) }
    }
}

/** chat.persona_transfer — 迁移/导出人物。 */
@Composable
fun ZxPersonaTransferScene(
    transfer: PersonaTransferUiPort,
    galleryId: String,
    personaName: String,
    scope: UiSceneRenderScope,
) {
    ZxPage("迁移人物 · $personaName", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        ZxTransferSheet(
            personaName = personaName,
            onExport = { /* ExportPersonaDocumentCommand(galleryId, format) 由宿主触发 SAF */ },
            onImport = { /* ImportPersonaDocumentCommand(bytes…) 由宿主文件选择器触发 */ },
            onCopy = {},
            onDestructiveMove = {},
        )
        ZxFooterNote("导出/导入上限 ${transfer.maxImportBytes} bytes · 由 Owner 校验")
    }
}

/** chat.group_members — 群成员。 */
@Composable
fun ZxGroupMembersScene(members: List<Pair<String, Boolean>>, scope: UiSceneRenderScope) {
    ZxPage("群成员", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        if (members.isEmpty())
            ZxEmpty("成员清单未注入", "由 ChatGroupUiPort 投影提供")
        members.forEach { (name, selected) ->
            ZxRow(name, "") {
                ZxBadge(if (selected) "✓ 已选" else "未选", if (selected) ZxBadgeTone.Accent else ZxBadgeTone.Neutral)
            }
        }
        ZxFooterNote("成员变更经 ChatGroupUiPort · 群主语义不变")
    }
}

/** chat.group_announcement — 群公告编辑。 */
@Composable
fun ZxGroupAnnouncementScene(initial: String, scope: UiSceneRenderScope) {
    var text by remember { mutableStateOf(initial) }
    ZxPage("群公告", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        com.labteto.dshmobile.uipack.zhaoxi.components.ZxField(text, { text = it },
            Modifier.fillMaxWidth().height(120.dp), placeholder = "公告卡置顶于群会话顶部")
        Text("${text.length} / 2000 字", fontSize = 10.sp, color = ZhaoxiTheme.colors.ink3)
        Spacer(Modifier.height(10.dp))
        com.labteto.dshmobile.uipack.zhaoxi.components.ZxButton("保存公告", {}, Modifier.fillMaxWidth())
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
