package com.labteto.dshmobile.uipack.v6

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.uicontract.DeletePersonaGalleryCommand
import com.labteto.dshmobile.uicontract.PersonaGalleryDeletionUiPort
import com.labteto.dshmobile.uicontract.PersonaAuthoringUiPort
import com.labteto.dshmobile.uicontract.UiPersonaInspection
import com.labteto.dshmobile.uicontract.UiPersonaInspectionReceipt
import com.labteto.dshmobile.uicontract.UiPersonaAuthoringReceipt
import com.labteto.dshmobile.uicontract.PersonaGalleryUiCommand
import com.labteto.dshmobile.uicontract.PersonaGalleryUiPort
import com.labteto.dshmobile.uicontract.PersonaSelectionUiCommand
import com.labteto.dshmobile.uicontract.PersonaSelectionUiPort
import com.labteto.dshmobile.uicontract.PersonaStoryUiCommand
import com.labteto.dshmobile.uicontract.PersonaStoryUiPort
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.UiMessageSnapshot
import com.labteto.dshmobile.uicontract.UiPersonaGalleryEntry
import com.labteto.dshmobile.uicontract.UiPersonaGalleryHistoryReceipt
import com.labteto.dshmobile.uicontract.UiPersonaGalleryReadReceipt
import com.labteto.dshmobile.uicontract.UiPersonaGalleryDeletionReceipt
import com.labteto.dshmobile.uicontract.UiPersonaGalleryStory
import com.labteto.dshmobile.uicontract.UiPersonaStoryReceipt
import com.labteto.dshmobile.uicontract.UiSessionIdentity
import com.shenyan.designsystem.components.SyButton
import com.shenyan.designsystem.components.SyButtonStyle
import com.shenyan.designsystem.components.SyGlassCard
import com.shenyan.designsystem.components.SyInput
import com.shenyan.designsystem.components.SyNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Archive browsing and editing never impersonate foreground Chat commands. */
enum class V6PersonaArchivePart { GALLERY, PROFILE, DELETE }

internal fun v6ArchiveSaveLabel(receipt: UiPersonaStoryReceipt): String = when (receipt) {
    is UiPersonaStoryReceipt.Saved -> "故事已保存；当前会话同步状态：" + receipt.sessionSync.name
    UiPersonaStoryReceipt.Missing -> "目标故事已经不存在，请刷新档案"
    is UiPersonaStoryReceipt.Failed -> receipt.message ?: "故事更新失败"
}

/** Every reader and writer below uses an existing archive Owner with explicit gallery/story IDs. */
@Composable
fun V6PersonaArchiveScene(
    gallery: PersonaGalleryUiPort,
    selection: PersonaSelectionUiPort,
    stories: PersonaStoryUiPort?,
    deletion: PersonaGalleryDeletionUiPort?,
    authoring: PersonaAuthoringUiPort? = null,
    part: V6PersonaArchivePart,
    onBack: () -> Unit,
) {
    val selected by selection.snapshots.collectAsStateWithLifecycle(
        initialValue = remember(selection) { selection.snapshot() })
    var entries by remember(gallery) { mutableStateOf<List<UiPersonaGalleryEntry>>(emptyList()) }
    var activeGallery by remember(gallery) { mutableStateOf<String?>(null) }
    var activeStory by remember(gallery) { mutableStateOf<String?>(null) }
    var history by remember(activeGallery, activeStory) { mutableStateOf<List<UiMessageSnapshot>>(emptyList()) }
    var pageCursor by remember(activeGallery, activeStory) { mutableStateOf<String?>(null) }
    var historyLoaded by remember(activeGallery, activeStory) { mutableStateOf(false) }
    var loading by remember(gallery) { mutableStateOf(false) }
    var pending by remember(gallery) { mutableStateOf(false) }
    var notice by remember(gallery) { mutableStateOf<String?>(null) }
    var rename by remember(activeGallery, activeStory) { mutableStateOf("") }
    var notes by remember(activeGallery, activeStory) { mutableStateOf("") }
    var stage by remember(activeGallery, activeStory) { mutableStateOf("") }
    var deleteTarget by remember(activeGallery, activeStory) { mutableStateOf<Pair<String, String?>?>(null) }
    var inspection by remember(activeGallery, activeStory) { mutableStateOf<UiPersonaInspection?>(null) }
    var suggestionIndices by remember(activeGallery, activeStory) { mutableStateOf<Set<Int>>(emptySet()) }
    var confirmSuggestions by remember(activeGallery, activeStory) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun readGallery(clearNotice: Boolean = true) {
        when (val result = withContext(Dispatchers.IO) { gallery.read() }) {
            is UiPersonaGalleryReadReceipt.Available -> {
                entries = result.entries
                if (activeGallery != null && entries.none { it.id == activeGallery }) {
                    activeGallery = null
                    activeStory = null
                }
                if (clearNotice) notice = null
            }
            is UiPersonaGalleryReadReceipt.Failed ->
                notice = result.message ?: "人物档案读取失败"
        }
    }

    fun refresh() {
        if (loading || pending) return
        loading = true
        scope.launch {
            try { readGallery() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notice = failure.message ?: "人物档案读取失败" }
            finally { loading = false }
        }
    }

    fun chooseGallery(id: String) {
        activeGallery = id
        activeStory = null
        historyLoaded = false
        notice = null
    }
    fun chooseStory(story: UiPersonaGalleryStory) {
        activeStory = story.id
        rename = story.title
        notes = story.notes
        stage = story.storyStage
        historyLoaded = false
        notice = null
    }
    fun loadHistory(older: Boolean) {
        val galleryId = activeGallery ?: return
        val storyId = activeStory ?: return
        if (pending || (older && (!historyLoaded || pageCursor == null))) return
        val cursor = if (older) pageCursor else null
        pending = true
        scope.launch {
            try {
                val receipt = withContext(Dispatchers.IO) {
                    gallery.history(galleryId, storyId, cursor)
                }
                if (activeGallery != galleryId || activeStory != storyId) return@launch
                when (receipt) {
                    is UiPersonaGalleryHistoryReceipt.Available -> {
                        history = if (older)
                            (receipt.page.messages + history).distinctBy { it.id }
                        else receipt.page.messages
                        pageCursor = receipt.page.olderCursor
                        historyLoaded = true
                        notice = "已加载 ${history.size}/${receipt.page.totalCount} 条历史消息"
                    }
                    UiPersonaGalleryHistoryReceipt.Stale -> {
                        historyLoaded = false
                        pageCursor = null
                        history = emptyList()
                        notice = "历史版本已变化，请重新读取"
                    }
                    UiPersonaGalleryHistoryReceipt.Missing -> notice = "故事已经不存在"
                    is UiPersonaGalleryHistoryReceipt.Failed ->
                        notice = receipt.message ?: "历史读取失败"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notice = failure.message ?: "历史读取失败" }
            finally { pending = false }
        }
    }

    fun write(operation: suspend () -> String, afterSuccess: Boolean = false) {
        if (pending || loading) return
        pending = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { operation() }
                notice = result
                // A successful refresh must not erase the actual write receipt.
                if (afterSuccess) readGallery(clearNotice = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notice = failure.message ?: "档案操作失败" }
            finally { pending = false }
        }
    }

    LaunchedEffect(gallery) {
        loading = true
        try { readGallery() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { notice = failure.message ?: "人物档案读取失败" }
        finally { loading = false }
    }

    val chosen = entries.firstOrNull { it.id == activeGallery }
    val story = chosen?.stories?.firstOrNull { it.id == activeStory }
    val currentIdentity: UiSessionIdentity? = selected.identity
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(when (part) {
            V6PersonaArchivePart.GALLERY -> "人物档案馆"
            V6PersonaArchivePart.PROFILE -> "人物与故事详情"
            V6PersonaArchivePart.DELETE -> "档案清理"
        }, style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SyButton("返回", onBack, style = SyButtonStyle.GHOST)
            SyButton("刷新人物库", { refresh() }, enabled = !loading && !pending,
                style = SyButtonStyle.SECONDARY)
        }
        if (loading) SyNote("正在读取档案")
        if (pending) SyNote("正在与原档案 Owner 同步")
        notice?.let { SyNote(it) }
        if (entries.isEmpty() && !loading) SyNote("当前没有已保存人物档案")
        entries.forEach { person ->
            SyGlassCard(Modifier.fillMaxWidth()) {
                SyButton(person.name + "（${person.stories.size} 个故事）", {
                    chooseGallery(person.id)
                }, style = if (activeGallery == person.id) SyButtonStyle.PRIMARY else SyButtonStyle.SECONDARY)
                if (part == V6PersonaArchivePart.DELETE && deletion != null)
                    SyButton("删除此人物及其档案", {
                        deleteTarget = person.id to null
                    }, style = SyButtonStyle.GHOST, enabled = !pending)
            }
        }
        if (chosen != null && part != V6PersonaArchivePart.DELETE) {
            Text(chosen.name, style = MaterialTheme.typography.titleMedium)
            if (currentIdentity != null && selected.canStartStory) {
                SyButton("以此人物开始新故事", {
                    val expected = currentIdentity
                    write({
                        val receipt = selection.submit(PersonaSelectionUiCommand.StartStory(
                            expected, chosen.id, storyId = null, freshStory = true))
                        describeV6ConversationReceipt(receipt)
                    })
                }, enabled = !pending)
            }
            chosen.stories.forEach { item ->
                SyGlassCard(Modifier.fillMaxWidth()) {
                    SyButton(item.title, { chooseStory(item) },
                        style = if (activeStory == item.id) SyButtonStyle.PRIMARY
                        else SyButtonStyle.SECONDARY)
                    Text("历史消息：" + item.messageCount + " · 阶段：" + item.storyStage)
                    if (currentIdentity != null && selected.canStartStory)
                        SyButton("继续这段故事", {
                            write({
                                describeV6ConversationReceipt(selection.submit(
                                    PersonaSelectionUiCommand.StartStory(
                                        currentIdentity, chosen.id, item.id, freshStory = false)))
                            })
                        }, enabled = !pending, style = SyButtonStyle.GHOST)
                }
            }
        }
        if (part == V6PersonaArchivePart.DELETE) {
            chosen?.stories?.forEach { item ->
                SyButton("删除故事：" + item.title, { deleteTarget = chosen.id to item.id },
                    enabled = !pending, style = SyButtonStyle.GHOST)
            }
        } else if (chosen != null && story != null) {
            Text("故事信息：" + story.title, style = MaterialTheme.typography.titleMedium)
            if (stories != null) {
                SyInput(notes, { notes = it }, label = "故事备注", singleLine = false)
                SyInput(stage, { stage = it }, label = "故事阶段")
                SyButton("保存故事资料", {
                    val id = chosen.id
                    val sid = story.id
                    val expected = currentIdentity
                    write({
                        v6ArchiveSaveLabel(stories.submit(
                            PersonaStoryUiCommand.SaveDetails(expected, id, sid, notes, stage)))
                    }, afterSuccess = true)
                }, enabled = !pending, style = SyButtonStyle.SECONDARY)
            }
            SyInput(rename, { rename = it }, label = "故事标题")
            SyButton("保存标题", {
                val id = chosen.id
                val sid = story.id
                write({
                    describeV6ConversationReceipt(
                        gallery.submit(PersonaGalleryUiCommand.RenameStory(id, sid, rename)))
                }, afterSuccess = true)
            }, enabled = !pending && rename.isNotBlank(), style = SyButtonStyle.SECONDARY)
            if (authoring != null && currentIdentity != null) {
                SyButton("检查人物与故事设定", {
                    if (!pending) {
                        val id = chosen.id
                        val sid = story.id
                        val expected = currentIdentity
                        pending = true
                        scope.launch {
                            try {
                                val receipt = withContext(Dispatchers.IO) {
                                    authoring.inspect(expected, id, sid)
                                }
                                if (activeGallery == id && activeStory == sid &&
                                    selection.snapshot().identity == expected) {
                                    when (receipt) {
                                        is UiPersonaInspectionReceipt.Inspected -> {
                                            inspection = receipt.inspection
                                            suggestionIndices = receipt.inspection.suggestions.indices.toSet()
                                            notice = "检查返回 ${receipt.inspection.conflicts.size} 条冲突、${receipt.inspection.suggestions.size} 条建议；尚未更改人物档案"
                                        }
                                        UiPersonaInspectionReceipt.Rejected ->
                                            notice = "原 Owner 拒绝检查请求"
                                        is UiPersonaInspectionReceipt.Failed ->
                                            notice = receipt.message ?: "设定检查失败"
                                    }
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { notice = failure.message ?: "设定检查失败" }
                            finally { pending = false }
                        }
                    }
                }, enabled = !pending && !loading,
                    style = SyButtonStyle.SECONDARY)
                inspection?.takeIf { it.galleryId == chosen.id }?.let { examined ->
                    Text("检查版本：${examined.revision} · 建议尚未应用")
                    examined.conflicts.forEach { issue ->
                        SyGlassCard(Modifier.fillMaxWidth()) {
                            Text("冲突：" + issue.field)
                            Text("期望：" + issue.fixedValue)
                            Text("实际：" + issue.observedValue)
                            Text(issue.reason)
                        }
                    }
                    examined.suggestions.forEachIndexed { index, suggestion ->
                        SyGlassCard(Modifier.fillMaxWidth()) {
                            Text(suggestion.field + "：" + suggestion.value)
                            Text("建议依据：" + suggestion.evidence)
                            com.shenyan.designsystem.components.SySwitch(
                                "应用此条建议", index in suggestionIndices,
                                onCheckedChange = { checked ->
                                    suggestionIndices = if (checked) suggestionIndices + index
                                        else suggestionIndices - index
                                })
                        }
                    }
                    SyButton("确认并应用所选建议", { confirmSuggestions = true },
                        enabled = suggestionIndices.isNotEmpty() && !pending && !loading)
                }
            }
            SyButton(if (historyLoaded) "重新读取历史" else "读取历史消息", {
                loadHistory(false)
            }, enabled = !pending, style = SyButtonStyle.SECONDARY)
            if (historyLoaded) {
                history.forEach { message ->
                    SyGlassCard(Modifier.fillMaxWidth()) {
                        Text(message.role.name, style = MaterialTheme.typography.labelSmall)
                        Text(message.text)
                    }
                }
                if (pageCursor != null) SyButton("加载更早消息", { loadHistory(true) },
                    enabled = !pending)
            }
        }
    }
    if (confirmSuggestions) {
        val examined = inspection
        val owner = authoring
        AlertDialog(onDismissRequest = { if (!pending) confirmSuggestions = false },
            title = { Text("将检查建议写入人物档案？") },
            text = { Text("只写入勾选的建议，由 Chat Owner 使用档案版本 ${examined?.revision} 再次校验。保存图库不会自动改变正在运行的角色。") },
            confirmButton = { TextButton(onClick = {
                confirmSuggestions = false
                val doc = examined ?: return@TextButton
                val author = owner ?: return@TextButton
                val galleryId = activeGallery ?: return@TextButton
                if (doc.galleryId != galleryId || pending ||
                    suggestionIndices.isEmpty()) return@TextButton
                val chosenSuggestions = doc.suggestions.filterIndexed { index, _ ->
                    index in suggestionIndices
                }
                pending = true
                scope.launch {
                    try {
                        val receipt = withContext(Dispatchers.IO) {
                            author.applySuggestions(galleryId, doc.revision, chosenSuggestions)
                        }
                        if (activeGallery == galleryId) {
                            notice = when (receipt) {
                                is UiPersonaAuthoringReceipt.Saved -> {
                                    inspection = null; suggestionIndices = emptySet()
                                    readGallery(clearNotice = false)
                                    "所选建议已保存至人物图库，前台人物仍须显式重新选择"
                                }
                                is UiPersonaAuthoringReceipt.Rejected ->
                                    "档案版本或权限已变化，未覆盖，请重新检查：" + receipt.reason.name
                                is UiPersonaAuthoringReceipt.Failed ->
                                    receipt.message ?: "建议写入失败"
                                else -> "未收到图库保存确认，请重新检查"
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { notice = failure.message ?: "建议写入失败" }
                    finally { pending = false }
                }
            }, enabled = !pending) { Text("确认保存") } },
            dismissButton = { TextButton(onClick = { confirmSuggestions = false }) {
                Text("取消")
            } },
        )
    }
    deleteTarget?.let { (galleryId, storyId) ->
        AlertDialog(
            onDismissRequest = { if (!pending) deleteTarget = null },
            title = { Text(if (storyId == null) "确认删除整个人物档案" else "确认删除故事") },
            text = { Text("此操作会修改已保存档案，需要原 Owner 确认。删除后可能无法找回。") },
            confirmButton = { TextButton(onClick = {
                if (pending) return@TextButton
                deleteTarget = null
                if (storyId == null) {
                    val owner = deletion ?: return@TextButton
                    write({
                        when (val result = owner.delete(DeletePersonaGalleryCommand(currentIdentity, galleryId))) {
                            is UiPersonaGalleryDeletionReceipt.Deleted ->
                                "档案已删除；同步：" + result.sessionSync.name +
                                    (result.portraitCleanupWarning?.let { "；图片清理：" + it } ?: "")
                            UiPersonaGalleryDeletionReceipt.Missing -> "档案已不存在"
                            is UiPersonaGalleryDeletionReceipt.Failed -> result.message ?: "档案删除失败"
                        }
                    }, afterSuccess = true)
                } else {
                    val owner = stories ?: return@TextButton
                    write({
                        v6ArchiveSaveLabel(owner.submit(PersonaStoryUiCommand.Delete(
                            currentIdentity, galleryId, storyId)))
                    }, afterSuccess = true)
                }
            }) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}
