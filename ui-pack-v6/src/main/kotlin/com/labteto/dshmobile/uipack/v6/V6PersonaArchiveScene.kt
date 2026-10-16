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
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.shenyan.designsystem.components.SyEntrance
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
    is UiPersonaStoryReceipt.Saved -> "故事已保存；" + v6StorySyncLabel(receipt.sessionSync)
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
    portraits: com.labteto.dshmobile.uicontract.PersonaPortraitUiPort? = null,
    part: V6PersonaArchivePart,
    onBack: () -> Unit,
) {
    val selected by selection.snapshots.collectAsStateWithLifecycle(
        initialValue = remember(selection) { selection.snapshot() })
    var entries by remember(gallery) { mutableStateOf<List<UiPersonaGalleryEntry>>(emptyList()) }
    var activeGallery by rememberSaveable(gallery) { mutableStateOf<String?>(null) }
    var activeStory by rememberSaveable(gallery) { mutableStateOf<String?>(null) }
    var history by remember(activeGallery, activeStory) { mutableStateOf<List<UiMessageSnapshot>>(emptyList()) }
    var pageCursor by remember(activeGallery, activeStory) { mutableStateOf<String?>(null) }
    var historyLoaded by remember(activeGallery, activeStory) { mutableStateOf(false) }
    var loading by remember(gallery) { mutableStateOf(false) }
    var pending by remember(gallery) { mutableStateOf(false) }
    var notice by remember(gallery) { mutableStateOf<String?>(null) }
    val chosen = entries.firstOrNull { it.id == activeGallery }
    val story = chosen?.stories?.firstOrNull { it.id == activeStory }
    var rename by rememberSaveable(activeGallery, activeStory) { mutableStateOf(story?.title.orEmpty()) }
    var notes by rememberSaveable(activeGallery, activeStory) { mutableStateOf(story?.notes.orEmpty()) }
    var stage by rememberSaveable(activeGallery, activeStory) { mutableStateOf(story?.storyStage.orEmpty()) }
    val dirty = story != null && (rename != story.title || notes != story.notes || stage != story.storyStage)
    var pendingDeparture by remember(gallery) { mutableStateOf<(() -> Unit)?>(null) }
    var departureError by remember(gallery) { mutableStateOf<String?>(null) }
    fun requestDeparture(action: () -> Unit) {
        if (pending || loading) return
        if (dirty) { departureError = null; pendingDeparture = action } else action()
    }
    BackHandler(enabled = dirty || pending || pendingDeparture != null) {
        if (!pending) requestDeparture(onBack)
    }
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

    val currentIdentity: UiSessionIdentity? = selected.identity
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(when (part) {
            V6PersonaArchivePart.GALLERY -> "人物档案馆"
            V6PersonaArchivePart.PROFILE -> "人物与故事详情"
            V6PersonaArchivePart.DELETE -> "档案清理"
        }, style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SyButton("返回", { requestDeparture(onBack) }, enabled = !pending, style = SyButtonStyle.GHOST)
            SyButton("刷新人物库", { refresh() }, enabled = !loading && !pending,
                style = SyButtonStyle.SECONDARY)
        }
        if (loading) SyNote("正在读取档案")
        if (pending) SyNote("正在处理，请稍候")
        notice?.let { SyNote(it) }
        if (entries.isEmpty() && !loading) SyNote("当前没有已保存人物档案")
        if (chosen != null && part != V6PersonaArchivePart.DELETE)
            SyButton("切换人物", { requestDeparture { activeGallery = null; activeStory = null } },
                enabled = !pending, style = SyButtonStyle.GHOST)
        if (chosen == null || part == V6PersonaArchivePart.DELETE) entries.forEachIndexed { index, person ->
            androidx.compose.runtime.key(person.id) {
                SyEntrance("gallery:${person.id}", order = index) {
                    Column(Modifier.fillMaxWidth()) {
                        com.shenyan.designsystem.components.SyCategoryRow(title = person.name,
                            subtitle = "${person.stories.size} 段故事",
                            onClick = { requestDeparture { chooseGallery(person.id) } })
                        if (part == V6PersonaArchivePart.DELETE && deletion != null)
                            SyButton("删除此人物及其档案", {
                                deleteTarget = person.id to null
                            }, style = SyButtonStyle.GHOST, enabled = !pending)
                    }
                }
            }
        }
        if (chosen != null && part != V6PersonaArchivePart.DELETE) {
            V6PersonaHero(chosen, portraits)
            if (currentIdentity != null && selected.canStartStory) {
                SyButton("开始新故事", {
                    requestDeparture {
                        val expected = currentIdentity
                        write({
                            val receipt = selection.submit(PersonaSelectionUiCommand.StartStory(
                                expected, chosen.id, storyId = null, freshStory = true))
                            describeV6ConversationReceipt(receipt)
                        })
                    }
                }, enabled = !pending, style = SyButtonStyle.SECONDARY)
            }
            if (story != null) {
                Text(story.title, style = MaterialTheme.typography.titleMedium)
                SyButton("切换故事", { requestDeparture { activeStory = null } },
                    enabled = !pending, style = SyButtonStyle.GHOST)
                if (currentIdentity != null && selected.canStartStory) SyButton("继续这段故事", {
                    requestDeparture {
                        write({ describeV6ConversationReceipt(selection.submit(
                            PersonaSelectionUiCommand.StartStory(currentIdentity, chosen.id, story.id, freshStory = false))) })
                    }
                }, enabled = !pending)
            }
            if (story == null) chosen.stories.forEach { item ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.shenyan.designsystem.components.SyCategoryRow(title = item.title,
                        subtitle = "${item.messageCount} 条消息" +
                            item.storyStage.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                        onClick = { requestDeparture { chooseStory(item) } })
                    if (currentIdentity != null && selected.canStartStory)
                        SyButton("继续这段故事", {
                            write({
                                describeV6ConversationReceipt(selection.submit(
                                    PersonaSelectionUiCommand.StartStory(
                                        currentIdentity, chosen.id, item.id, freshStory = false)))
                            })
                        }, enabled = !pending, style = if (activeStory == item.id) SyButtonStyle.PRIMARY else SyButtonStyle.GHOST)
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
                SyInput(notes, { notes = it }, label = "故事备注", singleLine = false, enabled = !pending, maxLines = 8)
                SyInput(stage, { stage = it }, label = "故事阶段", enabled = !pending)
                SyButton(if (pending) "正在处理" else "保存故事资料", loading = pending, onClick = {
                    val id = chosen.id
                    val sid = story.id
                    val expected = currentIdentity
                    write({
                        v6ArchiveSaveLabel(stories.submit(
                            PersonaStoryUiCommand.SaveDetails(expected, id, sid, notes, stage)))
                    }, afterSuccess = true)
                }, enabled = !pending, style = SyButtonStyle.SECONDARY)
            }
            SyInput(rename, { rename = it }, label = "故事标题", enabled = !pending)
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
                                            notice = "当前无法检查，请稍后再试"
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
                    V6MessageBody(message, workMode = false)
                }
                if (pageCursor != null) SyButton("加载更早消息", { loadHistory(true) },
                    enabled = !pending)
            }
        }
    }
    if (pendingDeparture != null) {
        AlertDialog(
            onDismissRequest = { if (!pending) pendingDeparture = null },
            title = { Text("还有未保存的修改") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("保存后离开，或放弃本次修改。继续编辑会保留当前内容。")
                    departureError?.let { Text(it, color = com.shenyan.designsystem.foundation.LocalSyColors.current.dangerInk) }
                    SyButton("保存并离开", {
                        val person = chosen ?: return@SyButton
                        val source = story ?: return@SyButton
                        val action = pendingDeparture ?: return@SyButton
                        val expected = selected.identity
                        val titleToSave = rename
                        val notesToSave = notes
                        val stageToSave = stage
                        if (pending || titleToSave.isBlank()) return@SyButton
                        pending = true
                        departureError = null
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    if (notesToSave != source.notes || stageToSave != source.storyStage) {
                                        val owner = stories ?: error("故事资料暂时无法保存，请继续编辑")
                                        when (val result = owner.submit(PersonaStoryUiCommand.SaveDetails(
                                            expected, person.id, source.id, notesToSave, stageToSave))) {
                                            is UiPersonaStoryReceipt.Saved -> Unit
                                            UiPersonaStoryReceipt.Missing -> error("故事已不存在，请先保留修改内容")
                                            is UiPersonaStoryReceipt.Failed -> error(result.message ?: "故事资料保存失败")
                                        }
                                    }
                                    if (titleToSave != source.title) {
                                        val result = gallery.submit(PersonaGalleryUiCommand.RenameStory(
                                            person.id, source.id, titleToSave))
                                        if (result !is UiCommandReceipt.Accepted)
                                            error(describeV6ConversationReceipt(result))
                                    }
                                }
                                readGallery(clearNotice = false)
                                val saved = entries.firstOrNull { it.id == person.id }?.stories?.firstOrNull { it.id == source.id }
                                if (saved != null && saved.title == titleToSave && saved.notes == notesToSave && saved.storyStage == stageToSave) {
                                    pendingDeparture = null
                                    pending = false
                                    action()
                                } else departureError = "保存结果尚未确认，修改内容已保留，请稍后再试"
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { departureError = failure.message ?: "保存失败，修改内容已保留" }
                            finally { pending = false }
                        }
                    }, enabled = !pending && rename.isNotBlank())
                }
            },
            confirmButton = { TextButton(onClick = {
                val action = pendingDeparture
                rename = story?.title.orEmpty()
                notes = story?.notes.orEmpty()
                stage = story?.storyStage.orEmpty()
                pendingDeparture = null
                action?.invoke()
            }, enabled = !pending) { Text("放弃修改") } },
            dismissButton = { TextButton(onClick = { pendingDeparture = null }, enabled = !pending) { Text("继续编辑") } },
        )
    }
    if (confirmSuggestions) {
        val examined = inspection
        val owner = authoring
        AlertDialog(onDismissRequest = { if (!pending) confirmSuggestions = false },
            title = { Text("将检查建议写入人物档案？") },
            text = { Text("只保存勾选的建议。保存前会核对档案是否已更新；当前会话中的人物需重新选择后才会应用。") },
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
                                    "档案版本或权限已变化，未覆盖，请重新检查"
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
            text = { Text("将删除已保存的档案内容，删除后可能无法找回。") },
            confirmButton = { TextButton(onClick = {
                if (pending) return@TextButton
                deleteTarget = null
                if (storyId == null) {
                    val owner = deletion ?: return@TextButton
                    write({
                        when (val result = owner.delete(DeletePersonaGalleryCommand(currentIdentity, galleryId))) {
                            is UiPersonaGalleryDeletionReceipt.Deleted ->
                                "档案已删除；" + v6StorySyncLabel(result.sessionSync) +
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
