package com.labteto.dshmobile.ui.screens.local

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.labteto.dshmobile.local.interaction.LocalQuestion
import com.labteto.dshmobile.local.interaction.LocalQuestionFormField
import com.labteto.dshmobile.local.interaction.LOCAL_MCP_QUESTION_DECLINE
import com.labteto.dshmobile.local.interaction.LOCAL_MCP_QUESTION_URL_ACCEPT
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.shenyan.designsystem.components.SySheetChoiceRow
import com.shenyan.designsystem.components.SyTextField
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Keep multi-select draft typed as a string array; malformed drafts remain invalid. */
private fun selectedMcpOptions(raw: String): List<String>? {
    if (raw.isBlank()) return emptyList()
    val decoded = try {
        Json.parseToJsonElement(raw) as? JsonArray
    } catch (_: SerializationException) {
        null
    } ?: return null
    return decoded.map { item ->
        (item as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    }
}

/** User-visible server identity and one native form, through the existing question waiter. */
@Composable
internal fun McpElicitationDialog(
    question: LocalQuestion,
    onAnswer: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    val fields = question.mcpFormFields.orEmpty()
    val context = LocalContext.current
    var drafts by rememberSaveable(question.requestId) {
        mutableStateOf(fields.associate { field ->
            field.name to field.defaultValue.ifBlank {
                if (field.kind == "array" && field.required) "[]" else ""
            }
        })
    }
    var opened by rememberSaveable(question.requestId) { mutableStateOf(false) }
    var error by rememberSaveable(question.requestId) { mutableStateOf<String?>(null) }
    fun submit(value: String) {
        if (!onAnswer(value)) error = "此追问已结束，请返回当前会话查看最新状态"
    }
    val valid = fields.all { field ->
        val raw = drafts[field.name].orEmpty()
        if (raw.isBlank()) !field.required else when (field.kind) {
            "array" -> selectedMcpOptions(raw)?.let { selected ->
                (field.minItems == null || selected.size >= field.minItems) &&
                    (field.maxItems == null || selected.size <= field.maxItems) &&
                    selected.all { it in field.options }
            } ?: false
            "integer" -> raw.toLongOrNull()?.let { n ->
                (field.minimum == null || n.toDouble() >= field.minimum) &&
                    (field.maximum == null || n.toDouble() <= field.maximum)
            } ?: false
            "number" -> raw.toDoubleOrNull()?.let { n ->
                n.isFinite() && (field.minimum == null || n >= field.minimum) &&
                    (field.maximum == null || n <= field.maximum)
            } ?: false
            "boolean" -> raw == "true" || raw == "false"
            else -> (field.minLength == null || raw.length >= field.minLength) &&
                (field.maxLength == null || raw.length <= field.maxLength) &&
                (field.options.isEmpty() || raw in field.options)
        }
    }
    SyResponsiveSheet(
        title = "MCP 工具请求确认",
        onDismiss = onDismiss,
        scrollable = true,
        seamlessBackground = true,
    ) {
        Text(question.question, style = DsType.base16Strong.withReadingWeight())
        if (question.mcpUrl != null) {
            val url = question.mcpUrl
            val host = Uri.parse(url).host.orEmpty()
            Text("此操作将在浏览器打开：$host。密码、密钥与支付信息仅在对方网页填写。")
            SyButton(
                text = "打开外部安全页面",
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addCategory(Intent.CATEGORY_BROWSABLE))
                        opened = true
                        error = null
                    } catch (failure: android.content.ActivityNotFoundException) {
                        error = "设备没有可打开此网址的浏览器"
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("mcp-open-url"),
            )
        } else {
            fields.forEach { field ->
                val value = drafts[field.name].orEmpty()
                Text(field.title + if (field.required) " · 必填" else "")
                if (field.kind == "array") {
                    val selected = selectedMcpOptions(value).orEmpty()
                    field.options.forEach { option ->
                        SySheetChoiceRow(
                            title = field.optionLabels[option] ?: option,
                            selected = option in selected,
                            onClick = {
                                val next = if (option in selected) selected - option
                                    else selected + option
                                drafts = drafts + (field.name to
                                    JsonArray(next.map(::JsonPrimitive)).toString())
                                error = null
                            },
                        )
                    }
                } else if (field.kind == "boolean") {
                    listOf("true" to "是", "false" to "否").forEach { (raw,label) ->
                        SySheetChoiceRow(
                            title = label,
                            selected = value == raw,
                            onClick = { drafts = drafts + (field.name to raw); error = null },
                        )
                    }
                } else if (field.options.isNotEmpty()) {
                    field.options.forEach { option ->
                        SySheetChoiceRow(
                            title = field.optionLabels[option] ?: option,
                            selected = value == option,
                            onClick = { drafts = drafts + (field.name to option); error = null },
                        )
                    }
                } else {
                    SyTextField(
                        value = value,
                        onValueChange = { drafts = drafts + (field.name to it); error = null },
                        modifier = Modifier.fillMaxWidth().testTag("mcp-form-${field.name}"),
                        label = { Text(field.title) },
                        maxLines = 3,
                    )
                }
            }
        }
        error?.let { Text(it) }
        SyButton(
            text = if (question.mcpUrl != null) "确认并继续" else "提交答复",
            modifier = Modifier.fillMaxWidth().testTag("mcp-form-submit"),
            enabled = if (question.mcpUrl != null) opened else valid,
            onClick = {
                if (question.mcpUrl != null) submit(LOCAL_MCP_QUESTION_URL_ACCEPT)
                else submit(buildJsonObject {
                    fields.forEach { field ->
                        val raw = drafts[field.name].orEmpty()
                        if (raw.isNotBlank()) when (field.kind) {
                            "array" -> put(field.name,
                                JsonArray(requireNotNull(selectedMcpOptions(raw)).map(::JsonPrimitive)))
                            "integer" -> put(field.name, JsonPrimitive(raw.toLong()))
                            "number" -> put(field.name, JsonPrimitive(raw.toDouble()))
                            "boolean" -> put(field.name, JsonPrimitive(raw.toBooleanStrict()))
                            else -> put(field.name, JsonPrimitive(raw))
                        }
                    }
                }.toString())
            },
        )
        SyButton(
            text = "拒绝提供",
            modifier = Modifier.fillMaxWidth().testTag("mcp-form-decline"),
            onClick = { submit(LOCAL_MCP_QUESTION_DECLINE) },
            variant = SyButtonVariant.Ghost,
        )
        SyButton(
            text = "取消",
            modifier = Modifier.fillMaxWidth().testTag("mcp-form-cancel"),
            onClick = onDismiss,
            variant = SyButtonVariant.Ghost,
        )
    }
}
