package com.labteto.dshmobile.ui.screens.local

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
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
    val colors = DsTheme.colors
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
            modifier = Modifier.fillMaxSize().padding(DsSpacing.comfortable),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(colors.accent, colors.successSecondary),
                        ),
                        RoundedCornerShape(18.dp),
                    ),
            )
            Spacer(Modifier.height(DsSpacing.xlarge))
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
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
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
                        color = colors.labelPrimary,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        stringResource(subtitle),
                        style = DsType.std14.withReadingWeight(),
                        color = colors.labelSecondary,
                        textAlign = TextAlign.Center,
                    )
                    if (current == 1) {
                        Surface(
                            shape = DsShapes.block,
                            color = colors.bgLayer1,
                        ) {
                            Column(Modifier.padding(DsSpacing.small)) {
                                SyCategoryRow(
                                    icon = FeatherIcons.MessageCircle,
                                    title = stringResource(R.string.shenyan_onboarding_chat),
                                    subtitle = stringResource(R.string.shenyan_onboarding_chat_hint),
                                    value = if (mode == LocalUsageMode.CHAT) {
                                        stringResource(R.string.shenyan_onboarding_selected)
                                    } else null,
                                    onClick = { mode = LocalUsageMode.CHAT },
                                )
                                SyCategoryRow(
                                    icon = FeatherIcons.Zap,
                                    title = stringResource(R.string.shenyan_onboarding_work),
                                    subtitle = stringResource(R.string.shenyan_onboarding_work_hint),
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
            Spacer(Modifier.height(DsSpacing.xlarge))
            Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                repeat(3) { index ->
                    Spacer(
                        Modifier
                            .width(if (index == step) 24.dp else 8.dp)
                            .height(3.dp)
                            .background(
                                if (index == step) colors.accent else colors.borderL2,
                                RoundedCornerShape(2.dp),
                            ),
                    )
                }
            }
            Spacer(Modifier.height(DsSpacing.xlarge))
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
