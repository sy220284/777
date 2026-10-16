package com.labteto.dshmobile.uipack.v6

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.uicontract.PricingUiPort
import com.labteto.dshmobile.uicontract.PricingUiReceipt
import com.labteto.dshmobile.uicontract.UiProviderPriceTier
import com.shenyan.designsystem.components.SyButton
import com.shenyan.designsystem.components.SyButtonStyle
import com.shenyan.designsystem.components.SyGlassCard
import com.shenyan.designsystem.components.SyNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Official model tariff display is never inferred from TokenUsageAnalyticsSnapshot totals. */
@Composable
fun V6ProviderPricingScene(owner: PricingUiPort, onBack: () -> Unit) {
    val snapshot by owner.snapshots.collectAsStateWithLifecycle(initialValue = owner.snapshot())
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    fun fmt(value: Double) = String.format(Locale.US, "%.2f", value)
    @Composable fun priceRow(label: String, tier: UiProviderPriceTier) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text("缓存命中：¥${fmt(tier.cacheHitCnyPerMillion)} / 百万 Tokens")
        Text("缓存未命中：¥${fmt(tier.cacheMissCnyPerMillion)} / 百万 Tokens")
        Text("输出：¥${fmt(tier.outputCnyPerMillion)} / 百万 Tokens")
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("DeepSeek 官方价格", style = MaterialTheme.typography.titleLarge)
        SyButton("返回", onBack, style = SyButtonStyle.GHOST)
        snapshot.error?.let { SyNote("刷新错误：" + it) }
        feedback?.let { SyNote(it) }
        Text("当前计价时段：" + if (snapshot.activePeriod == "PEAK") "峰时" else "谷时")
        Text(if (snapshot.lastUpdatedAt > 0L)
            "已缓存官方价格，更新时间：" +
                DateFormat.getDateTimeInstance().format(Date(snapshot.lastUpdatedAt))
        else "展示内置初始价格，尚未成功刷新官方数据")
        Text("官方来源：" + snapshot.sourceUrl)
        SyNote("节假日计价及服务商账单可能存在差异；此页面展示公布的费率，不代表已实际扣费。")
        SyButton(if (snapshot.refreshing || busy) "正在刷新" else "从官方重新获取价格", {
            if (!busy && !snapshot.refreshing) {
                busy = true
                scope.launch {
                    try {
                        feedback = when (val result = withContext(Dispatchers.IO) {
                            owner.refreshOfficial()
                        }) {
                            PricingUiReceipt.Completed -> "已获取原价格 Repository 的最新状态"
                            is PricingUiReceipt.Failed -> result.message ?: "官方价格刷新失败"
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        feedback = failure.message ?: "价格网络请求失败"
                    } finally { busy = false }
                }
            }
        }, enabled = !busy && !snapshot.refreshing, style = SyButtonStyle.SECONDARY)
        snapshot.models.forEach { model ->
            SyGlassCard(Modifier.fillMaxWidth()) {
                Text(model.displayName + " · " + model.version,
                    style = MaterialTheme.typography.titleMedium)
                Text("模型：" + model.modelId)
                priceRow("谷时", model.offPeak)
                priceRow("峰时", model.peak)
            }
        }
    }
}
