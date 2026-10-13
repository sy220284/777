package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.foundation.SyMetric

import com.shenyan.designsystem.components.SyMaterialTier

import com.shenyan.designsystem.components.SyMaterialSurface


import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.interaction.LocalQuestion
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.components.MarkdownText
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

private const val PLAN_REVIEW_PREFIX = "Harness 已完成计划，是否批准并进入执行模式？"

internal data class LocalPlanReview(
    val plan: String,
    val approve: String,
    val decline: String?,
    val regenerate: String,
)

internal fun localPlanReviewOf(question: LocalQuestion): LocalPlanReview? {
    if (!question.question.startsWith(PLAN_REVIEW_PREFIX)) return null
    val plan = question.question.substringAfter("\n\n", "").trim()
    if (plan.isBlank()) return null
    val approve = question.options.firstOrNull()?.takeIf(String::isNotBlank) ?: return null
    return LocalPlanReview(
        plan = plan,
        approve = approve,
        decline = question.options.getOrNull(1)?.takeIf(String::isNotBlank),
        // Existing saved reviews may predate the explicit third option.
        regenerate = question.options.getOrNull(2)?.takeIf(String::isNotBlank) ?: "重新生成方案",
    )
}

@Composable
internal fun LocalPlanReviewCard(
    review: LocalPlanReview,
    busy: Boolean,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onRegenerate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.chat_copy_success)

    SyMaterialSurface(
        modifier = modifier.fillMaxWidth(),
        shape = DsShapes.approvalCard,
        tier = SyMaterialTier.L3_CARD,
        border = BorderStroke(1.dp, colors.border),
    ) {
        Column(
            modifier = Modifier.padding(SyMetric.gapLg),
            verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
        ) {
            Text(
                stringResource(R.string.plan_review_title),
                style = DsType.small13Strong.withReadingWeight(),
                color = colors.text,
            )
            MarkdownText(review.plan)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
            ) {
                SyButton(
                    text = stringResource(R.string.local_plan_review_confirm),
                    onClick = onApprove,
                    enabled = !busy,
                    variant = SyButtonVariant.Info,
                    size = SyButtonSize.Small,
                )
                review.decline?.let {
                    SyButton(
                        text = stringResource(R.string.local_plan_review_decline),
                        onClick = onDecline,
                        enabled = !busy,
                        variant = SyButtonVariant.Outline,
                        size = SyButtonSize.Small,
                    )
                }
                Spacer(Modifier.weight(1f))
                CompactMessageAction(
                    icon = FeatherIcons.Copy,
                    contentDescription = stringResource(R.string.local_plan_review_copy),
                    onClick = {
                        clipboard.setText(AnnotatedString(review.plan))
                        Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                    },
                    enabled = !busy,
                )
                CompactMessageAction(
                    icon = FeatherIcons.RefreshCw,
                    contentDescription = stringResource(R.string.local_plan_review_regenerate),
                    onClick = onRegenerate,
                    enabled = !busy,
                )
            }
        }
    }
}
