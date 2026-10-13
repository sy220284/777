package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.labteto.dshmobile.ui.shenyan.SyProgressBar
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
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.CancellationException

/** Provider quota and local token consumption have separate authoritative sources. */
@Composable
internal fun MiniMaxQuotaPage(
    profile: LocalModelProfile?,
    loadingProfile: Boolean,
    onConfigureModel: () -> Unit,
    queryQuota: suspend (LocalModelProfile) -> MiniMaxProviderUsage,
) {
    val colors = DsTheme.colors
    var refresh by remember(profile) { mutableIntStateOf(0) }
    var loading by remember(profile) { mutableStateOf(true) }
    var usage by remember(profile) { mutableStateOf<MiniMaxProviderUsage?>(null) }
    var error by remember(profile) { mutableStateOf<String?>(null) }

    LaunchedEffect(profile, refresh, loadingProfile) {
        if (loadingProfile || profile == null) return@LaunchedEffect
        loading = true
        usage = null
        error = null
        try {
            usage = queryQuota(profile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: ""
        } finally {
            loading = false
        }
    }

    // Missing or temporarily stale configuration changes content, never navigation.
    if (loadingProfile || profile == null) {
        AppSettingsSection {
            Column(
                modifier = Modifier.padding(DsSpacing.comfortable),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
            ) {
                Text(
                    stringResource(if (loadingProfile) R.string.minimax_quota_loading_profile else R.string.minimax_quota_no_profile),
                    style = DsType.std14Strong.withReadingWeight(), color = colors.labelSecondary,
                )
                if (!loadingProfile) {
                    SyButton(
                        text = stringResource(R.string.work_capability_configure_model),
                        onClick = onConfigureModel,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        return
    }

    AppSettingsSection {
        Column(
            modifier = Modifier.padding(DsSpacing.comfortable),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
        ) {
            Text(stringResource(R.string.minimax_quota_model, profile.model),
                style = DsType.std14Strong.withReadingWeight(), color = colors.labelPrimary)
            Text(stringResource(R.string.minimax_quota_disclaimer),
                style = DsType.small13.withReadingWeight(), color = colors.labelSecondary)
            SyButton(
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
                    style = DsType.std14Strong.withReadingWeight(), color = colors.error)
            }
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
            result.models.forEach { quota -> MiniMaxQuotaCard(quota) }

        }
        null -> Unit
    }
}

@Composable
internal fun MiniMaxQuotaCard(quota: MiniMaxProviderUsage.ModelQuota) {
    val colors = DsTheme.colors
    AppSettingsSection {
        Column(
            modifier = Modifier.fillMaxWidth().padding(DsSpacing.comfortable),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.large),
        ) {
            Text(
                if (quota.name.equals("general", ignoreCase = true)) {
                    stringResource(R.string.minimax_quota_shared)
                } else quota.name,
                style = DsType.headline17.withReadingWeight(), color = colors.labelPrimary,
            )
            MiniMaxQuotaWindow(
                title = stringResource(R.string.minimax_quota_interval_label),
                window = quota.interval, resetMillis = quota.intervalResetMillis,
            )
            MiniMaxQuotaWindow(
                title = stringResource(R.string.minimax_quota_weekly_label),
                window = quota.weekly, resetMillis = quota.weeklyResetMillis,
            )
        }
    }
}

@Composable
private fun MiniMaxQuotaWindow(
    title: String,
    window: MiniMaxProviderUsage.Window,
    resetMillis: Long?,
) {
    val colors = DsTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        Text(title, style = DsType.small13.withReadingWeight(), color = colors.labelSecondary)
        Text(window.display(), style = DsType.title22.withReadingWeight(), color = colors.labelPrimary)
        window.remainingFraction()?.let { fraction ->
            SyProgressBar(fraction, modifier = Modifier.fillMaxWidth(), color = colors.successSecondary)
        }
        resetMillis?.takeIf { it >= 0 }?.let {
            Text(stringResource(R.string.minimax_quota_reset_after, it.durationDisplay()),
                style = DsType.small13.withReadingWeight(), color = colors.labelSecondary)
        }
    }
}

/** No progress bar for unavailable, unlimited or non-plan quota. */
internal fun MiniMaxProviderUsage.Window.remainingFraction(): Float? {
    if (notInPlan || unlimited) return null
    val percent = remainingPercent?.takeIf { it.isFinite() && it in 0.0..100.0 }
    if (percent != null) return (percent / 100.0).toFloat()
    val capacity = total?.takeIf { it > 0 } ?: return null
    val count = remaining?.takeIf { it in 0..capacity } ?: return null
    return (count.toDouble() / capacity).toFloat()
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
