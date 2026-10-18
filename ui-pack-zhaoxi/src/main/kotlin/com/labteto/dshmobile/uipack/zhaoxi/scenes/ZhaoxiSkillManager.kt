package com.labteto.dshmobile.uipack.zhaoxi.scenes

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.ToolsUiCommand
import com.labteto.dshmobile.uicontract.ToolsUiPort
import com.labteto.dshmobile.uicontract.ToolsUiReceipt
import com.labteto.dshmobile.uicontract.UiToolError
import com.labteto.dshmobile.uicontract.UiPresetSkillSnapshot
import com.labteto.dshmobile.uicontract.UiSkillSnapshot
import com.labteto.dshmobile.uicontract.submit
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxButton
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxButtonVariant
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxDialog
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxEmpty
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxField
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxFooterNote
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxSection
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxSwitch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SKILL_IMPORT_LIMIT = 10 * 1024 * 1024

internal fun zxSupportedSkillImportName(name: String): Boolean =
    name.equals("SKILL.md", ignoreCase = true) || name.endsWith(".zip", ignoreCase = true)

/** A bounded read before submitting to the real Skill Owner; never trust a remote document's length. */
internal fun zxReadLimitedSkillPackage(stream: java.io.InputStream, limit: Int = SKILL_IMPORT_LIMIT): ByteArray {
    require(limit > 0)
    val result = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var size = 0
    while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        require(size + read <= limit) { "技能文件超过大小限制" }
        result.write(buffer, 0, read)
        size += read
    }
    require(size > 0) { "技能文件为空" }
    return result.toByteArray()
}

internal fun zxSkillReceiptText(receipt: ToolsUiReceipt, done: String): String = when (receipt) {
    is ToolsUiReceipt.Applied -> done
    is ToolsUiReceipt.Failed -> when (receipt.reason) {
        UiToolError.CONFIGURATION -> "技能格式、名称或规则无效，请检查后重试"
        UiToolError.PERMISSION -> "没有读取或修改技能所需的权限"
        UiToolError.TIMEOUT -> "技能操作超时，请核实当前状态再重试"
        UiToolError.DNS, UiToolError.UNREACHABLE -> "网络不可用，无法完成远程技能操作"
        UiToolError.UNKNOWN -> "技能操作失败，请检查是否冲突或重名"
    }
}

/** UI for existing ToolsUiPort skills, without inventing a second installation or execution path. */
@Composable
internal fun ZhaoxiSkillManager(tools: ToolsUiPort) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val jobs = rememberCoroutineScope()
    var generation by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var newSkill by remember { mutableStateOf(false) }
    var id by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    var document by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    val skills by produceState<List<UiSkillSnapshot>?>(null, tools, generation) {
        value = try { tools.installedSkills() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            feedback = "读取技能失败，请刷新重试"
            emptyList()
        }
    }
    val presets by produceState<List<UiPresetSkillSnapshot>?>(null, tools, generation) {
        value = try { tools.presetSkills() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { emptyList() }
    }

    fun submitSkill(command: ToolsUiCommand, success: String, onSuccess: () -> Unit = {}) {
        if (busy) return
        jobs.launch {
            busy = true
            try {
                val receipt = tools.submit(command)
                feedback = zxSkillReceiptText(receipt, success)
                if (receipt is ToolsUiReceipt.Applied) {
                    onSuccess()
                    generation++
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            finally { busy = false }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) jobs.launch {
            busy = true
            try {
                val fileName = withContext(Dispatchers.IO) {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                }
                if (fileName == null || !zxSupportedSkillImportName(fileName)) {
                    feedback = "请选择 SKILL.md 或 ZIP 技能包"
                } else {
                    val data = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { zxReadLimitedSkillPackage(it) }
                            ?: throw IllegalArgumentException("无法读取技能文件")
                    }
                    val receipt = tools.submit(ToolsUiCommand.ImportSkill(fileName, data))
                    feedback = zxSkillReceiptText(receipt, "技能已安装，可在列表查看")
                    if (receipt is ToolsUiReceipt.Applied) generation++
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                feedback = if (failure is IllegalArgumentException) failure.message
                    else "导入失败，请检查文件权限和技能格式"
            } finally { busy = false }
        }
    }

    ZxSection("本地技能")
    feedback?.let { ZxFooterNote(it) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ZxButton("上传技能文件", { picker.launch(arrayOf("*/*")) }, Modifier.weight(1f), enabled = !busy)
        ZxButton("新建技能", { newSkill = !newSkill }, Modifier.weight(1f),
            variant = ZxButtonVariant.Outline, enabled = !busy)
    }
    ZxFooterNote("支持 SKILL.md、ZIP（上限 10 MB）。安装和附属资料由现有 Skill Owner 负责。")

    if (newSkill) {
        ZxSection("创建技能")
        ZxField(id, { id = it }, Modifier.fillMaxWidth(), placeholder = "英文 ID")
        ZxField(displayName, { displayName = it }, Modifier.fillMaxWidth(), placeholder = "中文显示名")
        ZxField(description, { description = it }, Modifier.fillMaxWidth(), placeholder = "适用任务说明")
        ZxField(instructions, { instructions = it },
            Modifier.fillMaxWidth().heightIn(min = 112.dp), placeholder = "完整技能指令")
        ZxButton("保存并安装", {
            submitSkill(ToolsUiCommand.CreateSkill(id.trim(), displayName.trim(),
                description.trim(), instructions.trim()), "技能创建成功") {
                newSkill = false
                id = ""; displayName = ""; description = ""; instructions = ""
            }
        }, enabled = !busy && id.isNotBlank() && displayName.isNotBlank() &&
            description.isNotBlank() && instructions.isNotBlank(), loading = busy)
    }

    if (skills == null) ZxFooterNote("正在读取技能…")
    else if (skills.orEmpty().isEmpty()) ZxEmpty("尚未安装技能", "上传技能包或新建技能")
    skills.orEmpty().forEach { item ->
        ZxRow(item.displayName, item.description, onClick = {
            if (!busy) jobs.launch {
                busy = true
                try {
                    document = tools.readSkillDocument(item.name)
                    editing = item.name
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { feedback = "读取技能规则失败，请重试" }
                finally { busy = false }
            }
        }, trailing = {
            ZxSwitch(item.modelInvocable, { enabled ->
                submitSkill(ToolsUiCommand.SetSkillModelInvocable(item.name, enabled),
                    if (enabled) "已允许自动发现" else "已改为仅手动使用")
            }, contentDescription = "自动发现 ${item.displayName}")
        })
    }

    editing?.let { skillId ->
        Spacer(Modifier.height(8.dp))
        ZxSection("编辑技能 · $skillId")
        ZxFooterNote("保存 SKILL.md 不会删除 references、scripts 等附属文件。")
        ZxField(document, { document = it }, Modifier.fillMaxWidth().heightIn(min = 160.dp),
            placeholder = "SKILL.md")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZxButton("保存修改", {
                submitSkill(ToolsUiCommand.UpdateSkill(skillId, document), "已保存技能规则") { editing = null }
            }, Modifier.weight(1f), enabled = !busy && document.isNotBlank())
            ZxButton("关闭", { editing = null }, Modifier.weight(1f),
                variant = ZxButtonVariant.Outline, enabled = !busy)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZxButton("复制手动调用", {
                clipboard.setText(AnnotatedString("@skill:$skillId"))
                feedback = "已复制指令，可粘贴到 Work 输入框"
            }, Modifier.weight(1f), variant = ZxButtonVariant.Outline)
            ZxButton("删除技能", { confirmDelete = skillId }, Modifier.weight(1f),
                variant = ZxButtonVariant.Danger, enabled = !busy)
        }
    }

    presets.orEmpty().filterNot { it.installed }.take(12).let { available ->
        if (available.isNotEmpty()) ZxSection("可安装预置")
        available.forEach { preset ->
            ZxRow(preset.title, preset.description, trailing = {
                ZxButton("安装", {
                    submitSkill(ToolsUiCommand.InstallPreset(preset.id), "预置技能已安装")
                }, enabled = !busy)
            })
        }
    }
    confirmDelete?.let { skillId ->
        ZxDialog("确认删除技能？", "此操作会删除本地「$skillId」及其资料。",
            "删除", {
                confirmDelete = null
                submitSkill(ToolsUiCommand.RemoveSkill(skillId), "技能已删除") {
                    if (editing == skillId) editing = null
                }
            }, { confirmDelete = null })
    }
}
