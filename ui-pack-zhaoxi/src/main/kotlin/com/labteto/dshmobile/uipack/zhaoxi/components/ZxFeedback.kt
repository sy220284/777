package com.labteto.dshmobile.uipack.zhaoxi.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiDuration
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiMotion
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiMaterial
import com.labteto.dshmobile.uipack.zhaoxi.theme.zxMaterial

/**
 * Zx 反馈与状态组件（E8.14 实装）。规格：实施规格 §1.2。
 * Sheet/Dialog 打开时焦点圈入与 TalkBack 顺序由调用侧语义树保证。
 */

enum class ZxTone { Info, Warn, Err }

/** 全局 Toast 宿主：底部 96dp，3s 消散由宿主协程控制，队列最长 2。 */
data class ZxToast(val message: String, val tone: ZxTone = ZxTone.Info, val action: String? = null)

@Composable
fun ZxToastHost(toast: ZxToast?, onAction: () -> Unit = {}, modifier: Modifier = Modifier) {
    val c = ZhaoxiTheme.colors
    AnimatedVisibility(
        visible = toast != null,
        enter = slideInVertically(ZhaoxiMotion.spec(ZhaoxiDuration.NORMAL, spring = true)) { it } + fadeIn(animationSpec = ZhaoxiMotion.spec(ZhaoxiDuration.FAST)),
        exit = slideOutVertically(ZhaoxiMotion.spec(ZhaoxiDuration.FAST)) { it } + fadeOut(animationSpec = ZhaoxiMotion.spec(ZhaoxiDuration.FAST)),
        modifier = modifier,
    ) {
        toast?.let { t ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.ink)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(t.message, color = c.onInk, fontSize = 13.sp)
                t.action?.let { a ->
                    Box(
                        Modifier.heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button, onClick = onAction)
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(a, color = c.onInk, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** Destructive confirmation is a real modal with system back and focus confinement. */
@Composable
fun ZxDialog(
    title: String,
    body: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ZhaoxiTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier.fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .zxMaterial(ZhaoxiMaterial.M3)
                .padding(20.dp),
        ) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
            Spacer(Modifier.height(8.dp))
            Text(body, fontSize = 13.sp, color = c.ink2, lineHeight = 20.sp)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZxButton("取消", onDismiss, Modifier.weight(1f), ZxButtonVariant.Outline)
                ZxButton(confirmText, onConfirm, Modifier.weight(1f), ZxButtonVariant.Danger)
            }
        }
    }
}

/** M3 底弹层容器：上托/下滑动效由宿主 Sheet 状态驱动，此处提供内容骨架。 */
@Composable
fun ZxSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val c = ZhaoxiTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = ZhaoxiTheme.shapes.sheet, topEnd = ZhaoxiTheme.shapes.sheet))
            .zxMaterial(ZhaoxiMaterial.M3)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(c.line),
        )
        Spacer(Modifier.height(10.dp))
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

enum class ZxSkeletonVariant { Row, Text, Card }

/** reduced-motion 时静态呈现（动画入口在 ZhaoxiMotion，已降级）。 */
@Composable
fun ZxSkeleton(variant: ZxSkeletonVariant, modifier: Modifier = Modifier) {
    val c = ZhaoxiTheme.colors
    val h = when (variant) {
        ZxSkeletonVariant.Row -> 44.dp
        ZxSkeletonVariant.Text -> 16.dp
        ZxSkeletonVariant.Card -> 96.dp
    }
    Box(modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(10.dp)).background(c.bg2))
}

@Composable
fun ZxProgressBar(progress: Float?, modifier: Modifier = Modifier) {
    val c = ZhaoxiTheme.colors
    if (progress == null) {
        CircularProgressIndicator(modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
    } else {
        Box(
            modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.bg2),
        ) {
            Box(
                Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(6.dp)
                    .clip(RoundedCornerShape(3.dp)).background(c.accent),
            )
        }
    }
}

@Composable
fun ZxTimeSeparator(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().padding(vertical = 10.dp),
        textAlign = TextAlign.Center,
        fontSize = 11.sp,
        color = ZhaoxiTheme.colors.ink3,
    )
}

/** 左 3px 色条横幅。 */
@Composable
fun ZxBanner(
    text: String,
    tone: ZxTone,
    modifier: Modifier = Modifier,
    action: (Pair<String, () -> Unit>)? = null,
) {
    val c = ZhaoxiTheme.colors
    val toneColor = when (tone) {
        ZxTone.Info -> c.accent
        ZxTone.Warn -> c.warn
        ZxTone.Err -> c.err
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.bg2)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 3.dp, height = 28.dp).background(toneColor))
        Text(text, Modifier.weight(1f).padding(horizontal = 12.dp), fontSize = 12.5.sp, color = c.ink2)
        action?.let { (label, run) ->
            Box(
                Modifier.heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClick = run)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontSize = 12.sp, color = toneColor,
                    fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** 区块小标题（sect）。 */
@Composable
fun ZxSection(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp),
        fontSize = 12.sp, color = ZhaoxiTheme.colors.ink2, fontWeight = FontWeight.SemiBold)
}

/** 页面注脚。 */
@Composable
fun ZxFooterNote(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp),
        textAlign = TextAlign.Start, fontSize = 12.sp, lineHeight = 18.sp, color = ZhaoxiTheme.colors.ink2)
}
