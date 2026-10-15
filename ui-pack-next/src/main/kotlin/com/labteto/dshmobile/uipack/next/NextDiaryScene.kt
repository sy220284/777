package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Native diary scene. Entries and conditional edits come from the original Chat Owner. */
@Composable
internal fun NextDiaryScene(
    gallery: PersonaGalleryUiPort,
    diary: DiaryUiPort,
    onBack: () -> Unit,
) {
    var subjects by remember { mutableStateOf(emptyList<UiPersonaGalleryEntry>()) }
    var galleryId by rememberSaveable { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf(emptyList<UiDiaryEntry>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var visibleCount by rememberSaveable { mutableIntStateOf(40) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<UiDiaryEntry?>(null) }
    var deactivating by remember { mutableStateOf<UiDiaryEntry?>(null) }
    var event by remember { mutableStateOf("") }
    var feeling by remember { mutableStateOf("") }
    var thoughts by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf("") }
    var unresolved by remember { mutableStateOf("") }
    var reflection by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(gallery) {
        try {
            val result = withContext(Dispatchers.IO) { gallery.read() }
            when (result) {
                is UiPersonaGalleryReadReceipt.Available -> {
                    subjects = result.entries
                    if (result.entries.none { it.id == galleryId }) galleryId = result.entries.firstOrNull()?.id
                }
                is UiPersonaGalleryReadReceipt.Failed ->
                    notice = result.message ?: "人物列表读取失败"
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { notice = failure.message ?: "人物列表读取失败" }
    }
    LaunchedEffect(diary, galleryId, refresh) {
        val target = galleryId
        entries = emptyList()
        if (target == null) return@LaunchedEffect
        loading = true
        try {
            entries = withContext(Dispatchers.IO) { diary.entries(target) }
                .sortedByDescending(UiDiaryEntry::createdAt)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { notice = failure.message ?: "日记读取失败" }
        finally { loading = false }
    }
    fun apply(action: suspend () -> UiDiaryReceipt) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                notice = when (val receipt = action()) {
                    UiDiaryReceipt.Applied -> { editing = null; deactivating = null; "修改已保存" }
                    UiDiaryReceipt.Stale -> "日记版本已变化，请刷新；原草稿保留"
                    is UiDiaryReceipt.Failed -> receipt.message ?: "操作失败"
                }
                refresh++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notice = failure.message ?: "操作失败" }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("人物日记", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack) { Text("返回") }
        }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (subjects.isEmpty()) Text("请先在人物图库中创建或导入人物")
        LazyColumn(Modifier.heightIn(max = 112.dp)) {
            items(subjects, key = { it.id }) { entry ->
                FilterChip(selected = galleryId == entry.id,
                    onClick = { galleryId = entry.id; visibleCount = 40 },
                    label = { Text(entry.name) })
            }
        }
        OutlinedTextField(query, { query = it; visibleCount = 40 },
            label = { Text("搜索当前人物日记") }, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = !busy, onClick = { refresh++ }) { Text("重新读取日记") }
        val filtered = entries.filter { it.active && (
            query.isBlank() || listOf(it.event, it.feeling, it.innerThought,
                it.relationshipMeaning, it.unresolvedEcho, it.privateReflection)
                .any { value -> value.contains(query, ignoreCase = true) })
        }
        Text("符合条件：${filtered.size} 条")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (loading) item { Text("正在读取…") }
            items(filtered.take(visibleCount), key = { it.id }) { record ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(record.privateReflection.ifBlank { record.event })
                        Text(record.personaName + " · " + java.text.DateFormat
                            .getDateInstance().format(java.util.Date(record.createdAt)))
                        TextButton(onClick = {
                            editing = record
                            event = record.event; feeling = record.feeling
                            thoughts = record.innerThought; relationship = record.relationshipMeaning
                            unresolved = record.unresolvedEcho; reflection = record.privateReflection
                        }) { Text("纠正日记") }
                        TextButton(onClick = { deactivating = record }) { Text("停用这条日记") }
                    }
                }
            }
            if (visibleCount < filtered.size) item {
                TextButton(onClick = { visibleCount += 40 }) { Text("读取更多") }
            }
        }
    }
    editing?.let { original ->
        AlertDialog(onDismissRequest = { if (!busy) editing = null },
            title = { Text("修订日记") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(event, { event = it }, label = { Text("实际事件") })
                OutlinedTextField(feeling, { feeling = it }, label = { Text("感受") })
                OutlinedTextField(thoughts, { thoughts = it }, label = { Text("想法") })
                OutlinedTextField(relationship, { relationship = it }, label = { Text("关系理解") })
                OutlinedTextField(unresolved, { unresolved = it }, label = { Text("未解之事") })
                OutlinedTextField(reflection, { reflection = it }, label = { Text("私人日记") })
            } },
            confirmButton = { TextButton(enabled = !busy && event.isNotBlank(),
                onClick = {
                    val id = galleryId ?: return@TextButton
                    apply { diary.correct(UiDiaryCorrection(id, original.id, original.updatedAt,
                        event, feeling, thoughts, relationship, unresolved, reflection)) }
                }) { Text("保存修订") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } })
    }
    deactivating?.let { original ->
        AlertDialog(onDismissRequest = { deactivating = null },
            title = { Text("确认停用") }, text = { Text("停用后该条日记不再参与记忆召回。") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                val id = galleryId ?: return@TextButton
                apply { diary.deactivate(id, original.id, original.updatedAt) }
            }) { Text("停用") } },
            dismissButton = { TextButton(onClick = { deactivating = null }) { Text("取消") } })
    }
}
