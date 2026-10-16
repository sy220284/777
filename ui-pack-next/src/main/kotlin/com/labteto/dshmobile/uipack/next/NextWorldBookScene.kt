package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Existing persona lore/knowledge boundary, edited with archive revisions; no second world-book store. */
@Composable
internal fun NextWorldBookScene(
    gallery: PersonaGalleryUiPort,
    authoring: PersonaAuthoringUiPort,
    onBack: () -> Unit,
) {
    var galleryList by remember { mutableStateOf(emptyList<UiPersonaGalleryEntry>()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var current by remember { mutableStateOf<UiPersonaAuthoringSnapshot?>(null) }
    var editing by remember { mutableStateOf<UiPersonaLore?>(null) }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var keywords by remember { mutableStateOf("") }
    var secondaryKeywords by remember { mutableStateOf("") }
    var stage by remember { mutableStateOf("") }
    var alwaysOn by remember { mutableStateOf(false) }
    var priority by remember { mutableIntStateOf(50) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var deletion by remember { mutableStateOf<UiPersonaLore?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(gallery) {
        try {
            when (val receipt = withContext(Dispatchers.IO) { gallery.read() }) {
                is UiPersonaGalleryReadReceipt.Available -> {
                    galleryList = receipt.entries
                    if (receipt.entries.none { it.id == selected }) selected = receipt.entries.firstOrNull()?.id
                }
                is UiPersonaGalleryReadReceipt.Failed ->
                    notice = receipt.message ?: "人物档案读取失败"
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { notice = failure.message ?: "人物档案读取失败" }
    }
    LaunchedEffect(selected, revision) {
        current = null
        val id = selected ?: return@LaunchedEffect
        try {
            when (val receipt = withContext(Dispatchers.IO) { authoring.read(id) }) {
                is UiPersonaAuthoringReceipt.Loaded -> current = receipt.snapshot
                is UiPersonaAuthoringReceipt.Failed -> notice = receipt.message ?: "世界资料无法读取"
                else -> notice = "人物档案当前不可编辑"
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { notice = failure.message ?: "世界资料读取失败" }
    }

    fun mutate(change: (List<UiPersonaLore>) -> List<UiPersonaLore>) {
        val copy = current ?: return
        if (busy) return
        busy = true
        scope.launch {
            try {
                val updated = copy.draft.copy(lore = change(copy.draft.lore))
                notice = when (val result = withContext(Dispatchers.IO) {
                    authoring.save(copy.galleryId, copy.revision, updated)
                }) {
                    is UiPersonaAuthoringReceipt.Saved -> {
                        editing = null
                        deletion = null
                        revision++
                        "世界资料已保存；当前故事如需应用请重新选择人物"
                    }
                    is UiPersonaAuthoringReceipt.Rejected -> "档案已变化或修改被拒绝：${result.reason}"
                    is UiPersonaAuthoringReceipt.Failed -> result.message ?: "保存失败，草稿未丢失"
                    else -> "保存状态未知，请重新读取档案"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notice = failure.message ?: "保存失败，草稿未丢失" }
            finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("人物世界资料", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onBack) { Text("返回") }
        Text("人物档案中的 Lore 只描述世界背景和知识边界，不修改聊天运行机制。")
        notice?.let { Text(it) }
        galleryList.forEach { entry ->
            FilterChip(selected = selected == entry.id, onClick = { selected = entry.id; editing = null },
                label = { Text(entry.name) })
        }
        val value = current
        if (value == null) Text("请选择一个已保存的人物档案")
        else {
            Text("世界资料 ${value.draft.lore.size} 条")
            Button(enabled = !busy, onClick = {
                editing = UiPersonaLore(UUID.randomUUID().toString(), "", "")
                title = ""; content = ""; keywords = ""; secondaryKeywords = ""; stage = ""
                alwaysOn = false; priority = 50
            }) { Text("新增世界资料") }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(value.draft.lore, key = { it.id }) { entry ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(entry.title, style = MaterialTheme.typography.titleMedium)
                            Text(entry.content, maxLines = 5)
                            Text("优先级：${entry.priority} · ${if (entry.alwaysOn) "常驻" else "关键词触发"}")
                            TextButton(enabled = !busy, onClick = {
                                editing = entry
                                title = entry.title; content = entry.content
                                keywords = entry.keywords.joinToString(",")
                                secondaryKeywords = entry.secondaryKeywords.joinToString(",")
                                stage = entry.stage
                                alwaysOn = entry.alwaysOn; priority = entry.priority
                            }) { Text("编辑") }
                            TextButton(enabled = !busy, onClick = { deletion = entry }) { Text("删除") }
                        }
                    }
                }
            }
        }
    }
    editing?.let { original ->
        AlertDialog(onDismissRequest = { if (!busy) editing = null },
            title = { Text("世界资料编辑") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(title, { title = it.take(150) }, label = { Text("标题") })
                OutlinedTextField(content, { content = it.take(6000) }, label = { Text("背景信息") },
                    minLines = 3)
                OutlinedTextField(keywords, { keywords = it.take(300) },
                    label = { Text("关键词，逗号分隔") })
                OutlinedTextField(secondaryKeywords, { secondaryKeywords = it.take(300) },
                    label = { Text("次级关键词，逗号分隔") })
                OutlinedTextField(stage, { stage = it.take(150) },
                    label = { Text("条目生效阶段") })
                Row {
                    Switch(alwaysOn, onCheckedChange = { alwaysOn = it })
                    Text("常驻资料")
                }
                Text("优先级：$priority")
                Slider(priority.toFloat(), { priority = it.toInt() }, valueRange = 0f..100f)
            } },
            confirmButton = { TextButton(enabled = !busy && title.isNotBlank() && content.isNotBlank(),
                onClick = {
                    val revised = original.copy(title = title.trim(), content = content.trim(),
                        keywords = keywords.split(',','，').map(String::trim).filter(String::isNotBlank).distinct(),
                        secondaryKeywords = secondaryKeywords.split(',','，').map(String::trim)
                            .filter(String::isNotBlank).distinct(),
                        stage = stage.trim(), alwaysOn = alwaysOn, priority = priority)
                    mutate { existing ->
                        val index = existing.indexOfFirst { it.id == original.id }
                        if (index < 0) existing + revised else existing.toMutableList().apply { set(index, revised) }
                    }
                }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } })
    }
    deletion?.let { entry ->
        AlertDialog(onDismissRequest = { deletion = null },
            title = { Text("删除世界资料？") },
            text = { Text(entry.title) },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                mutate { existing -> existing.filterNot { it.id == entry.id } }
            }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deletion = null }) { Text("取消") } })
    }
}
