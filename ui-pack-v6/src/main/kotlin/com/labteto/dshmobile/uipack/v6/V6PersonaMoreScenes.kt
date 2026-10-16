package com.labteto.dshmobile.uipack.v6

import android.graphics.ImageDecoder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.uicontract.*
import com.shenyan.designsystem.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Persona authoring owns only an editable draft. Durable save and revision CAS belong to Chat. */
@Composable
fun V6PersonaAuthoringScene(
    owner: PersonaAuthoringUiPort,
    gallery: PersonaGalleryUiPort,
    selection: PersonaSelectionUiPort,
    worldBook: Boolean,
    onBack: () -> Unit,
) {
    var galleryEntries by remember(gallery) { mutableStateOf(emptyList<UiPersonaGalleryEntry>()) }
    var galleryId by remember { mutableStateOf<String?>(null) }
    var revision by remember(galleryId) { mutableStateOf(0L) }
    var draft by remember(galleryId) { mutableStateOf(UiPersonaDraft()) }
    var readComplete by remember(galleryId) { mutableStateOf(galleryId == null) }
    var dirty by remember(galleryId) { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var suggestion by remember { mutableStateOf("") }
    var loreTitle by remember { mutableStateOf("") }
    var loreContent by remember { mutableStateOf("") }
    var confirmDiscard by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(gallery) {
        try {
            when (val result = withContext(Dispatchers.IO) { gallery.read() }) {
                is UiPersonaGalleryReadReceipt.Available -> galleryEntries = result.entries
                is UiPersonaGalleryReadReceipt.Failed -> feedback = result.message ?: "人物档案读取失败"
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { feedback = failure.message ?: "人物档案读取失败" }
    }
    LaunchedEffect(owner, galleryId) {
        val id = galleryId ?: return@LaunchedEffect
        readComplete = false
        loading = true
        try {
            when (val receipt = withContext(Dispatchers.IO) { owner.read(id) }) {
                is UiPersonaAuthoringReceipt.Loaded -> {
                    if (galleryId == id) {
                        draft = receipt.snapshot.draft
                        revision = receipt.snapshot.revision
                        dirty = false
                        readComplete = true
                    }
                }
                is UiPersonaAuthoringReceipt.Failed -> feedback = receipt.message ?: "档案读取失败"
                else -> feedback = "人物档案版本失效"
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { feedback = failure.message ?: "读取人物档案失败" }
        finally { loading = false }
    }
    fun act(operation: suspend () -> UiPersonaAuthoringReceipt) {
        if (loading) return
        loading = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { operation() }
                feedback = when(result) {
                    is UiPersonaAuthoringReceipt.Saved -> {
                        galleryId = result.galleryId
                        revision = result.revision
                        dirty = false
                        "人物档案已保存到 Chat Owner；选择到当前聊天是另一步操作"
                    }
                    is UiPersonaAuthoringReceipt.Suggested -> {
                        draft = result.draft
                        dirty = true
                        "模型建议已加入待编辑草稿，仍需手动保存"
                    }
                    is UiPersonaAuthoringReceipt.Rejected -> "操作被拒绝：" + result.reason.name
                    is UiPersonaAuthoringReceipt.Failed -> result.message ?: "人物档案操作失败"
                    is UiPersonaAuthoringReceipt.Loaded -> "已读取档案"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { feedback = failure.message ?: "人物操作失败" }
            finally { loading = false }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (worldBook) "人物世界书" else "人物编辑",
            style = MaterialTheme.typography.titleLarge)
        SyButton("返回", { if (dirty) confirmDiscard = true else onBack() },
            style = SyButtonStyle.GHOST)
        feedback?.let { SyNote(it) }
        if (loading) SyNote("正在读取或写入人物档案")
        if (!worldBook) {
            SyButton("新建人物", { galleryId = null; draft = UiPersonaDraft(); revision = 0L;
                dirty = false; readComplete = true }, style = SyButtonStyle.SECONDARY)
        }
        galleryEntries.forEach { entry ->
            SyButton(entry.name, { if (!dirty) galleryId = entry.id
                else feedback = "请先保存或放弃当前草稿再切换人物" },
                style = if (entry.id == galleryId) SyButtonStyle.PRIMARY else SyButtonStyle.GHOST)
        }
        if (worldBook && galleryId == null) SyNote("先选择要编辑世界书的已保存人物")
        if (readComplete && (!worldBook || galleryId != null)) {
            if (!worldBook) {
                SyInput(draft.name, { draft = draft.copy(name = it); dirty = true }, label = "姓名")
                SyInput(draft.coreIdentity, { draft = draft.copy(coreIdentity = it); dirty = true },
                    label = "人物核心身份", singleLine = false)
                SyInput(draft.franchise, { draft = draft.copy(franchise = it); dirty = true },
                    label = "作品/世界来源")
                UI_PERSONA_FACT_FIELDS.forEach { field ->
                    SyInput(draft.fields[field].orEmpty(), {
                        draft = draft.copy(fields = draft.fields + (field to it))
                        dirty = true
                    }, label = field, singleLine = false)
                }
                SyInput(suggestion, { suggestion = it },
                    label = "额外模型辅助设计要求（明确点击后才调用）", singleLine = false)
                val identity = selection.snapshot().identity
                SyButton("用模型辅助补充草稿", {
                    if (identity != null && suggestion.isNotBlank())
                        act { owner.suggest(identity, draft, suggestion) }
                }, enabled = !loading && identity != null && suggestion.isNotBlank(),
                    style = SyButtonStyle.SECONDARY)
            } else {
                Text("世界书条目：" + draft.lore.size)
                draft.lore.forEach { lore ->
                    SyGlassCard(Modifier.fillMaxWidth()) {
                        Text(lore.title, style = MaterialTheme.typography.titleMedium)
                        Text(lore.content)
                        Text("关键词：" + lore.keywords.joinToString())
                        SyButton("删除这条世界书", {
                            draft = draft.copy(lore = draft.lore.filterNot { it.id == lore.id })
                            dirty = true
                        }, style = SyButtonStyle.DANGER)
                    }
                }
                SyInput(loreTitle, { loreTitle = it }, label = "世界书标题")
                SyInput(loreContent, { loreContent = it }, label = "世界书内容", singleLine = false)
                SyButton("添加世界书条目", {
                    if (loreTitle.isNotBlank() && loreContent.isNotBlank()) {
                        draft = draft.copy(lore = draft.lore + UiPersonaLore(
                            "v6-" + java.util.UUID.randomUUID().toString(), loreTitle.trim(),
                            loreContent.trim()))
                        loreTitle = ""; loreContent = ""
                        dirty = true
                    }
                }, enabled = loreTitle.isNotBlank() && loreContent.isNotBlank())
            }
            SyButton(if (worldBook) "保存世界书" else "保存人物档案", {
                val id = galleryId
                val value = draft
                act { if (id == null) owner.create(value) else owner.save(id, revision, value) }
            }, enabled = !loading && (worldBook || draft.name.isNotBlank()))
        }
    }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
        title = { Text("尚有未保存的修改") },
        text = { Text("离开会丢弃当前编辑草稿。") },
        confirmButton = { TextButton(onClick = {
            confirmDiscard = false; onBack()
        }) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } })
}

/** Active persona tuning uses a different revision-bearing target from archive authoring. */
@Composable
fun V6CurrentPersonaTuningScene(owner: CurrentPersonaUiPort, onBack: () -> Unit) {
    var loaded by remember(owner) { mutableStateOf<UiCurrentPersonaSnapshot?>(null) }
    var draft by remember(owner) { mutableStateOf(UiPersonaDraft()) }
    var description by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(owner, revision) {
        when (val receipt = owner.read()) {
            is UiCurrentPersonaReceipt.Loaded -> {
                loaded = receipt.snapshot
                if (!dirty) draft = receipt.snapshot.draft
            }
            is UiCurrentPersonaReceipt.Failed -> feedback = receipt.message ?: "当前人物不可用"
            else -> feedback = "当前人物需要重新载入"
        }
    }
    fun submit(suggest: Boolean) {
        val target = loaded?.target ?: return
        if (busy) return
        busy = true
        val body = draft
        val intent = description
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (suggest) owner.suggest(target, body, intent) else owner.save(target, body)
                }
                feedback = when(result) {
                    UiCurrentPersonaReceipt.Saved -> {
                        dirty = false
                        "已通过 Chat Owner 更新当前人物"
                    }
                    is UiCurrentPersonaReceipt.Suggested -> {
                        draft = result.draft
                        dirty = true
                        "建议已填入草稿，仍需保存"
                    }
                    is UiCurrentPersonaReceipt.Failed -> result.message ?: "当前人物修改失败"
                    else -> "人物版本已变化，请重新读取"
                }
                if (result == UiCurrentPersonaReceipt.Saved) revision++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { feedback = failure.message ?: "人物操作异常" }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("当前人物微调", style = MaterialTheme.typography.titleLarge)
        SyButton("返回", { if (dirty) confirmExit = true else onBack() },
            style = SyButtonStyle.GHOST)
        feedback?.let { SyNote(it) }
        val target = loaded?.target
        if (target == null) SyNote("当前聊天尚无可修改的人物")
        else {
            SyInput(draft.name, { draft = draft.copy(name = it); dirty = true }, label = "人物名字")
            SyInput(draft.coreIdentity, { draft = draft.copy(coreIdentity = it); dirty = true },
                label = "核心身份", singleLine = false)
            UI_PERSONA_FACT_FIELDS.forEach { field ->
                SyInput(draft.fields[field].orEmpty(), {
                    draft = draft.copy(fields = draft.fields + (field to it)); dirty = true
                }, label = field, singleLine = false)
            }
            Text("采样偏好：" + (draft.samplingTemperaturePosition ?: loaded!!.samplingPosition))
            Slider(value = (draft.samplingTemperaturePosition ?: loaded!!.samplingPosition).toFloat(),
                onValueChange = {
                    draft = draft.copy(samplingTemperaturePosition = it.roundToInt())
                    dirty = true
                }, valueRange = 0f..100f)
            SyInput(description, { description = it }, label = "要求模型建议（可能消耗额度）",
                singleLine = false)
            SyButton("额外模型建议", { submit(true) },
                enabled = !busy && description.isNotBlank(), style = SyButtonStyle.SECONDARY)
            SyButton("保存到当前聊天人物", { submit(false) },
                enabled = !busy && draft.name.isNotBlank())
        }
    }
    if (confirmExit) AlertDialog(onDismissRequest = { confirmExit = false },
        title = { Text("丢弃未保存的当前人物修改？") },
        confirmButton = { TextButton(onClick = { confirmExit = false; onBack() }) {
            Text("放弃修改")
        } },
        dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("继续编辑") } })
}

/** Private portraits are surfaced only via Owner's bounded bytes and OpenDocument SAF grant. */
@Composable
fun V6PortraitScene(
    gallery: PersonaGalleryUiPort, owner: PersonaPortraitUiPort, onBack: () -> Unit,
) {
    var people by remember(gallery) { mutableStateOf(emptyList<UiPersonaGalleryEntry>()) }
    var galleryId by remember { mutableStateOf<String?>(null) }
    var image by remember(galleryId) { mutableStateOf<ImageBitmap?>(null) }
    var feedback by remember(galleryId) { mutableStateOf<String?>(null) }
    var busy by remember(galleryId) { mutableStateOf(false) }
    var reload by remember(galleryId) { mutableIntStateOf(0) }
    var confirmRemoval by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(gallery) {
        try {
            when(val result = withContext(Dispatchers.IO) { gallery.read() }) {
                is UiPersonaGalleryReadReceipt.Available -> people = result.entries
                is UiPersonaGalleryReadReceipt.Failed -> feedback = result.message ?: "人物库不可用"
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { feedback = failure.message ?: "人物库不可用" }
    }
    LaunchedEffect(galleryId, reload) {
        val id = galleryId ?: return@LaunchedEffect
        image = null
        try {
            val result = withContext(Dispatchers.IO) {
                when(val data = owner.preview(id)) {
                    is UiPersonaPortraitPreviewReceipt.Ready ->
                        ImageDecoder.decodeBitmap(
                            ImageDecoder.createSource(ByteBuffer.wrap(data.bytes))
                        ) { decoder, info, _ ->
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            var sample = 1
                            while (maxOf(info.size.width, info.size.height) / sample > 1600) sample *= 2
                            decoder.setTargetSampleSize(sample)
                        }.asImageBitmap() to null
                    UiPersonaPortraitPreviewReceipt.Missing -> null to "此人物尚无头像"
                    is UiPersonaPortraitPreviewReceipt.Failed -> null to (data.message ?: "头像读取失败")
                }
            }
            if (galleryId == id) { image = result.first; feedback = result.second }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { feedback = failure.message ?: "头像无法解码" }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = galleryId
        if (id != null && uri != null && !busy) {
            busy = true
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        owner.submit(PersonaPortraitUiCommand.Import(id, uri.toString()))
                    }
                    if (galleryId == id) {
                        feedback = when(result) {
                            is UiPersonaPortraitReceipt.Updated ->
                                "头像已更新" + (result.cleanupWarning?.let { "；清理提醒：" + it } ?: "")
                            UiPersonaPortraitReceipt.Missing -> "人物记录已删除"
                            is UiPersonaPortraitReceipt.Failed -> result.message ?: "头像更新失败"
                        }
                        reload++
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { feedback = failure.message ?: "头像导入失败" }
                finally { busy = false }
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("人物头像", style = MaterialTheme.typography.titleLarge)
        SyButton("返回", onBack, style = SyButtonStyle.GHOST)
        feedback?.let { SyNote(it) }
        people.forEach { entry ->
            SyButton(entry.name, { galleryId = entry.id },
                style = if (galleryId == entry.id) SyButtonStyle.PRIMARY else SyButtonStyle.GHOST)
        }
        val id = galleryId
        if (id != null) {
            image?.let { Image(it, "人物头像", Modifier.fillMaxWidth().heightIn(max = 340.dp)) }
            SyButton("选择本地头像", { picker.launch(arrayOf("image/*")) }, enabled = !busy)
            SyButton("移除头像", { confirmRemoval = true },
                enabled = !busy, style = SyButtonStyle.DANGER)
        }
    }
    if (confirmRemoval) AlertDialog(onDismissRequest = { confirmRemoval = false },
        title = { Text("确认移除人物头像") },
        confirmButton = { TextButton(onClick = {
            confirmRemoval = false
            val id = galleryId ?: return@TextButton
            busy = true
            scope.launch {
                try {
                    feedback = when(val result = withContext(Dispatchers.IO) {
                        owner.submit(PersonaPortraitUiCommand.Remove(id))
                    }) {
                        is UiPersonaPortraitReceipt.Updated -> "头像已从人物档案移除"
                        UiPersonaPortraitReceipt.Missing -> "人物档案已不存在"
                        is UiPersonaPortraitReceipt.Failed -> result.message ?: "移除头像失败"
                    }
                    reload++
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { feedback = failure.message ?: "移除头像异常" }
                finally { busy = false }
            }
        }) { Text("确认移除") } },
        dismissButton = { TextButton(onClick = { confirmRemoval = false }) { Text("取消") } })
}
