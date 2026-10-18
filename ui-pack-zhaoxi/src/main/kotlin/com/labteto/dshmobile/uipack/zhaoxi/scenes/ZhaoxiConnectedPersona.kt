package com.labteto.dshmobile.uipack.zhaoxi.scenes

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.local.chat.PersonaTransferFormat
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uipack.zhaoxi.components.*
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme
import com.labteto.dshmobile.uihost.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Composable
fun ZhaoxiAuthoringForm(authoring: PersonaAuthoringUiPort, snapshot: UiPersonaAuthoringSnapshot?, scope: UiSceneRenderScope) {
    var encodedDraft by rememberUiPrivateText("zhaoxi.persona.${snapshot?.galleryId ?: "new"}", snapshot?.revision) {
        Json.encodeToString(snapshot?.draft ?: UiPersonaDraft())
    }
    val draftState = object : MutableState<UiPersonaDraft> {
        override var value: UiPersonaDraft
            get() = Json.decodeFromString(encodedDraft)
            set(value) { encodedDraft = Json.encodeToString(value) }
        override fun component1(): UiPersonaDraft = value
        override fun component2(): (UiPersonaDraft) -> Unit = { value = it }
    }
    var draft by draftState
    var status by remember { mutableStateOf<String?>(null) }
    var revision by remember(snapshot?.revision) { mutableStateOf(snapshot?.revision) }
    var galleryId by remember(snapshot?.galleryId) { mutableStateOf(snapshot?.galleryId) }
    val jobs = rememberCoroutineScope()
    ZxPage("人物编辑", onBack = { scope.onBack() }) {
        status?.let { Text(it, color = ZhaoxiTheme.colors.ink2) }
        ZxField(draft.name, { draft = draft.copy(name = it) }, Modifier.fillMaxWidth(), placeholder = "姓名")
        ZxField(draft.coreIdentity, { draft = draft.copy(coreIdentity = it) }, Modifier.fillMaxWidth(), placeholder = "核心身份")
        ZxField(draft.franchise, { draft = draft.copy(franchise = it) }, Modifier.fillMaxWidth(), placeholder = "作品 / 世界")
        (listOf("opening") + UI_PERSONA_FACT_FIELDS + draft.fields.keys).distinct().forEach { field ->
            ZxField(draft.fields[field].orEmpty(), { draft = draft.copy(fields = draft.fields + (field to it)) },
                Modifier.fillMaxWidth(), placeholder = field)
        }
        ZxSection("事实")
        draft.facts.forEach { fact ->
            ZxField(fact.category, { value -> draft = draft.copy(facts = draft.facts.map { if (it.id == fact.id) it.copy(category = value) else it }) }, placeholder = "类别")
            ZxField(fact.content, { value -> draft = draft.copy(facts = draft.facts.map { if (it.id == fact.id) it.copy(content = value) else it }) }, Modifier.fillMaxWidth(), placeholder = "事实内容")
            ZxChip("移除事实", false) { draft = draft.copy(facts = draft.facts.filterNot { it.id == fact.id }) }
        }
        ZxChip("添加事实", false) { draft = draft.copy(facts = draft.facts + UiPersonaFact(java.util.UUID.randomUUID().toString(), "customFacts", "")) }
        ZxSection("世界书")
        draft.lore.forEach { lore ->
            ZxField(lore.title, { value -> draft = draft.copy(lore = draft.lore.map { if (it.id == lore.id) it.copy(title = value) else it }) }, placeholder = "标题")
            ZxField(lore.content, { value -> draft = draft.copy(lore = draft.lore.map { if (it.id == lore.id) it.copy(content = value) else it }) }, Modifier.fillMaxWidth(), placeholder = "内容")
            ZxField(lore.keywords.joinToString("、"), { value -> draft = draft.copy(lore = draft.lore.map { if (it.id == lore.id) it.copy(keywords = value.split("、").filter(String::isNotBlank)) else it }) }, placeholder = "关键词（顿号分隔）")
            ZxChip("移除条目", false) { draft = draft.copy(lore = draft.lore.filterNot { it.id == lore.id }) }
        }
        ZxChip("添加世界书条目", false) { draft = draft.copy(lore = draft.lore + UiPersonaLore(java.util.UUID.randomUUID().toString(), "", "")) }
        ZxSection("行为调节")
        ZxField(draft.samplingTemperaturePosition?.toString().orEmpty(), { value ->
            if (value.isEmpty() || value.toIntOrNull()?.let { it in 0..100 } == true) draft = draft.copy(samplingTemperaturePosition = value.toIntOrNull())
        }, placeholder = "采样位置 0–100，留空沿用全局")
        ZxChip("恢复全局采样", false) { draft = draft.copy(samplingTemperaturePosition = null, legacySamplingPosition = null) }
        ZxButton("保存归档", { jobs.launch {
            val id = galleryId; val rev = revision
            val result = if (id != null && rev != null) authoring.save(id, rev, draft) else authoring.create(draft)
            when (result) {
                is UiPersonaAuthoringReceipt.Saved -> { galleryId = result.galleryId; revision = result.revision; status = "归档已保存；应用到会话请从人物档案选择"; scope.onBack() }
                else -> status = result.toString()
            }
        } }, enabled = draft.name.isNotBlank())
    }
}

@Composable
fun ZhaoxiPersonaProfileScene(galleryId: String, gallery: PersonaGalleryUiPort?, selection: PersonaSelectionUiPort?,
    stories: PersonaStoryUiPort?, transfer: PersonaTransferUiPort?, scope: UiSceneRenderScope) {
    var revision by remember { mutableStateOf(0) }
    val receipt by produceState<UiPersonaGalleryReadReceipt?>(null, galleryId, revision) { value = gallery?.read() }
    val entry = (receipt as? UiPersonaGalleryReadReceipt.Available)?.entries?.firstOrNull { it.id == galleryId }
    val jobs = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    val save = rememberUiDocumentSaver("application/octet-stream") { status = it.toString() }
    ZxPage(entry?.name ?: "人物档案", onBack = { scope.onBack() }) {
        status?.let { Text(it, color = ZhaoxiTheme.colors.ink2) }
        if (receipt is UiPersonaGalleryReadReceipt.Failed) Text((receipt as UiPersonaGalleryReadReceipt.Failed).message ?: "读取失败")
        ZxNavRow("编辑人物与世界书") { scope.onNavigate(UiSceneId("chat.persona_editor")) }
        ZxNavRow("画像") { scope.onNavigate(UiSceneId("chat.gallery_portrait")) }
        ZxNavRow("日记") { scope.onNavigate(UiSceneId("chat.diary")) }
        selection?.let { owner ->
            val snap by owner.snapshots.collectAsState(initial = owner.snapshot())
            ZxButton("用于当前聊天", { snap.identity?.let { status = owner.submit(PersonaSelectionUiCommand.SelectForCurrentChat(it, galleryId)).userHint() } }, enabled = snap.canSelectForCurrentChat)
            ZxButton("开始新故事", { snap.identity?.let { status = owner.submit(PersonaSelectionUiCommand.StartStory(it, galleryId, null, true)).userHint() } }, enabled = snap.canStartStory)
            entry?.stories?.forEach { story ->
                var notes by rememberUiPrivateText("zhaoxi.story.$galleryId.${story.id}", story.notes) { story.notes }
                ZxRow(story.title, "${story.messageCount} 条消息 · ${story.storyStage}")
                ZxButton("继续故事", { snap.identity?.let { status = owner.submit(PersonaSelectionUiCommand.StartStory(it, galleryId, story.id, false)).userHint() } }, enabled = snap.canStartStory)
                ZxField(notes, { notes = it }, placeholder = "故事备注")
                if (stories != null) ZxButton("保存备注", { jobs.launch {
                    status = stories.submit(PersonaStoryUiCommand.SaveDetails(snap.identity, galleryId, story.id, notes, story.storyStage)).toString(); revision++
                } })
            }
        }
        transfer?.let { owner ->
            PersonaTransferFormat.entries.forEach { format -> ZxButton("导出 ${format.extension}", { jobs.launch {
                when (val result = owner.export(ExportPersonaDocumentCommand(galleryId, format))) {
                    is UiPersonaExportReceipt.Ready -> save(UiExportFile("persona.${format.extension}", result.document.bytes))
                    is UiPersonaExportReceipt.Failed -> status = result.message ?: "导出失败"
                }
            } }) }
        }
        ZxNavRow("删除人物") { scope.onNavigate(UiSceneId("chat.gallery_delete")) }
    }
}

@Composable
fun ZhaoxiPortraitEditor(port: PersonaPortraitUiPort, galleryId: String, scope: UiSceneRenderScope) {
    val jobs = rememberCoroutineScope()
    var revision by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    val preview by produceState<UiPersonaPortraitPreviewReceipt?>(null, galleryId, revision) { value = port.preview(galleryId) }
    val pick = rememberUiAttachmentPickerFor<String>(arrayOf("image/*"), { status = it }) { target, uri ->
        if (uri != null) jobs.launch { status = port.submit(PersonaPortraitUiCommand.Import(target, uri)).toString(); revision++ }
    }
    ZxPage("人物画像", onBack = { scope.onBack() }) {
        val bytes = (preview as? UiPersonaPortraitPreviewReceipt.Ready)?.bytes
        val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, bytes) {
            if (bytes != null) try {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                        decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                        var sample = 1
                        while (maxOf(info.size.width, info.size.height) / sample > 1600) sample *= 2
                        decoder.setTargetSampleSize(sample)
                    }.asImageBitmap()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { status = failure.message ?: "画像无法解码" }
        }
        image?.let { Image(it, "人物画像", Modifier.fillMaxWidth().height(280.dp)) }
        status?.let { Text(it, color = ZhaoxiTheme.colors.ink2) }
        ZxButton("导入画像", { pick(galleryId) })
        ZxButton("移除画像", { jobs.launch { status = port.submit(PersonaPortraitUiCommand.Remove(galleryId)).toString(); revision++ } })
    }
}

@Composable
fun ZhaoxiCurrentPersonaTuning(port: CurrentPersonaUiPort, scope: UiSceneRenderScope) {
    val initial = remember(port) { port.read() }
    val snapshot = (initial as? UiCurrentPersonaReceipt.Loaded)?.snapshot
    val jobs = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var value by rememberUiPrivateText("zhaoxi.tuning.${snapshot?.target?.personaId}") { snapshot?.samplingPosition?.toString().orEmpty() }
    ZxPage("当前人物采样", onBack = { scope.onBack() }) {
        if (snapshot == null) ZxEmpty("当前没有人物", "先在会话中选择人物")
        else {
            ZxField(value, { value = it }, placeholder = "采样位置 0–100")
            ZxButton("应用到当前人物", { jobs.launch {
                val position = value.toIntOrNull()
                status = if (position == null || position !in 0..100) "请输入 0–100 的整数"
                else port.save(snapshot.target, snapshot.draft.copy(samplingTemperaturePosition = position)).toString()
            } })
            ZxButton("恢复全局采样", { jobs.launch {
                status = port.save(snapshot.target, snapshot.draft.copy(samplingTemperaturePosition = null, legacySamplingPosition = null)).toString()
            } })
        }
        status?.let { Text(it, color = ZhaoxiTheme.colors.ink2) }
    }
}
