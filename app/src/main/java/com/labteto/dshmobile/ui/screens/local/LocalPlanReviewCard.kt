package com.labteto.dshmobile.ui.screens.local

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Surface
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
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.MarkdownText
import com.labteto.dshmobile.ui.theme.BackgroundRegion
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight

private const val PLAN_REVIEW_PREFIX = "Harness 已完成计划，是否批准并进入执行模式？"

internal data class LocalPlanReview(
    val plan: String,
    val approve: String,
    val decline: String?,
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
    val colors = DsTheme.colors
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.chat_copy_success)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = DsShapes.approvalCard,
        color = colors.wallpaperSurface(
            WallpaperSurfaceLevel.CARD,
            BackgroundRegion.MIDDLE,
            colors.composerCard,
        ),
        border = BorderStroke(1.dp, colors.borderL1),
    ) {
        Column(
            modifier = Modifier.padding(DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Text(
                stringResource(R.string.plan_review_title),
                style = DsType.small13Strong.withReadingWeight(),
                color = colors.labelPrimary,
            )
            MarkdownText(review.plan)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            ) {
                DsButton(
                    text = stringResource(R.string.local_plan_review_confirm),
                    onClick = onApprove,
                    enabled = !busy,
                    variant = DsButtonVariant.Info,
                    size = DsButtonSize.Small,
                )
                review.decline?.let {
                    DsButton(
                        text = stringResource(R.string.local_plan_review_decline),
                        onClick = onDecline,
                        enabled = !busy,
                        variant = DsButtonVariant.Outline,
                        size = DsButtonSize.Small,
                    )
                }
                Spacer(Modifier.weight(1f))
                CompactMessageAction(
                    icon = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.local_plan_review_copy),
                    onClick = {
                        clipboard.setText(AnnotatedString(review.plan))
                        Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                    },
                    enabled = !busy,
                )
                CompactMessageAction(
                    icon = Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.local_plan_review_regenerate),
                    onClick = onRegenerate,
                    enabled = !busy,
                )
            }
        }
    }
}
