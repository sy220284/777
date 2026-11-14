package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.labteto.dshmobile.local.model.DeepSeekProviderBalance
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.SyMetric
import kotlinx.coroutines.CancellationException

/** Displays verified account API values, independent of locally measured token usage. */
@Composable
internal fun DeepSeekBalanceCard(
    profile: LocalModelProfile,
    queryBalance: suspend (LocalModelProfile) -> DeepSeekProviderBalance,
) {
    val colors = LocalSyColors.current
    var refresh by remember(profile.id) { mutableIntStateOf(0) }
    var loading by remember(profile.id) { mutableStateOf(true) }
    var balance by remember(profile.id) { mutableStateOf<DeepSeekProviderBalance?>(null) }
    var error by remember(profile.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(profile, refresh) {
        loading = true
        balance = null
        error = null
        try {
            balance = queryBalance(profile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "当前无法读取余额"
        } finally {
            loading = false
        }
    }

    AppSettingsSection {
        Column(
            modifier = Modifier.fillMaxWidth().padding(SyMetric.gapSection),
            verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
        ) {
            Text("DeepSeek 官方账户余额", style = DsType.std14Strong.withReadingWeight(),
                color = colors.text)
            Text("使用已保存的官方 API Key 查询。金额来自服务商账户，不以本机用量推算。",
                style = DsType.caption11.withReadingWeight(), color = colors.textSecondary)
            SyButton(
                text = if (loading) "正在查询余额…" else "刷新真实余额",
                onClick = { if (!loading) refresh++ },
                enabled = !loading,
                variant = SyButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Text("余额查询失败：$it", style = DsType.small13.withReadingWeight(),
                    color = colors.dangerInk)
            }
            balance?.let { latest ->
                Text(
                    if (latest.available) "服务商状态：账户可调用" else "服务商状态：余额不足或不可调用",
                    style = DsType.small13Strong.withReadingWeight(), color = colors.text,
                )
                if (latest.amounts.isEmpty()) {
                    Text("服务商未提供余额币种详情",
                        style = DsType.small13.withReadingWeight(), color = colors.textSecondary)
                }
                latest.amounts.forEach { amount ->
                    Text("${amount.currency} 可用余额：${amount.total}",
                        style = DsType.std14Strong.withReadingWeight(), color = colors.text)
                    Text("赠金 ${amount.granted} · 充值 ${amount.toppedUp}",
                        style = DsType.small13.withReadingWeight(), color = colors.textSecondary)
                }
            }
        }
    }
}
