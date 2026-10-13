package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.foundation.SyMetric

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.shenyan.designsystem.components.SyCategoryRow
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.rootSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight

/** Existing installations do not suddenly get blocked by a first-launch tour after upgrade. */
internal fun shouldPresentFirstLaunch(
    dismissed: Boolean,
    hasUserContent: Boolean,
    hasConfiguredModel: Boolean,
): Boolean = !dismissed && !hasUserContent && !hasConfiguredModel

/** Three real onboarding steps. No Android permission is requested until a later explicit action. */
@Composable
internal fun FirstLaunchOnboarding(
    onSkip: () -> Unit,
    onStart: (LocalUsageMode) -> Unit,
    onConfigureModel: (LocalUsageMode) -> Unit,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    var mode by rememberSaveable { mutableStateOf(LocalUsageMode.CHAT) }
    BackHandler {
        if (step > 0) step-- else onSkip()
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.rootSurface(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(SyMetric.gapSection),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier.size(64.dp),
                color = com.shenyan.designsystem.foundation.LocalSyColors.current.materialL2,
                shape = RoundedCornerShape(18.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(if (step == 2) FeatherIcons.Shield else if (mode == LocalUsageMode.CHAT)
                        FeatherIcons.MessageCircle else FeatherIcons.Zap,
                        contentDescription = null, tint = colors.text, modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.height(SyMetric.gapPage))
            AnimatedContent(
                targetState = step,
                modifier = Modifier.widthIn(max = 380.dp),
                transitionSpec = {
                    fadeIn(DsAnimations.fade) togetherWith fadeOut(DsAnimations.fade)
                },
                label = "onboardingStep",
            ) { current ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(SyMetric.gapLg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val title = when (current) {
                        0 -> R.string.shenyan_onboarding_welcome
                        1 -> R.string.shenyan_onboarding_choose
                        else -> R.string.shenyan_onboarding_permission_later
                    }
                    val subtitle = when (current) {
                        0 -> R.string.shenyan_onboarding_intro
                        1 -> R.string.shenyan_onboarding_mode_intro
                        else -> R.string.shenyan_onboarding_privacy_intro
                    }
                    Text(
                        stringResource(title),
                        style = DsType.large20.withReadingWeight(),
                        color = colors.text,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        stringResource(subtitle),
                        style = DsType.std14.withReadingWeight(),
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                    if (current == 1) {
                        com.shenyan.designsystem.components.SyMaterialFrame(
                            horizontalPadding = 0.dp, verticalPadding = 0.dp,
                        ) {
                            Column(Modifier.padding(SyMetric.gapMd)) {
                                SyCategoryRow(
                                    icon = FeatherIcons.MessageCircle,
                                    title = stringResource(R.string.shenyan_onboarding_chat),
                                                        value = if (mode == LocalUsageMode.CHAT) {
                                        stringResource(R.string.shenyan_onboarding_selected)
                                    } else null,
                                    onClick = { mode = LocalUsageMode.CHAT },
                                )
                                SyCategoryRow(
                                    icon = FeatherIcons.Zap,
                                    title = stringResource(R.string.shenyan_onboarding_work),
                                                        value = if (mode == LocalUsageMode.WORK) {
                                        stringResource(R.string.shenyan_onboarding_selected)
                                    } else null,
                                    onClick = { mode = LocalUsageMode.WORK },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(SyMetric.gapPage))
            Row(horizontalArrangement = Arrangement.spacedBy(SyMetric.gapXs)) {
                repeat(3) { index ->
                    Spacer(
                        Modifier
                            .width(if (index == step) 24.dp else 8.dp)
                            .height(3.dp)
                            .background(
                                if (index == step) colors.primary else colors.border,
                                RoundedCornerShape(2.dp),
                            ),
                    )
                }
            }
            Spacer(Modifier.height(SyMetric.gapPage))
            SyButton(
                text = stringResource(
                    when (step) {
                        0 -> R.string.shenyan_onboarding_try_chat
                        1 -> R.string.shenyan_onboarding_continue
                        else -> R.string.shenyan_onboarding_start
                    },
                ),
                onClick = { if (step < 2) step++ else onStart(mode) },
                modifier = Modifier.fillMaxWidth().widthIn(max = 380.dp),
            )
            SyButton(
                text = stringResource(
                    when (step) {
                        0 -> R.string.shenyan_onboarding_skip
                        1 -> R.string.common_back
                        else -> R.string.shenyan_onboarding_setup_model
                    },
                ),
                onClick = {
                    when (step) {
                        0 -> onSkip()
                        1 -> step--
                        else -> onConfigureModel(mode)
                    }
                },
                modifier = Modifier.fillMaxWidth().widthIn(max = 380.dp),
                variant = SyButtonVariant.Ghost,
            )
        }
    }
}
