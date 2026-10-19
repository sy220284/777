package com.labteto.dshmobile.uipack.zhaoxi.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labteto.dshmobile.uicontract.UiActionAvailability
import com.labteto.dshmobile.uicontract.UiApprovalImpact
import com.labteto.dshmobile.uicontract.UiApprovalSnapshot
import com.labteto.dshmobile.uicontract.UiCommandRejection
import com.labteto.dshmobile.uicontract.UiMessageRole
import com.labteto.dshmobile.uicontract.UiMessageSnapshot
import com.labteto.dshmobile.uicontract.UiQuestionSnapshot
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme
import com.labteto.dshmobile.uipack.zhaoxi.theme.LocalReducedMotion

/**
 * Zx 会话域组件（E8.14 实装）。规格：实施规格 §1.3。
 * 行为基准 = ui-pack-v6 同族组件；拒收回执一律保留草稿 + toast 呈现。
 */

@Composable
fun ZxAvatar(name: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    val c = ZhaoxiTheme.colors
    Box(
        modifier.size(26.dp).clip(CircleShape)
            .background(if (accent) c.accent else c.bg2),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.take(1), fontSize = 11.sp, fontWeight = FontWeight.Bold,
            color = if (accent) c.onInk else c.ink2)
    }
}

/** 助手无气泡阅读流；用户右对齐浅底块；长按出操作层；分支切换器见 ZxBranchSwitcher。 */
@Composable
fun ZxMessageRow(
    msg: UiMessageSnapshot,
    authorName: String,
    onLongPress: (UiMessageSnapshot) -> Unit,
    onBranchSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ZhaoxiTheme.colors
    val isUser = msg.role == UiMessageRole.USER
    Column(
        modifier
            .fillMaxWidth()
            .pointerInput(msg.id) { detectTapGestures(onLongPress = { onLongPress(msg) }) }
            .padding(vertical = if (msg.progressive) 2.dp else 6.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (!isUser && !msg.progressive) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ZxAvatar(authorName)
                Text(authorName, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
                if (msg.proactive) ZxBadge("主动", ZxBadgeTone.Accent)
            }
            Spacer(Modifier.height(4.dp))
        }
        Box(
            Modifier
                .then(if (isUser) Modifier.widthIn(max = 300.dp) else Modifier.fillMaxWidth())
                .then(
                    if (isUser) Modifier.clip(RoundedCornerShape(ZhaoxiTheme.shapes.msg)).background(c.bg2)
                    else Modifier,
                )
                .padding(horizontal = if (isUser) 12.dp else 0.dp, vertical = if (isUser) 9.dp else 0.dp),
        ) {
            if (isUser) Text(msg.text, fontSize = 15.sp, lineHeight = 23.sp, color = c.ink)
            else ZxRichReply(msg.text)
        }
        msg.branch?.let { b ->
            if (b.count > 1) ZxBranchSwitcher(b.index, b.count, onSelect = onBranchSelect)
        }
    }
}

/** 分支/变体切换器（chat.branches / chat.select_variant）。 */
@Composable
fun ZxBranchSwitcher(index: Int, count: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val c = ZhaoxiTheme.colors
    Row(
        modifier.padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZxChip("‹", false, onClick = { if (index > 0) onSelect(index - 1) })
        Text("${index + 1} / $count", fontSize = 10.5.sp, color = c.accentInk,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        ZxChip("›", false, onClick = { if (index < count - 1) onSelect(index + 1) })
        Text("分支 · 变体可切换", fontSize = 10.sp, color = c.ink3)
    }
}

/** 能力胶囊条：模型/温度/推理/计划/交接，值摘要。 */
data class ZxCap(val key: String, val label: String, val active: Boolean)

@Composable
fun ZxCapBar(caps: List<ZxCap>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = ZhaoxiTheme.colors
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        caps.forEach { cap ->
            val interaction = remember(cap.key) { MutableInteractionSource() }
            Box(
                Modifier
                    .zxPressSurface(interaction)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (cap.active) c.tint else c.bg2)
                    .clickable(
                        interactionSource = interaction,
                        indication = androidx.compose.foundation.LocalIndication.current,
                        role = Role.Button) { onOpen(cap.key) }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(cap.label, fontSize = 12.sp,
                    color = if (cap.active) c.accentInk else c.ink2)
            }
        }
    }
}

/** Manual @skill invocation needs an actual task after its first-line marker. */
internal fun zxComposerCanSendDraft(draft: String): Boolean {
    if (draft.isBlank()) return false
    val matched = Regex("^@skill:[a-z][a-z0-9-]{1,47}(?:\\r?\\n|$)").find(draft)
        ?: return true
    return draft.substring(matched.range.last + 1).isNotBlank()
}

/** 输入区：拒绝时保留草稿 + toast（回执矩阵见 朝夕-R4接入设计 §4）。 */
@Composable
fun ZxComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    send: UiActionAvailability,
    onSend: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    running: Boolean = false,
    onStop: () -> Unit = {},
    onConnectModel: (() -> Unit)? = null,
    onAddClick: (() -> Unit)? = null,
) {
    val c = ZhaoxiTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(c.glass)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onAddClick != null) {
            ZxIconButton(onClick = onAddClick, contentDescription = "添加拍照、图片、文件或创作") {
                ZxIcon("plus")
            }
        }
        ZxField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = if (send.available) placeholder else rejectionHint(send.reason),
            modifier = Modifier.weight(1f),
            inputTestTag = "zhaoxi.composer.input",
        )
        if (running) {
            ZxIconButton(onClick = onStop, contentDescription = "停止生成回复") {
                ZxIcon("stop")
            }
        } else if (send.reason == UiCommandRejection.UNCONFIGURED && onConnectModel != null) {
            ZxButton("去连接", onClick = onConnectModel, variant = ZxButtonVariant.Outline)
        } else {
            ZxIconButton(onClick = onSend,
                contentDescription = if (draft.startsWith("@skill:") && !zxComposerCanSendDraft(draft))
                    "先填写技能要处理的任务" else "发送消息",
                enabled = send.available && zxComposerCanSendDraft(draft)) {
                ZxIcon("send")
            }
        }
    }
}

fun rejectionHint(reason: UiCommandRejection?): String = when (reason) {
    UiCommandRejection.EMPTY_INPUT -> "先写点什么"
    UiCommandRejection.LOADING -> "会话加载中…"
    UiCommandRejection.UNCONFIGURED -> "先到设置接入模型"
    UiCommandRejection.STALE_IDENTITY -> "会话已切换 · 请重试"
    UiCommandRejection.SESSION_TRANSITION -> "会话切换中…"
    UiCommandRejection.QUEUE_FULL -> "队列已满 · 稍后再发"
    UiCommandRejection.QUEUE_UNAVAILABLE -> "当前不可排队"
    UiCommandRejection.APPROVAL_EXPIRED -> "这项审批已结束"
    UiCommandRejection.QUESTION_EXPIRED -> "这次提问已结束"
    UiCommandRejection.UNSUPPORTED, null -> "暂不可用"
}

/** 审批卡：M3 + warn 边条；批准/拒绝本次操作；切换会话后审批失效。 */
@Composable
fun ZxApprovalCard(
    approval: UiApprovalSnapshot,
    expired: Boolean,
    modifier: Modifier = Modifier,
    onAnswer: (Boolean) -> Unit,
) {
    val c = ZhaoxiTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.glass2)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZxBadge(
                when (approval.impact) {
                    UiApprovalImpact.LOW -> "低风险"
                    UiApprovalImpact.MEDIUM -> "中风险"
                    UiApprovalImpact.HIGH -> "高风险"
                    UiApprovalImpact.CRITICAL -> "关键操作"
                },
                if (approval.impact >= UiApprovalImpact.HIGH) ZxBadgeTone.Err else ZxBadgeTone.Warn,
            )
            Text(approval.toolName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.ink,
                fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(6.dp))
        Text(approval.summary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
        if (approval.arguments.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(approval.arguments, fontSize = 12.sp, color = c.ink2, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(10.dp))
        if (expired) {
            Text("这项审批已结束", fontSize = 12.sp, color = c.ink3)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZxButton("批准本次操作", { onAnswer(true) }, Modifier.weight(1f))
                ZxButton("拒绝本次操作", { onAnswer(false) }, Modifier.weight(1f), ZxButtonVariant.Outline)
            }
            Spacer(Modifier.height(8.dp))
            Text("切换会话后审批失效 · 过期将提示「这项审批已结束」",
                fontSize = 9.5.sp, color = c.ink3, fontFamily = FontFamily.Monospace)
        }
    }
}

/** 提问卡：null = 显式取消；回答后继续执行。 */
@Composable
fun ZxQuestionCard(
    question: UiQuestionSnapshot,
    answer: String,
    onAnswerChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onAnswer: (String?) -> Unit,
) {
    val c = ZhaoxiTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.glass2)
            .padding(14.dp),
    ) {
        ZxBadge("模型提问", ZxBadgeTone.Accent)
        Spacer(Modifier.height(6.dp))
        Text(question.question, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
        if (question.options.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                question.options.forEach { ZxChip(it, answer == it, onClick = { onAnswerChange(it) }) }
            }
        }
        Spacer(Modifier.height(8.dp))
        ZxField(answer, onAnswerChange, Modifier.fillMaxWidth(), placeholder = "输入回答…")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZxButton("回答并继续", { onAnswer(answer.ifBlank { null }) }, Modifier.weight(1f))
            ZxButton("取消提问", { onAnswer(null) }, Modifier.weight(1f), ZxButtonVariant.Outline)
        }
        Spacer(Modifier.height(6.dp))
        Text("取消 = 显式回答 null · 任务转入等待指令", fontSize = 9.5.sp, color = c.ink3,
            fontFamily = FontFamily.Monospace)
    }
}

/** 三点输入指示。 */
@Composable
fun ZxTypingIndicator(modifier: Modifier = Modifier) {
    // Subtle phase motion; Compose animation clocks honor the platform duration scale.
    val reducedMotion = LocalReducedMotion.current
    val transition = if (reducedMotion) null else rememberInfiniteTransition(label = "zhaoxi-reply-progress")
    val ink = ZhaoxiTheme.colors.ink3
    Row(
        modifier.padding(vertical = 6.dp)
            .semantics { stateDescription = "正在生成回复" },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            // Respect the UI pack's reduced-motion token: no indefinite animation in this mode.
            val opacity = if (transition == null) 0.75f else {
                val animated by transition.animateFloat(
                    initialValue = 0.35f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 480, delayMillis = index * 90),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "reply-progress-dot-$index",
                )
                animated
            }
            Box(Modifier.size(5.dp).alpha(opacity).clip(CircleShape).background(ink))
        }
    }
}

@Composable
fun ZxPersonaCard(
    name: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    pinned: Boolean = false,
    portrait: androidx.compose.ui.graphics.ImageBitmap? = null,
    portraitHint: String? = null,
) {
    val c = ZhaoxiTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(ZhaoxiTheme.shapes.card))
            .background(c.card)
            .heightIn(min = 148.dp)
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (portrait != null) {
            androidx.compose.foundation.Image(
                bitmap = portrait, contentDescription = "$name 的画像",
                modifier = Modifier.fillMaxWidth().height(90.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
            )
        } else ZxAvatar(name, Modifier.size(52.dp))
        Spacer(Modifier.height(8.dp))
        Text(name + if (pinned) " · 置顶" else "", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = c.ink, maxLines = 2)
        Text(subtitle, fontSize = 12.sp, color = c.ink2, maxLines = 2)
        portraitHint?.let { Text(it, fontSize = 11.sp, color = c.ink3, maxLines = 1) }
    }
}
