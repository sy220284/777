package com.labteto.dshmobile.ui.screens.local

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import com.shenyan.designsystem.components.SyFlatSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.screens.settings.PermissionSetupFlow
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.theme.rootSurface
import com.shenyan.designsystem.components.SyCategoryRow
import com.shenyan.designsystem.components.SyOnboardingCarousel
import com.shenyan.designsystem.components.SyOnboardingPage
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.SyMetric

/** Existing installations with sessions or a configured model keep their current entry. */
internal fun shouldPresentFirstLaunch(
    dismissed: Boolean,
    hasUserContent: Boolean,
    hasConfiguredModel: Boolean,
): Boolean = !dismissed && !hasUserContent && !hasConfiguredModel

/**
 * One production gesture pager shared with the 10-atlas component lab.
 * Actual usage mode, model entry and authorization remain owned by this feature.
 */
@Composable
internal fun FirstLaunchOnboarding(
    onSkip: () -> Unit,
    onStart: (LocalUsageMode) -> Unit,
    onConfigureModel: (LocalUsageMode) -> Unit,
) {
    val colors = LocalSyColors.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    var mode by rememberSaveable { mutableStateOf(LocalUsageMode.CHAT) }
    BackHandler(enabled = step < 3) {
        if (step > 0) step-- else onSkip()
    }
    if (step == 3) {
        PermissionSetupFlow(onFinished = { onStart(mode) }, onBack = { step = 2 })
        return
    }
    val pages = listOf(
        SyOnboardingPage(
            title = stringResource(R.string.shenyan_onboarding_welcome),
            description = stringResource(R.string.shenyan_onboarding_intro),
            illustration = {
                SyFlatSurface(
                    modifier = Modifier.padding(vertical = SyMetric.gapSm),
                    shape = RoundedCornerShape(SyMetric.radiusCard),
                    color = colors.materialL2,
                ) {
                    Box(Modifier.padding(SyMetric.gapLg), contentAlignment = Alignment.Center) {
                        Icon(FeatherIcons.MessageCircle, contentDescription = null,
                            tint = colors.primary)
                    }
                }
            },
        ),
        SyOnboardingPage(
            title = stringResource(R.string.shenyan_onboarding_choose),
            description = stringResource(R.string.shenyan_onboarding_mode_intro),
            illustration = {
                SyFlatSurface(
                    modifier = Modifier.padding(vertical = SyMetric.gapSm),
                    shape = RoundedCornerShape(SyMetric.radiusCard),
                    color = colors.materialL2,
                ) {
                    Box(Modifier.padding(SyMetric.gapLg), contentAlignment = Alignment.Center) {
                        Icon(
                            if (mode == LocalUsageMode.CHAT) FeatherIcons.MessageCircle
                            else FeatherIcons.Zap,
                            contentDescription = null,
                            tint = colors.primary,
                        )
                    }
                }
            },
        ),
        SyOnboardingPage(
            title = stringResource(R.string.shenyan_onboarding_permission_later),
            description = stringResource(R.string.shenyan_onboarding_privacy_intro),
            illustration = {
                SyFlatSurface(
                    modifier = Modifier.padding(vertical = SyMetric.gapSm),
                    shape = RoundedCornerShape(SyMetric.radiusCard),
                    color = colors.materialL2,
                ) {
                    Box(Modifier.padding(SyMetric.gapLg), contentAlignment = Alignment.Center) {
                        Icon(FeatherIcons.Shield, contentDescription = null, tint = colors.primary)
                    }
                }
            },
        ),
    )
    SyFlatSurface(Modifier.fillMaxSize(), color = colors.rootSurface()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding()
                .verticalScroll(rememberScrollState()).padding(SyMetric.gapSection),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SyOnboardingCarousel(
                pages = pages,
                modifier = Modifier.fillMaxWidth().widthIn(max = 440.dp),
                selectedPage = step,
                onPageChanged = { step = it },
                onSkip = onSkip,
                onComplete = { step = 3 },
                extraPageContent = { index ->
                    if (index == 1) {
                        Column(Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm)) {
                            SyCategoryRow(
                                icon = FeatherIcons.MessageCircle,
                                title = stringResource(R.string.shenyan_onboarding_chat),
                                value = if (mode == LocalUsageMode.CHAT)
                                    stringResource(R.string.shenyan_onboarding_selected) else null,
                                onClick = { mode = LocalUsageMode.CHAT },
                            )
                            SyCategoryRow(
                                icon = FeatherIcons.Zap,
                                title = stringResource(R.string.shenyan_onboarding_work),
                                value = if (mode == LocalUsageMode.WORK)
                                    stringResource(R.string.shenyan_onboarding_selected) else null,
                                onClick = { mode = LocalUsageMode.WORK },
                            )
                        }
                    }
                },
                actions = { index, next, back ->
                    Column(Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm)) {
                        SyButton(
                            text = stringResource(
                                if (index == 0) R.string.shenyan_onboarding_try_chat
                                else R.string.shenyan_onboarding_continue),
                            onClick = next,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SyButton(
                            text = stringResource(when (index) {
                                0 -> R.string.shenyan_onboarding_skip
                                1 -> R.string.common_back
                                else -> R.string.shenyan_onboarding_setup_model
                            }),
                            onClick = { when (index) {
                                0 -> onSkip()
                                1 -> back()
                                else -> onConfigureModel(mode)
                            } },
                            modifier = Modifier.fillMaxWidth(),
                            variant = SyButtonVariant.Ghost,
                        )
                    }
                },
            )
        }
    }
}
