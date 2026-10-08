package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.presentation.LocalReasoningControls
import com.labteto.dshmobile.local.presentation.LocalReasoningUiMode
import com.labteto.dshmobile.ui.components.DsComposerAction
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

/** The same exact request policy drives the composer labels and the network payload. */
internal fun composerReasoningLabel(
    mode: LocalReasoningUiMode,
    modes: List<LocalReasoningUiMode>,
): String = when {
    modes.isEmpty() -> "默认"
    mode == LocalReasoningUiMode.DEFAULT && LocalReasoningUiMode.DEEP in modes -> "高"
    mode == LocalReasoningUiMode.DEFAULT -> "默认"
    mode == LocalReasoningUiMode.FAST -> if (LocalReasoningUiMode.LOW in modes ||
        LocalReasoningUiMode.FAST in modes && LocalReasoningUiMode.DEEP in modes) "关" else "基础"
    mode == LocalReasoningUiMode.LOW -> "低"
    mode == LocalReasoningUiMode.DEEP -> "高"
    else -> "极高"
}

private fun reasoningModeLabel(mode: LocalReasoningUiMode): String = when (mode) {
    LocalReasoningUiMode.DEFAULT -> "默认"
    LocalReasoningUiMode.FAST -> "关闭"
    LocalReasoningUiMode.LOW -> "低"
    LocalReasoningUiMode.DEEP -> "高"
    LocalReasoningUiMode.MAX -> "极高"
}

@Composable
internal fun LocalComposerCapabilityActions(
    profile: LocalModelProfile?,
    usageMode: LocalUsageMode,
    reasoningMode: LocalReasoningUiMode,
    networkSearchEnabled: Boolean,
    onSelectPanel: (String) -> Unit,
) {
    val colors = DsTheme.colors
    val modes = LocalReasoningControls.availableModes(profile, usageMode)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        DsComposerAction(
            icon = FeatherIcons.Gauge,
            contentDescription = "思考强度：" + composerReasoningLabel(reasoningMode, modes),
            onClick = { onSelectPanel("reasoning") },
            tint = colors.labelPrimary,
            containerColor = Color.Transparent,
        )
        Text(
            text = composerReasoningLabel(reasoningMode, modes),
            style = DsType.caption11.withReadingWeight(),
            color = colors.labelSecondary,
        )
        DsComposerAction(
            icon = FeatherIcons.Globe,
            contentDescription = if (networkSearchEnabled) "联网搜索：开启" else "联网搜索：关闭",
            onClick = { onSelectPanel("web") },
            tint = if (networkSearchEnabled) colors.labelPrimary else colors.labelTertiary,
            containerColor = Color.Transparent,
        )
        Text(
            text = if (networkSearchEnabled) "联网" else "离线",
            style = DsType.caption11.withReadingWeight(),
            color = colors.labelSecondary,
        )
    }
}

/** Discrete slider panels live above the composer, so keyboard focus stays intact. */
@Composable
internal fun LocalComposerCapabilityPanel(
    panel: String,
    profile: LocalModelProfile?,
    usageMode: LocalUsageMode,
    reasoningMode: LocalReasoningUiMode,
    onReasoningModeChange: (LocalReasoningUiMode) -> Unit,
    networkSearchEnabled: Boolean,
    onNetworkSearchChange: (Boolean) -> Unit,
) {
    val colors = DsTheme.colors
    val modes = LocalReasoningControls.availableModes(profile, usageMode)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = DsSpacing.small),
        shape = DsShapes.menu,
        color = colors.composerCard,
        border = BorderStroke(1.dp, colors.borderL2),
    ) {
        Column(
            Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
        ) {
            if (panel == "reasoning") {
                Text("思考强度", style = DsType.std14Strong.withReadingWeight(), color = colors.labelPrimary)
                if (modes.isEmpty()) {
                    Text("当前模型或协议使用模型默认档位，暂不支持手动调整",
                        style = DsType.small13.withReadingWeight(), color = colors.labelSecondary)
                } else {
                    val effectiveMode = if (reasoningMode == LocalReasoningUiMode.DEFAULT) {
                        if (LocalReasoningUiMode.DEEP in modes) LocalReasoningUiMode.DEEP else modes.first()
                    } else reasoningMode.takeIf { it in modes } ?: modes.first()
                    var chosen by remember(profile?.id, usageMode, reasoningMode, modes) {
                        mutableFloatStateOf(modes.indexOf(effectiveMode).toFloat())
                    }
                    Slider(
                        value = chosen,
                        onValueChange = { chosen = it },
                        onValueChangeFinished = {
                            onReasoningModeChange(modes[chosen.toInt().coerceIn(0, modes.lastIndex)])
                        },
                        valueRange = 0f..modes.lastIndex.toFloat(),
                        steps = (modes.size - 2).coerceAtLeast(0),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        modes.forEach { mode ->
                            Text(reasoningModeLabel(mode), style = DsType.caption11.withReadingWeight(),
                                color = colors.labelSecondary)
                        }
                    }
                    if (reasoningMode == LocalReasoningUiMode.DEFAULT) {
                        Text("当前使用模型默认档位；滑动后按所选档位发送",
                            style = DsType.caption11.withReadingWeight(), color = colors.labelSecondary)
                    }
                }
            } else {
                Text("联网搜索", style = DsType.std14Strong.withReadingWeight(), color = colors.labelPrimary)
                var selected by remember(networkSearchEnabled) {
                    mutableFloatStateOf(if (networkSearchEnabled) 1f else 0f)
                }
                Slider(
                    value = selected,
                    onValueChange = { selected = it },
                    onValueChangeFinished = { onNetworkSearchChange(selected >= 0.5f) },
                    valueRange = 0f..1f,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("关闭", style = DsType.caption11.withReadingWeight(), color = colors.labelSecondary)
                    Text("开启", style = DsType.caption11.withReadingWeight(), color = colors.labelSecondary)
                }
                Text("与工具管理中的联网搜索设置同步，下一次工具调用生效",
                    style = DsType.caption11.withReadingWeight(), color = colors.labelSecondary)
            }
        }
    }
}
