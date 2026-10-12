package com.shenyan.designsystem.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shenyan.designsystem.foundation.*

/** 新增通用 8 组件。受控数据 + 受控业务回调，UI 不执行持久化。 */
@Composable
fun SyChoiceControls(options: List<SyOption>, selected: Set<String>, onChange: (Set<String>) -> Unit,
                     modifier: Modifier = Modifier, multiple: Boolean = true) {
    Column(modifier) {
        options.forEach { option ->
            val checked = option.id in selected
            val hit = if (multiple) Modifier.toggleable(checked, enabled = option.enabled, role = Role.Checkbox) {
                onChange(if (it) selected + option.id else selected - option.id)
            } else Modifier.selectable(checked, enabled = option.enabled, role = Role.RadioButton) {
                onChange(setOf(option.id))
            }
            Row(Modifier.fillMaxWidth().heightIn(min = SyMetric.touch).then(hit), verticalAlignment = Alignment.CenterVertically) {
                if (multiple) Checkbox(checked = checked, enabled = option.enabled, onCheckedChange = null)
                else RadioButton(selected = checked, enabled = option.enabled, onClick = null)
                Text(option.label, color = LocalSyColors.current.text)
            }
        }
    }
}

@Composable
fun SyGlassSelect(options: List<SyOption>, selectedId: String?, onSelect: (String) -> Unit,
                  modifier: Modifier = Modifier, enabled: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        SyButton(options.firstOrNull { it.id == selectedId }?.label ?: "请选择", { expanded = true },
            modifier = Modifier.fillMaxWidth(), style = SyButtonStyle.SECONDARY, enabled = enabled)
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, enabled = option.enabled,
                onClick = { expanded = false; onSelect(option.id) }) }
        }
    }
}

@Composable
fun SyValueSlider(label: String, value: Float, onValueChange: (Float) -> Unit,
                  modifier: Modifier = Modifier, range: ClosedFloatingPointRange<Float> = 0f..100f, enabled: Boolean = true) {
    Column(modifier) {
        Row { Text(label, Modifier.weight(1f)); Text("${value.toInt()}") }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onValueChange,
            valueRange = range, enabled = enabled, modifier = Modifier.heightIn(min = SyMetric.touch))
    }
}

@Composable
fun SyDateTimeField(date: String, time: String, onDateChange: (String) -> Unit, onTimeChange: (String) -> Unit,
                    onConfirm: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                    timezoneLabel: String = "设备时区") {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(value = date, onValueChange = onDateChange, label = { Text("日期 · YYYY-MM-DD") },
            enabled = enabled, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = time, onValueChange = onTimeChange, label = { Text("时间 · HH:mm") },
            enabled = enabled, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text("时区：$timezoneLabel", style = MaterialTheme.typography.labelSmall,
            color = LocalSyColors.current.textSecondary)
        SyButton("使用此日期与时间", onConfirm, enabled = enabled && runCatching { java.time.LocalDate.parse(date) }.isSuccess
                && Regex("([01]\\d|2[0-3]):[0-5]\\d").matches(time))
    }
}

@Composable
fun SyContextMenu(actions: List<SyAction>, onSelect: (String) -> Unit, modifier: Modifier = Modifier,
                  expanded: Boolean = true, onDismiss: () -> Unit = {}) {
    Box(modifier) {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label,
                        color = if (action.destructive) LocalSyColors.current.danger else LocalSyColors.current.text) },
                    onClick = { onSelect(action.id); onDismiss() }, enabled = action.enabled,
                    modifier = Modifier.heightIn(min = SyMetric.touch)
                )
            }
        }
    }
}

@Composable
fun SyDisclosure(title: String, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier,
                 content: @Composable ColumnScope.() -> Unit) {
    SyGlassCard(modifier) {
        SyButton((if (expanded) "▾ " else "▸ ") + title, onToggle, modifier = Modifier.fillMaxWidth(), style = SyButtonStyle.GHOST)
        if (expanded) content()
    }
}

@Composable
fun SyBreadcrumbs(path: List<SyOption>, onNavigate: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        path.forEachIndexed { i, route ->
            if (i > 0) Text("/", Modifier.align(Alignment.CenterVertically), color = LocalSyColors.current.textTertiary)
            SyButton(route.label, { onNavigate(route.id) }, enabled = route.enabled,
                style = SyButtonStyle.GHOST)
        }
    }
}

@Composable
fun SyFileTree(nodes: List<SyFileNode>, selectedId: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier,
               expandedIds: Set<String> = emptySet(), onExpand: ((String) -> Unit)? = null) {
    Column(modifier) {
        nodes.forEach { node ->
            val c = LocalSyColors.current
            Surface(onClick = { onSelect(node.id) }, color = if (selectedId == node.id) c.selected else c.surface,
                modifier = Modifier.fillMaxWidth().heightIn(min = SyMetric.touch),
                shape = RoundedCornerShape(SyMetric.radiusControl)) {
                Row(Modifier.padding(start = (12 + node.depth.coerceIn(0, 8) * 16).dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (node.isFolder && onExpand != null) {
                        SyButton(if (node.id in expandedIds) "▾" else "▸", { onExpand(node.id) },
                            style = SyButtonStyle.GHOST)
                    } else Text(if (node.isFolder) "▸" else "▤")
                    Spacer(Modifier.width(9.dp))
                    Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}