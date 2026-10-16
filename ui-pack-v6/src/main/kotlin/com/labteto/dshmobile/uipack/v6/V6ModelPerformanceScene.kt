package com.labteto.dshmobile.uipack.v6

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.uicontract.ModelPerformanceUiCommand
import com.labteto.dshmobile.uicontract.ModelPerformanceUiPort
import com.labteto.dshmobile.uicontract.ModelPerformanceUiReceipt
import com.labteto.dshmobile.uicontract.UiModelReasoningCeiling
import com.shenyan.designsystem.components.SyButton
import com.shenyan.designsystem.components.SyButtonStyle
import com.shenyan.designsystem.components.SyGlassCard
import com.shenyan.designsystem.components.SyNote
import kotlin.math.roundToInt

/** Real V6 model performance controls; values are never copied into an independent UI store. */
@Composable
fun V6ModelPerformanceScene(owner: ModelPerformanceUiPort, onBack: () -> Unit) {
    val state by owner.snapshots.collectAsStateWithLifecycle(initialValue = owner.snapshot())
    var feedback by remember { mutableStateOf<String?>(null) }
    var temperatureDraft by remember(state.temperatureCeiling) {
        mutableFloatStateOf(state.temperatureCeiling.toFloat())
    }

    fun submit(command: ModelPerformanceUiCommand) {
        feedback = when (val receipt = owner.submit(command)) {
            ModelPerformanceUiReceipt.Applied -> "已提交原模型性能配置，最新状态由 Owner 同步"
            ModelPerformanceUiReceipt.InvalidValue -> "数值超出模型性能设置允许的范围"
            is ModelPerformanceUiReceipt.Failed -> receipt.message ?: "模型性能设置修改失败"
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("模型性能与推理", style = MaterialTheme.typography.titleLarge)
        SyButton("返回", onBack, style = SyButtonStyle.GHOST)
        feedback?.let { SyNote(it) }
        SyNote("此处是全应用的推理档位和温度上限。实际模型能力及最低要求仍由模型 Owner 决定。")
        SyGlassCard(Modifier.fillMaxWidth()) {
            Text("全局推理上限", style = MaterialTheme.typography.titleMedium)
            UiModelReasoningCeiling.entries.forEach { choice ->
                val label = when (choice) {
                    UiModelReasoningCeiling.CUSTOM -> "按对话独立设置"
                    UiModelReasoningCeiling.DEFAULT -> "服务商默认"
                    UiModelReasoningCeiling.FAST -> "快速 / 关闭（若模型允许）"
                    UiModelReasoningCeiling.LOW -> "低推理"
                    UiModelReasoningCeiling.DEEP -> "高推理"
                    UiModelReasoningCeiling.MAX -> "最高"
                }
                SyButton(label, {
                    if (choice != owner.snapshot().reasoning)
                        submit(ModelPerformanceUiCommand.SetReasoning(choice))
                }, enabled = choice != state.reasoning,
                    style = if (choice == state.reasoning) SyButtonStyle.PRIMARY
                        else SyButtonStyle.SECONDARY)
            }
        }
        SyGlassCard(Modifier.fillMaxWidth()) {
            Text("温度上限", style = MaterialTheme.typography.titleMedium)
            val rounded = (temperatureDraft * 20f).roundToInt() / 20f
            Text(String.format(java.util.Locale.US, "%.2f", rounded))
            Slider(value = temperatureDraft, onValueChange = {
                temperatureDraft = (it * 20f).roundToInt().coerceIn(20, 40) / 20f
            }, onValueChangeFinished = {
                submit(ModelPerformanceUiCommand.SetTemperature(
                    (temperatureDraft * 20f).roundToInt() / 20.0))
            }, modifier = Modifier.fillMaxWidth(), valueRange = 1f..2f, steps = 19)
            SyNote("最小 1.00，最大 2.00，精度 0.05；保存后会约束聊天和 Work 的模型参数。")
        }
    }
}
