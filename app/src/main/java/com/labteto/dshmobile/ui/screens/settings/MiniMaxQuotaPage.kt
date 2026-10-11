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
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.MiniMaxProviderUsage
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.CancellationException

/**
 * Remote provider quota is independent of the on-device model token usage ledger.
 * Fetch only when this page is visible; a profile switch disposes the old request.
 */
@Composable
internal fun MiniMaxQuotaPage(
    profile: LocalModelProfile,
    viewModel: SettingsViewModel,
) {
    val colors = DsTheme.colors
    var refresh by remember(profile.id) { mutableIntStateOf(0) }
    var loading by remember(profile.id) { mutableStateOf(true) }
    var usage by remember(profile.id) { mutableStateOf<MiniMaxProviderUsage?>(null) }
    var error by remember(profile.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(profile.id, refresh) {
        loading = true
        error = null
        try {
            usage = viewModel.queryMiniMaxQuota(profile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "MiniMax 未能返回额度数据"
        } finally {
            loading = false
        }
    }

    AppSettingsSection {
        Text("当前模型：${profile.model}", style = DsType.std14Strong.withReadingWeight(),
            color = colors.labelPrimary)
        Text("数据来自 MiniMax 官方账户接口；与设备上的模型 Token 消耗统计分开显示。",
            style = DsType.caption11.withReadingWeight(), color = colors.labelSecondary)
        DsButton(
            text = if (loading) "查询中…" else "刷新额度",
            onClick = { if (!loading) refresh++ },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
        )
        if (loading) {
            Text("正在向 MiniMax 查询当前账户额度…",
                style = DsType.std14Strong.withReadingWeight(), color = colors.labelSecondary)
        }
        if (error != null) {
            Text("查询失败：${error.orEmpty()}",
                style = DsType.std14Strong.withReadingWeight(), color = colors.labelPrimary)
        }
    }

    when (val result = usage) {
        is MiniMaxProviderUsage.AccountBalance -> {
            AppSettingsSection {
                Text("按量付费账户余额", style = DsType.std14Strong.withReadingWeight(),
                    color = colors.labelPrimary)
                Text("可用金额：${result.available}", color = colors.labelPrimary)
                Text("现金余额：${result.cash}", color = colors.labelSecondary)
                Text("代金券：${result.voucher}", color = colors.labelSecondary)
                Text("信用额度：${result.credit}", color = colors.labelSecondary)
            }
        }
        is MiniMaxProviderUsage.TokenPlan -> {
            if (result.models.isEmpty()) {
                AppSettingsSection { Text("当前账户暂无套餐额度数据。", color = colors.labelSecondary) }
            }
            result.models.forEach { quota ->
                AppSettingsSection {
                    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                        Text(quota.name, style = DsType.std14Strong.withReadingWeight(),
                            color = colors.labelPrimary)
                        Text("本周期：${quota.interval.display()}",
                            color = colors.labelSecondary)
                        Text("本周：${quota.weekly.display()}",
                            color = colors.labelSecondary)
                        quota.intervalResetMillis?.let { remaining ->
                            Text("本周期重置：${remaining.durationDisplay()}后",
                                style = DsType.caption11.withReadingWeight(), color = colors.labelSecondary)
                        }
                        quota.weeklyResetMillis?.let { remaining ->
                            Text("每周重置：${remaining.durationDisplay()}后",
                                style = DsType.caption11.withReadingWeight(), color = colors.labelSecondary)
                        }
                    }
                }
            }
        }
        null -> Unit
    }
}

private fun MiniMaxProviderUsage.Window.display(): String = when {
    notInPlan -> "不在当前套餐内"
    unlimited -> "不限额"
    remaining != null && total != null -> "剩余 ${remaining} / ${total}"
    remainingPercent != null -> "剩余 ${"%.1f".format(java.util.Locale.ROOT, remainingPercent)}%"
    else -> "服务商未提供可确认的剩余额度"
}

private fun Long.durationDisplay(): String {
    val seconds = coerceAtLeast(0) / 1000
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        days > 0 -> "${days}天${hours}小时"
        hours > 0 -> "${hours}小时${minutes}分钟"
        minutes > 0 -> "${minutes}分钟"
        else -> "不到1分钟"
    }
}
