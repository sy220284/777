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
import androidx.compose.ui.unit.sp
import com.labteto.dshmobile.local.chat.PersonaTransferFormat
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uipack.zhaoxi.components.*
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme
import com.labteto.dshmobile.uihost.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Preserve actual archive/foreground state; never expose a Kotlin receipt dump. */
internal fun UiPersonaAuthoringReceipt.zxAuthoringFeedback(): String = when (this) {
    is UiPersonaAuthoringReceipt.Saved -> "人物档案已保存"
    is UiPersonaAuthoringReceipt.Rejected -> "无法保存，请刷新人物档案后重试"
    is UiPersonaAuthoringReceipt.Failed -> message ?: "人物保存失败"
    is UiPersonaAuthoringReceipt.Loaded -> "人物档案已读取"
    is UiPersonaAuthoringReceipt.Suggested -> "建议已生成"
}

internal fun UiPersonaPortraitReceipt.zxPortraitFeedback(): String = when (this) {
    is UiPersonaPortraitReceipt.Updated ->
        cleanupWarning?.let { "画像已更新，清理旧图片时出现问题：$it" } ?: "画像已更新"
    UiPersonaPortraitReceipt.Missing -> "人物或画像已不存在"
    is UiPersonaPortraitReceipt.Failed -> message ?: "画像操作失败"
}

internal fun UiPersonaStoryReceipt.zxStoryFeedback(): String = when (this) {
    is UiPersonaStoryReceipt.Saved -> when (sessionSync) {
        UiStorySessionSync.APPLIED -> "故事已保存并同步到当前会话"
        UiStorySessionSync.NOT_BOUND -> "故事已保存"
        UiStorySessionSync.DEFERRED -> "故事已保存，当前会话稍后同步"
    }
    UiPersonaStoryReceipt.Missing -> "故事已不存在，请刷新"
    is UiPersonaStoryReceipt.Failed -> message ?: "保存失败"
}

internal fun UiCurrentPersonaReceipt.zxTuningFeedback(): String = when (this) {
    UiCurrentPersonaReceipt.Saved -> "人物设置已应用"
    UiCurrentPersonaReceipt.Rejected -> "人物状态已变化，请刷新后重试"
    is UiCurrentPersonaReceipt.Failed -> message ?: "保存失败"
    is UiCurrentPersonaReceipt.Loaded -> "已读取人物设置"
    is UiCurrentPersonaReceipt.Suggested -> "建议已生成"
}

/** 设计包 E8.18：字段标签始终可见，存储键保持原契约不变。 */
private fun personaFieldTitle(key: String): String = when (key) {
    "personality" -> "性格特征"
    "valuesAndTradeoffs" -> "价值取向与取舍"
    "selfNarrative" -> "自我认知"
    "subjectiveBeliefs" -> "主观信念"
    "identityGap" -> "身份认知差异"
    "preferencesAndHabits" -> "喜好与习惯"
    "voiceStyle" -> "说话风格"
    "biography" -> "人物经历"
    "relationships" -> "人物关系"
    "customFacts" -> "补充事实"
    else -> key
}

/** The E8.18 primary editor consists of 3 identity fields plus 10 canonical fact fields. */
internal fun zxPersonaMainEditorKeys(): List<String> =
    listOf("name", "coreIdentity", "franchise") + UI_PERSONA_FACT_FIELDS

internal fun zxPersonaCanonicalDraft(draft: UiPersonaDraft): UiPersonaDraft =
    draft.copy(fields = draft.fields.filterKeys { it in UI_PERSONA_FACT_FIELDS })

@Composable
private fun ZhaoxiPersonaField(title: String, value: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = ZhaoxiTheme.colors.ink2, fontSize = 13.sp)
        ZxField(value, onChange, Modifier.fillMaxWidth(), placeholder = "填写$title")
    }
}

@Composable
fun ZhaoxiAuthoringForm(authoring: PersonaAuthoringUiPort, snapshot: UiPersonaAuthoringSnapshot?, scope: UiSceneRenderScope, initialTab: Int = 0, identity: UiSessionIdentity? = null) {
    var encodedDraft by rememberUiPrivateText("zhaoxi.persona.${snapshot?.galleryId ?: "new"}", snapshot?.revision) {
        Json.encodeToString(zxPersonaCanonicalDraft(snapshot?.draft ?: UiPersonaDraft()))
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
    var selectedTab by rememberSaveable(snapshot?.galleryId, initialTab) { mutableIntStateOf(initialTab) }
    val original = remember(snapshot?.galleryId, snapshot?.revision) { zxPersonaCanonicalDraft(snapshot?.draft ?: UiPersonaDraft()) }
    var confirmLeave by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var aiPrompt by remember { mutableStateOf("") }
    var aiBusy by remember { mutableStateOf(false) }
    var proposed by remember { mutableStateOf<UiPersonaDraft?>(null) }
    var selectedFactId by remember { mutableStateOf<String?>(null) }
    var selectedLoreId by remember { mutableStateOf<String?>(null) }
    var proposalSource by remember { mutableStateOf<String?>(null) }
    fun leave() { if (draft != original) confirmLeave = true else scope.onBack() }
    ZxPage(if (galleryId == null) "新建人物" else "人物编辑", onBack = ::leave) {
        status?.let { Text(it, color = ZhaoxiTheme.colors.ink2) }
        ZxSection("人物档案")
        ZxBadge(if (draft != original) "有未保存修改" else "内容已同步")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ZxAvatar(draft.name.ifBlank { "人" }, Modifier.size(48.dp), accent = true)
            Column {
                Text(draft.name.ifBlank { "未命名人物" }, color = ZhaoxiTheme.colors.ink)
                Text(if (galleryId == null) "新建人物 · 尚未归档" else "已关联人物归档", color = ZhaoxiTheme.colors.ink2)
            }
        }
        ZxTabRow(listOf("核心档案", "独立事实", "世界书", "表达与边界"), selectedTab) { selectedTab = it }
        when (selectedTab) {
            0 -> {
        ZhaoxiPersonaField("姓名", draft.name) { draft = draft.copy(name = it) }
        ZhaoxiPersonaField("核心身份", draft.coreIdentity) { draft = draft.copy(coreIdentity = it) }
        ZhaoxiPersonaField("作品 / 世界", draft.franchise) { draft = draft.copy(franchise = it) }
        ZxSection("性格与价值")
        listOf("personality", "valuesAndTradeoffs").forEach { field ->
            ZhaoxiPersonaField(personaFieldTitle(field), draft.fields[field].orEmpty()) { value ->
                draft = draft.copy(fields = draft.fields + (field to value))
            }
        }
        ZxSection("自我认知")
        listOf("selfNarrative", "subjectiveBeliefs", "identityGap").forEach { field ->
            ZhaoxiPersonaField(personaFieldTitle(field), draft.fields[field].orEmpty()) { value ->
                draft = draft.copy(fields = draft.fields + (field to value))
            }
        }
        ZxSection("日常与表达")
        listOf("preferencesAndHabits", "voiceStyle").forEach { field ->
            ZhaoxiPersonaField(personaFieldTitle(field), draft.fields[field].orEmpty()) { value ->
                draft = draft.copy(fields = draft.fields + (field to value))
            }
        }
        ZxSection("经历与关系")
        listOf("biography", "relationships").forEach { field ->
            ZhaoxiPersonaField(personaFieldTitle(field), draft.fields[field].orEmpty()) { value ->
                draft = draft.copy(fields = draft.fields + (field to value))
            }
        }
        ZxSection("补充设定")
        ZhaoxiPersonaField("补充事实", draft.fields["customFacts"].orEmpty()) { value ->
            draft = draft.copy(fields = draft.fields + ("customFacts" to value))
        }
        ZxFooterNote("人物主档案共 13 个正式字段；额外事实、世界书与采样偏好从上方页签管理。")
        if (identity != null) {
            ZxSection("AI 辅助设定")
            ZxFooterNote("模型建议仅作为预览，点击采用后进入草稿，还须保存归档。")
            ZxField(aiPrompt, { aiPrompt = it }, Modifier.fillMaxWidth().heightIn(min = 100.dp),
                placeholder = "写出人物性格、经历、表达等希望补充的细节")
            ZxButton("生成待确认建议", {
                if (!aiBusy) {
                    aiBusy = true
                    val source = encodedDraft
                    proposalSource = source
                    jobs.launch {
                        try {
                            val result = authoring.suggest(identity, draft, aiPrompt.trim())
                            if (encodedDraft != source) {
                                status = "草稿已经修改，旧建议不会覆盖当前输入"
                            } else if (result is UiPersonaAuthoringReceipt.Suggested) {
                                proposed = zxPersonaCanonicalDraft(result.draft)
                                status = "建议生成成功，请自行审核"
                            } else status = result.zxAuthoringFeedback()
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (failure: Exception) { status = failure.message ?: "建议生成失败" }
                        finally { aiBusy = false }
                    }
                }
            }, enabled = !aiBusy && !saving && aiPrompt.isNotBlank(), loading = aiBusy)
            proposed?.let { suggestion ->
                ZxSection("AI 草稿预览")
                ZxValueRow("建议人物", value = suggestion.name.ifBlank { "未命名" })
                ZxValueRow("已补充主档案字段", value = suggestion.fields.values.count(String::isNotBlank).toString())
                ZxButton("采用到编辑草稿", {
                    if (encodedDraft == proposalSource) {
                        draft = suggestion
                        status = "建议已放入本地草稿，请检查后保存"
                    } else status = "草稿已变化，原建议不再适用"
                    proposed = null
                })
                ZxButton("忽略建议", { proposed = null }, variant = ZxButtonVariant.Outline)
            }
        }
            }
            1 -> {
                ZxSection("独立事实 · ${draft.facts.size} 条")
                ZxFooterNote("事实来源、时间和主观视角由人物档案真实保存。")
                draft.facts.forEachIndexed { index, fact ->
                    ZxRow("事实 ${index + 1} · ${personaFieldTitle(fact.category)}",
                        fact.content.take(90).ifBlank { "尚未填写" },
                        onClick = { selectedFactId = if (selectedFactId == fact.id) null else fact.id })
                }
                ZxButton("添加人物事实", {
                    val added = UiPersonaFact(java.util.UUID.randomUUID().toString(), "customFacts", "")
                    draft = draft.copy(facts = draft.facts + added)
                    selectedFactId = added.id
                }, variant = ZxButtonVariant.Outline)
                draft.facts.firstOrNull { it.id == selectedFactId }?.let { fact ->
                    ZxSection("编辑当前事实")
                    ZhaoxiPersonaField("事实类别", fact.category) { value ->
                        draft = draft.copy(facts = draft.facts.map {
                            if (it.id == fact.id) it.copy(category = value) else it
                        })
                    }
                    ZxField(fact.content, { value ->
                        draft = draft.copy(facts = draft.facts.map {
                            if (it.id == fact.id) it.copy(content = value) else it
                        })
                    }, Modifier.fillMaxWidth().heightIn(min = 120.dp), placeholder = "完整事实描述")
                    ZhaoxiPersonaField("所处时间 / 阶段", fact.stage) { value ->
                        draft = draft.copy(facts = draft.facts.map {
                            if (it.id == fact.id) it.copy(stage = value) else it
                        })
                    }
                    ZhaoxiPersonaField("人物的主观视角", fact.perspective) { value ->
                        draft = draft.copy(facts = draft.facts.map {
                            if (it.id == fact.id) it.copy(perspective = value) else it
                        })
                    }
                    ZhaoxiPersonaField("信息来源", fact.source) { value ->
                        draft = draft.copy(facts = draft.facts.map {
                            if (it.id == fact.id) it.copy(source = value) else it
                        })
                    }
                    ZxFooterNote("原始可信状态：${fact.provenance}")
                    ZxButton("移除这条事实", {
                        draft = draft.copy(facts = draft.facts.filterNot { it.id == fact.id })
                        selectedFactId = null
                    }, variant = ZxButtonVariant.Danger)
                }
            }
            2 -> {
                ZxSection("世界书 · ${draft.lore.size} 条")
                ZxFooterNote("先选择条目，再查看或修改完整正文。所有更改通过底部保存归档。")
                if (draft.lore.isEmpty()) ZxEmpty("尚无世界书条目", "添加背景、地点、规则等设定")
                draft.lore.forEachIndexed { index, lore ->
                    ZxRow(lore.title.ifBlank { "条目 ${index + 1}" },
                        lore.content.take(90).ifBlank { "点击完善世界设定" },
                        onClick = { selectedLoreId = if (selectedLoreId == lore.id) null else lore.id })
                }
                ZxButton("添加世界书条目", {
                    val added = UiPersonaLore(java.util.UUID.randomUUID().toString(), "", "")
                    draft = draft.copy(lore = draft.lore + added)
                    selectedLoreId = added.id
                }, variant = ZxButtonVariant.Outline)
                draft.lore.firstOrNull { it.id == selectedLoreId }?.let { lore ->
                    ZxSection("正在编辑的世界书条目")
                    ZhaoxiPersonaField("名称", lore.title) { value ->
                        draft = draft.copy(lore = draft.lore.map {
                            if (it.id == lore.id) it.copy(title = value) else it
                        })
                    }
                    ZxField(lore.content, { value ->
                        draft = draft.copy(lore = draft.lore.map {
                            if (it.id == lore.id) it.copy(content = value) else it
                        })
                    }, Modifier.fillMaxWidth().heightIn(min = 148.dp), placeholder = "该世界书条目的完整设定")
                    ZhaoxiPersonaField("关键词（顿号分隔）", lore.keywords.joinToString("、")) { value ->
                        draft = draft.copy(lore = draft.lore.map {
                            if (it.id == lore.id) it.copy(keywords = value.split("、")
                                .map(String::trim).filter(String::isNotBlank)) else it
                        })
                    }
                    ZxSwitchRow("始终启用", "启用后无需关键词触发", lore.alwaysOn) { checked ->
                        draft = draft.copy(lore = draft.lore.map {
                            if (it.id == lore.id) it.copy(alwaysOn = checked) else it
                        })
                    }
                    ZxFooterNote("优先级：${lore.priority} · 剧透等级：${lore.spoilerLevel}")
                    ZxButton("移除当前世界书条目", {
                        draft = draft.copy(lore = draft.lore.filterNot { it.id == lore.id })
                        selectedLoreId = null
                    }, variant = ZxButtonVariant.Danger)
                }
            }
            3 -> {
        ZxField(draft.samplingTemperaturePosition?.toString().orEmpty(), { value ->
            if (value.isEmpty() || value.toIntOrNull()?.let { it in 0..100 } == true) draft = draft.copy(samplingTemperaturePosition = value.toIntOrNull())
        }, placeholder = "采样位置 0–100，留空沿用全局")
        ZxChip("恢复全局采样", false) { draft = draft.copy(samplingTemperaturePosition = null, legacySamplingPosition = null) }
            }
        }
        Spacer(Modifier.height(12.dp))
        ZxButton("保存归档", { if (!saving) {
            saving = true
            jobs.launch {
                try {
                    val id = galleryId; val rev = revision
                    val result = if (id != null && rev != null)
                        authoring.save(id, rev, zxPersonaCanonicalDraft(draft))
                    else authoring.create(zxPersonaCanonicalDraft(draft))
                    when (result) {
                        is UiPersonaAuthoringReceipt.Saved -> {
                            galleryId = result.galleryId
                            revision = result.revision
                            status = "人物归档已保存"
                            scope.onBack()
                        }
                        else -> status = result.zxAuthoringFeedback()
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { status = failure.message ?: "保存失败；人物草稿已保留" }
                finally { saving = false }
            }
        } }, enabled = draft.name.isNotBlank() && !saving && !aiBusy, loading = saving)
        ZxButton("返回", ::leave, variant = ZxButtonVariant.Outline)
    }
    if (confirmLeave) ZxDialog("放弃未保存的人物修改？",
        "离开将放弃此次尚未归档的编辑内容。", "放弃修改",
        { confirmLeave = false; scope.onBack() }, { confirmLeave = false })
}

@Composable
fun ZhaoxiPersonaProfileScene(galleryId: String, gallery: PersonaGalleryUiPort?, selection: PersonaSelectionUiPort?,
    stories: PersonaStoryUiPort?, transfer: PersonaTransferUiPort?, scope: UiSceneRenderScope) {
    var revision by remember { mutableStateOf(0) }
    val receipt by produceState<UiPersonaGalleryReadReceipt?>(null, galleryId, revision) { value = gallery?.read() }
    val entry = (receipt as? UiPersonaGalleryReadReceipt.Available)?.entries?.firstOrNull { it.id == galleryId }
    val jobs = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    val save = rememberUiDocumentSaver("application/octet-stream") { status = if (it == UiDocumentWriteReceipt.Saved) "导出完成" else "导出失败" }
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
                    status = stories.submit(PersonaStoryUiCommand.SaveDetails(snap.identity, galleryId, story.id, notes, story.storyStage)).zxStoryFeedback(); revision++
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
    var confirmRemove by remember { mutableStateOf(false) }
    val preview by produceState<UiPersonaPortraitPreviewReceipt?>(null, galleryId, revision) { value = port.preview(galleryId) }
    val pick = rememberUiAttachmentPickerFor<String>(arrayOf("image/*"), { status = it }) { target, uri ->
        if (uri != null) jobs.launch { status = port.submit(PersonaPortraitUiCommand.Import(target, uri)).zxPortraitFeedback(); revision++ }
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
        if (image != null) ZxButton("移除画像", { confirmRemove = true }, variant = ZxButtonVariant.Danger)
        if (confirmRemove) ZxDialog(
            "确定移除这张画像？", "移除后无法从当前人物档案恢复。", "确认移除",
            {
                confirmRemove = false
                jobs.launch {
                    status = port.submit(PersonaPortraitUiCommand.Remove(galleryId)).zxPortraitFeedback()
                    revision++
                }
            },
            { confirmRemove = false },
        )
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
                else port.save(snapshot.target, snapshot.draft.copy(samplingTemperaturePosition = position)).zxTuningFeedback()
            } })
            ZxButton("恢复全局采样", { jobs.launch {
                status = port.save(snapshot.target, snapshot.draft.copy(samplingTemperaturePosition = null, legacySamplingPosition = null)).zxTuningFeedback()
            } })
        }
        status?.let { Text(it, color = ZhaoxiTheme.colors.ink2) }
    }
}
