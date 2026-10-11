package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.MiniMaxProviderUsage
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.CancellationException

/** Provider quota and local token consumption have separate authoritative sources. */
@Composable
internal fun MiniMaxQuotaPage(profile: LocalModelProfile, viewModel: SettingsViewModel) {
    val colors = DsTheme.colors
    var refresh by remember(profile.id) { mutableIntStateOf(0) }
    var loading by remember(profile.id) { mutableStateOf(true) }
    var usage by remember(profile.id) { mutableStateOf<MiniMaxProviderUsage?>(null) }
    var error by remember(profile.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(profile.id, refresh) {
        loading = true
        usage = null
        error = null
        try {
            usage = viewModel.queryMiniMaxQuota(profile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: ""
        } finally {
            loading = false
        }
    }

    AppSettingsSection {
        Text(stringResource(R.string.minimax_quota_model, profile.model),
            style = DsType.std14Strong.withReadingWeight(), color = colors.labelPrimary)
        Text(stringResource(R.string.minimax_quota_disclaimer),
            style = DsType.caption11.withReadingWeight(), color = colors.labelSecondary)
        DsButton(
            text = stringResource(if (loading) R.string.minimax_quota_loading else R.string.minimax_quota_refresh),
            onClick = { if (!loading) refresh++ },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
        )
        if (loading) {
            Text(stringResource(R.string.minimax_quota_querying),
                style = DsType.std14Strong.withReadingWeight(), color = colors.labelSecondary)
        }
        if (error != null) {
            Text(stringResource(R.string.minimax_quota_error, error.orEmpty()),
                style = DsType.std14Strong.withReadingWeight(), color = colors.labelPrimary)
        }
    }

    when (val result = usage) {
        is MiniMaxProviderUsage.AccountBalance -> {
            AppSettingsSection {
                Text(stringResource(R.string.minimax_quota_balance_title),
                    style = DsType.std14Strong.withReadingWeight(), color = colors.labelPrimary)
                Text(stringResource(R.string.minimax_quota_balance_available, result.available),
                    color = colors.labelPrimary)
                Text(stringResource(R.string.minimax_quota_balance_cash, result.cash),
                    color = colors.labelSecondary)
                Text(stringResource(R.string.minimax_quota_balance_voucher, result.voucher),
                    color = colors.labelSecondary)
                Text(stringResource(R.string.minimax_quota_balance_credit, result.credit),
                    color = colors.labelSecondary)
            }
        }
        is MiniMaxProviderUsage.TokenPlan -> {
            if (result.models.isEmpty()) {
                AppSettingsSection {
                    Text(stringResource(R.string.minimax_quota_empty), color = colors.labelSecondary)
                }
            }
            result.models.forEach { quota ->
                AppSettingsSection {
                    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                        Text(quota.name, style = DsType.std14Strong.withReadingWeight(),
                            color = colors.labelPrimary)
                        Text(stringResource(R.string.minimax_quota_interval, quota.interval.display()),
                            color = colors.labelSecondary)
                        Text(stringResource(R.string.minimax_quota_weekly, quota.weekly.display()),
                            color = colors.labelSecondary)
                        quota.intervalResetMillis?.takeIf { it >= 0 }?.let { remaining ->
                            Text(stringResource(R.string.minimax_quota_interval_reset, remaining.durationDisplay()),
                                style = DsType.caption11.withReadingWeight(), color = colors.labelSecondary)
                        }
                        quota.weeklyResetMillis?.takeIf { it >= 0 }?.let { remaining ->
                            Text(stringResource(R.string.minimax_quota_weekly_reset, remaining.durationDisplay()),
                                style = DsType.caption11.withReadingWeight(), color = colors.labelSecondary)
                        }
                    }
                }
            }
        }
        null -> Unit
    }
}

@Composable
private fun MiniMaxProviderUsage.Window.display(): String = when {
    notInPlan -> stringResource(R.string.minimax_quota_not_in_plan)
    unlimited -> stringResource(R.string.minimax_quota_unlimited)
    remaining != null && total != null ->
        stringResource(R.string.minimax_quota_remaining_count, remaining, total)
    remainingPercent != null ->
        stringResource(R.string.minimax_quota_remaining_percent,
            "%.1f".format(java.util.Locale.ROOT, remainingPercent))
    else -> stringResource(R.string.minimax_quota_unknown)
}

@Composable
private fun Long.durationDisplay(): String {
    val seconds = coerceAtLeast(0) / 1000
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        days > 0 -> stringResource(R.string.minimax_quota_days_hours, days, hours)
        hours > 0 -> stringResource(R.string.minimax_quota_hours_minutes, hours, minutes)
        minutes > 0 -> stringResource(R.string.minimax_quota_minutes, minutes)
        else -> stringResource(R.string.minimax_quota_under_one_minute)
    }
}
