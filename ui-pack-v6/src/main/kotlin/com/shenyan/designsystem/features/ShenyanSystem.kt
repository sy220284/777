package com.shenyan.designsystem.features

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shenyan.designsystem.components.*
import com.shenyan.designsystem.foundation.*

@Composable
fun SyMemoryEntry(text: String, source: String, scope: String, enabled: Boolean,
                  onToggle: (Boolean) -> Unit, onCorrect: () -> Unit, modifier: Modifier = Modifier) {
    SyGlassCard(modifier) {
        Text(source, style = MaterialTheme.typography.labelSmall, color = LocalSyColors.current.textSecondary)
        Text("作用范围：$scope", style = MaterialTheme.typography.labelSmall, color = LocalSyColors.current.textSecondary)
        Text(text)
        SySwitch("启用这条记忆", enabled, onToggle)
        SyButton("纠正记忆", onCorrect, style = SyButtonStyle.SECONDARY)
    }
}

@Composable
fun SyModelCapability(modelName: String, capabilities: List<String>, verified: Boolean,
                      onDetails: () -> Unit, modifier: Modifier = Modifier) {
    SyGlassCard(modifier) {
        Text(modelName, style = MaterialTheme.typography.titleMedium)
        SyStatusTag(if (verified) "能力来源已核实" else "能力未核实", if (verified) SyPhase.DONE else SyPhase.BLOCKED)
        Text(capabilities.joinToString(" · "))
        SyButton("查看能力来源", onDetails, style = SyButtonStyle.SECONDARY)
    }
}

@Composable
fun SySearchResultGroup(title: String, results: List<SyOption>, onOpen: (String) -> Unit,
                        modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$title · ${results.size}", style = MaterialTheme.typography.titleSmall)
        if (results.isEmpty()) SyNote("没有找到符合条件的结果")
        else results.forEach { result ->
            if (result.enabled) SyListItem(result.label, "点击查看", onClick = { onOpen(result.id) })
            else SyNote("${result.label} · 暂不可用")
        }
    }
}

@Composable
fun SyRecoveryPanel(title: String, reason: String, canRetry: Boolean,
                    onRetry: () -> Unit, onDetails: () -> Unit, modifier: Modifier = Modifier) {
    SyGlassCard(modifier) {
        SyStatusTag("需要处理", SyPhase.FAILED)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(reason, color = LocalSyColors.current.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canRetry) SyButton("重试", onRetry)
            SyButton("查看原因", onDetails, style = SyButtonStyle.SECONDARY)
        }
    }
}