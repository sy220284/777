package com.labteto.dshmobile.uipack.v6

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.local.memory.*
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.uicontract.*
import com.shenyan.designsystem.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Private diary content and optimistic corrections belong to original Chat diary Owner. */
@Composable
fun V6DiaryScene(gallery: PersonaGalleryUiPort, diary: DiaryUiPort, onBack: () -> Unit) {
    var subjects by remember(gallery) { mutableStateOf(emptyList<UiPersonaGalleryEntry>()) }
    var target by remember { mutableStateOf<String?>(null) }
    var entries by remember(target) { mutableStateOf(emptyList<UiDiaryEntry>()) }
    var filter by remember(target) { mutableStateOf("") }
    var visible by remember(target) { mutableIntStateOf(40) }
    var revision by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var editing by remember(target) { mutableStateOf<UiDiaryEntry?>(null) }
    var deactivating by remember(target) { mutableStateOf<UiDiaryEntry?>(null) }
    var event by remember { mutableStateOf("") }
    var feeling by remember { mutableStateOf("") }
    var thoughts by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf("") }
    var unresolved by remember { mutableStateOf("") }
    var reflection by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(gallery) {
        try {
            when (val r = withContext(Dispatchers.IO) { gallery.read() }) {
                is UiPersonaGalleryReadReceipt.Available -> {
                    subjects = r.entries
                    if (target == null || subjects.none { it.id == target })
                        target = subjects.firstOrNull()?.id
                }
                is UiPersonaGalleryReadReceipt.Failed -> feedback = r.message ?: "人物库不可读取"
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { feedback = failure.message ?: "人物库读取失败" }
    }
    LaunchedEffect(diary, target, revision) {
        val id = target ?: return@LaunchedEffect
        loading = true
        try {
            val result = withContext(Dispatchers.IO) { diary.entries(id) }
            if (target == id) entries = result.sortedByDescending(UiDiaryEntry::createdAt)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { feedback = failure.message ?: "日记读取失败" }
        finally { loading = false }
    }
    fun submit(operation: suspend () -> UiDiaryReceipt) {
        if (loading) return
        loading = true
        scope.launch {
            try {
                feedback = when (val result = withContext(Dispatchers.IO) { operation() }) {
                    UiDiaryReceipt.Applied -> { editing = null; deactivating = null; "日记已提交原 Chat Owner" }
                    UiDiaryReceipt.Stale -> "日记版本已变化，修改草稿已保留"
                    is UiDiaryReceipt.Failed -> result.message ?: "日记修改失败"
                }
                revision++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { feedback = failure.message ?: "日记操作异常" }
            finally { loading = false }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("人物日记", style = MaterialTheme.typography.titleLarge)
        SyButton("返回", onBack, style = SyButtonStyle.GHOST)
        feedback?.let { SyNote(it) }
        subjects.forEach { entry ->
            SyButton(entry.name, { target = entry.id; visible = 40 },
                style = if (target == entry.id) SyButtonStyle.PRIMARY else SyButtonStyle.GHOST)
        }
        SyInput(filter, { filter = it; visible = 40 }, label = "搜索真实日记内容")
        SyButton("刷新日记", { revision++ }, style = SyButtonStyle.SECONDARY,
            enabled = !loading)
        val matching = entries.filter { entry ->
            entry.active && (filter.isBlank() ||
                listOf(entry.event, entry.feeling, entry.innerThought,
                    entry.relationshipMeaning, entry.unresolvedEcho, entry.privateReflection)
                    .any { it.contains(filter, ignoreCase = true) })
        }
        Text("当前匹配：" + matching.size + " 条")
        matching.take(visible).forEach { entry ->
            SyGlassCard(Modifier.fillMaxWidth()) {
                Text(entry.privateReflection.ifBlank { entry.event })
                Text(entry.personaName + " · " +
                    java.text.DateFormat.getDateTimeInstance().format(java.util.Date(entry.createdAt)))
                SyButton("纠正记录", {
                    editing = entry
                    event = entry.event; feeling = entry.feeling
                    thoughts = entry.innerThought; relationship = entry.relationshipMeaning
                    unresolved = entry.unresolvedEcho; reflection = entry.privateReflection
                }, enabled = !loading, style = SyButtonStyle.SECONDARY)
                SyButton("停用这条日记", { deactivating = entry },
                    enabled = !loading, style = SyButtonStyle.DANGER)
            }
        }
        if (matching.size > visible)
            SyButton("显示更多", { visible += 40 }, style = SyButtonStyle.SECONDARY)
    }
    editing?.let { row -> AlertDialog(
        onDismissRequest = { if (!loading) editing = null },
        title = { Text("修正日记") },
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            SyInput(event, { event = it }, label = "实际事件")
            SyInput(feeling, { feeling = it }, label = "感受")
            SyInput(thoughts, { thoughts = it }, label = "想法", singleLine = false)
            SyInput(relationship, { relationship = it }, label = "关系理解")
            SyInput(unresolved, { unresolved = it }, label = "未解之事")
            SyInput(reflection, { reflection = it }, label = "私密反思", singleLine = false)
        } },
        confirmButton = { TextButton(onClick = {
            val id = target ?: return@TextButton
            submit { diary.correct(UiDiaryCorrection(id, row.id, row.updatedAt,
                event, feeling, thoughts, relationship, unresolved, reflection)) }
        }, enabled = !loading && event.isNotBlank()) { Text("保存修订") } },
        dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } })
    }
    deactivating?.let { row -> AlertDialog(onDismissRequest = { deactivating = null },
        title = { Text("停用人物日记") },
        text = { Text("该条日记停用后不再参与 Chat 记忆召回。") },
        confirmButton = { TextButton(onClick = {
            val id = target ?: return@TextButton
            submit { diary.deactivate(id, row.id, row.updatedAt) }
        }, enabled = !loading) { Text("确认停用") } },
        dismissButton = { TextButton(onClick = { deactivating = null }) { Text("取消") } })
    }
}

/** Memory records have revision CAS and retrieval scope; this view never creates a new memory store. */
@Composable
fun V6MemoryManagementScene(memory: MemoryUiPort, settings: SettingsUiPort, onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(MemoryManagementPage()) }
    var cursor by remember { mutableStateOf<MemoryManagementCursor?>(null) }
    var editing by remember { mutableStateOf<MemoryRecord?>(null) }
    var forgetting by remember { mutableStateOf<MemoryRecord?>(null) }
    var content by remember(editing) { mutableStateOf(editing?.content.orEmpty()) }
    var pinned by remember(editing) { mutableStateOf(editing?.pinned ?: false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = settings.memoryContext()
    LaunchedEffect(memory, settings, query, cursor, generation) {
        val current = settings.memoryContext()
        val scopes = when(current.conversationMode) {
            LocalConversationMode.INDEPENDENT -> setOf(MemoryScope.GLOBAL)
            LocalConversationMode.PROJECT -> setOf(MemoryScope.GLOBAL, MemoryScope.PROJECT)
            LocalConversationMode.CONTINUATION -> MemoryScope.entries.toSet()
        }
        busy = true
        try {
            val result = withContext(Dispatchers.IO) {
                memory.memoryPage(MemoryManagementQuery(scopes,
                    current.projectId, current.lineageId, text = query, cursor = cursor))
            }
            if (settings.memoryContext() == current)
                page = result.copy(records =
                    (if (cursor == null) emptyList() else page.records) + result.records)
            else feedback = "记忆上下文已变化，请刷新"
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { feedback = failure.message ?: "记忆不可读取" }
        finally { busy = false }
    }
    fun write(command: MemoryUiCommand) {
        if (busy) return
        if (settings.memoryContext() != context) {
            feedback = "记忆作用域已变更，请刷新"
            return
        }
        busy = true
        scope.launch {
            try {
                feedback = when(val result = withContext(Dispatchers.IO) { memory.submit(command) }) {
                    is MemoryUiReceipt.Updated -> { editing = null; "该条记忆已保存" }
                    MemoryUiReceipt.Forgotten -> { forgetting = null; "该条记忆已删除" }
                    MemoryUiReceipt.StaleOrMissing -> "记忆版本已变化，不会覆盖"
                    is MemoryUiReceipt.CommittedWithWarning ->
                        "记忆写入已提交，但后续处理需注意：" + result.message
                    is MemoryUiReceipt.Failed -> result.message ?: "记忆操作失败"
                }
                cursor = null; generation++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { feedback = failure.message ?: "操作失败" }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("本地记忆管理", style = MaterialTheme.typography.titleLarge)
        SyButton("返回", onBack, style = SyButtonStyle.GHOST)
        SyInput(query, { query = it; cursor = null }, label = "搜索现有记忆")
        feedback?.let { SyNote(it) }
        Text("查询结果：" + page.records.size + "/" + page.totalMatching)
        page.records.distinctBy { it.id }.forEach { record ->
            SyGlassCard(Modifier.fillMaxWidth()) {
                Text(record.kind.name + " · " + (if (record.pinned) "置顶" else "普通"))
                Text(record.content)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SyButton("编辑", { editing = record }, enabled = !busy,
                        style = SyButtonStyle.SECONDARY)
                    SyButton("删除", { forgetting = record }, enabled = !busy,
                        style = SyButtonStyle.DANGER)
                }
            }
        }
        if (page.next != null) SyButton("加载更多", { cursor = page.next },
            enabled = !busy, style = SyButtonStyle.SECONDARY)
    }
    editing?.let { record -> AlertDialog(onDismissRequest = { editing = null },
        title = { Text("修改已有记忆") },
        text = { Column {
            SyInput(content, { content = it }, label = "记忆内容", singleLine = false)
            SySwitch("置顶", pinned, { pinned = it })
        } },
        confirmButton = { TextButton(onClick = {
            write(MemoryUiCommand.Update(record, content, pinned))
        }, enabled = !busy && content.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } })
    }
    forgetting?.let { record -> AlertDialog(onDismissRequest = { forgetting = null },
        title = { Text("确认删除记忆") },
        text = { Text(record.content) },
        confirmButton = { TextButton(onClick = {
            write(MemoryUiCommand.Forget(record))
        }, enabled = !busy) { Text("确认删除") } },
        dismissButton = { TextButton(onClick = { forgetting = null }) { Text("取消") } })
    }
}
