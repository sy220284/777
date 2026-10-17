package com.labteto.dshmobile.uipack.zhaoxi.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme

/**
 * Zx 导航壳 + E8.12/E8.13 缺口组件（E8.14 实装）。
 * 规格：实施规格 §1.4 / §1.5。
 */

/** M2 玻璃顶栏：左返回/菜单，中标题，右动作槽。 */
@Composable
fun ZxTopBar(
    title: String,
    onNav: () -> Unit,
    modifier: Modifier = Modifier,
    canBack: Boolean = true,
    navGlyph: String = if (canBack) "‹" else "☰",
    actions: @Composable () -> Unit = {},
) {
    val c = ZhaoxiTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(c.glass)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZxIconButton(onClick = onNav, contentDescription = if (canBack) "返回" else "菜单") {
            Text(navGlyph, fontSize = 19.sp, color = c.ink)
        }
        Text(
            title,
            Modifier.weight(1f),
            textAlign = TextAlign.Center,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = c.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row { actions() }
    }
}

/** CHAT⇄WORK 模式分段。 */
@Composable
fun ZxModeSwitch(workMode: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = ZhaoxiTheme.colors
    Row(
        modifier.clip(RoundedCornerShape(999.dp)).background(c.bg2).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(false to "聊天", true to "工作").forEach { (w, label) ->
            val on = w == workMode
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (on) c.ink else c.bg2)
                    .clickable { onChange(w) }
                    .padding(horizontal = 13.dp, vertical = 5.dp),
            ) { Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = if (on) c.onInk else c.ink2) }
        }
    }
}

data class ZxDrawerEntry(val id: String, val label: String, val badge: String? = null)

/** 抽屉：搜索框 + 分组列表 + 底部通用入口。 */
@Composable
fun ZxDrawer(
    query: String,
    onQuery: (String) -> Unit,
    groups: List<Pair<String, List<ZxDrawerEntry>>>,
    onOpen: (String) -> Unit,
    footer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ZhaoxiTheme.colors
    Column(modifier.background(c.side).padding(12.dp)) {
        ZxField(query, onQuery, Modifier.fillMaxWidth(), placeholder = "搜索人物、会话、任务、文件")
        Spacer(Modifier.height(8.dp))
        Column(Modifier.weight(1f)) {
            groups.forEach { (group, entries) ->
                ZxSection(group)
                entries.forEach { e ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onOpen(e.id) }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(e.label, Modifier.weight(1f), fontSize = 13.5.sp, color = c.ink)
                        e.badge?.let { ZxBadge(it, ZxBadgeTone.Accent) }
                    }
                }
            }
        }
        footer()
    }
}

// ---------------------------------------------------------------------------
// E8.12 §1.5 缺口组件
// ---------------------------------------------------------------------------

data class ZxOnboardingStep(val kicker: String, val title: String, val body: String)

/** shell.onboarding：3–4 步引导；reduced-motion 退化为静态分页（动效经 ZhaoxiMotion）。 */
@Composable
fun ZxOnboardingFlow(
    steps: List<ZxOnboardingStep>,
    current: Int,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ZhaoxiTheme.colors
    val s = steps[current.coerceIn(steps.indices)]
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(64.dp))
        Text(s.kicker, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.accent, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        Text(s.title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = c.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(s.body, fontSize = 13.sp, color = c.ink2, lineHeight = 21.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            steps.forEachIndexed { i, _ ->
                Box(
                    Modifier
                        .size(width = if (i == current) 20.dp else 6.dp, height = 6.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(if (i == current) c.accent else c.line),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val last = current == steps.lastIndex
            ZxButton(if (last) "去图集" else "跳过", onSkip, Modifier.weight(1f), ZxButtonVariant.Outline)
            ZxButton(if (last) "开始对话" else "下一步", if (last) onFinish else onNext, Modifier.weight(2f))
        }
    }
}

data class ZxPlanStep(val content: String, val ownerStatus: String, val confirmed: Boolean)

/** work.plan_review：方案步骤逐步确认 + 批准剩余。 */
@Composable
fun ZxPlanReviewCard(
    steps: List<ZxPlanStep>,
    currentIndex: Int,
    onConfirmStep: (Int) -> Unit,
    onEditStep: (Int) -> Unit,
    onApproveAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ZhaoxiTheme.colors
    Column(modifier.fillMaxWidth()) {
        steps.forEachIndexed { i, step ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).background(
                        when {
                            step.confirmed -> c.okBg
                            i == currentIndex -> c.accent
                            else -> c.bg2
                        },
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (step.confirmed) "✓" else "${i + 1}", fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (i == currentIndex && !step.confirmed) c.onInk else c.ink2)
                }
                Column(Modifier.weight(1f)) {
                    Text(step.content, fontSize = 13.sp,
                        color = if (step.confirmed) c.ink3 else c.ink)
                    Text(step.ownerStatus, fontSize = 11.sp, color = c.ink3)
                }
                if (i == currentIndex && !step.confirmed) {
                    ZxChip("确认", false, { onConfirmStep(i) })
                    ZxChip("编辑", false, { onEditStep(i) })
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        ZxButton("批准剩余步骤", onApproveAll, Modifier.fillMaxWidth())
        ZxFooterNote("批准 = work.plan_review 能力 · 单步写操作仍走审批")
    }
}

/** chat.gallery_portrait：画像展示 + 重生成 + 存图集（chat.gallery_save 入口）。 */
@Composable
fun ZxPortraitCard(
    personaName: String,
    traits: List<Pair<String, String>>,
    onRegenerate: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ZhaoxiTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(c.card)
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ZxAvatar(personaName, Modifier.size(72.dp))
        Spacer(Modifier.height(10.dp))
        Text("$personaName · 画像", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.ink)
        Spacer(Modifier.height(12.dp))
        traits.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Text(k, fontSize = 12.sp, color = c.ink3, modifier = Modifier.weight(.34f))
                Text(v, fontSize = 12.5.sp, color = c.ink, modifier = Modifier.weight(.66f))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZxChip("重新生成", false, onRegenerate)
            ZxChip("存为图集立绘", false, onSave)
        }
        ZxFooterNote("存图集 = chat.gallery_save · 重新生成消耗一次媒体额度")
    }
}

data class ZxStoryChapter(val id: String, val title: String, val summary: String, val status: String)

/** chat.gallery_story：图集故事章节卡。 */
@Composable
fun ZxStoryCard(
    chapter: ZxStoryChapter,
    onOpen: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ZhaoxiTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ZhaoxiTheme.shapes.card))
            .background(c.card)
            .clickable(onClick = onOpen)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(chapter.title, Modifier.weight(1f), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
            ZxBadge(chapter.status, ZxBadgeTone.Neutral)
        }
        Spacer(Modifier.height(6.dp))
        Text(chapter.summary, fontSize = 12.5.sp, color = c.ink2, lineHeight = 19.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZxChip("继续阅读", false, onOpen)
            ZxChip("续写一节", false, onContinue)
        }
    }
}

/** chat.persona_transfer：迁移/导出；危险项走 ZxDialog 二次确认。 */
@Composable
fun ZxTransferSheet(
    personaName: String,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onCopy: () -> Unit,
    onDestructiveMove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        ZxBanner("迁移会带走身份、事实、世界书与公开日记 · 私密日记不随迁移", ZxTone.Warn)
        Spacer(Modifier.height(10.dp))
        listOf(
            Triple("导出为人物卡", ".zip · 含立绘与设定 · 可分享给其他设备", onExport),
            Triple("导入人物", "人物卡 / 剪贴板 JSON · 上限受 Owner 约束", onImport),
            Triple("复制为副本", "原人物保留 · 副本从头开始相处", onCopy),
        ).forEach { (t, sub, run) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = run)
                    .padding(vertical = 10.dp),
            ) {
                Column { Text(t, fontSize = 13.5.sp, color = ZhaoxiTheme.colors.ink)
                    Text(sub, fontSize = 11.sp, color = ZhaoxiTheme.colors.ink3) }
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onDestructiveMove)
                .padding(vertical = 10.dp),
        ) {
            Column { Text("迁出并删除本地", fontSize = 13.5.sp, color = ZhaoxiTheme.colors.err)
                Text("危险 · 需二次确认 · 账本保留 30 天", fontSize = 11.sp, color = ZhaoxiTheme.colors.ink3) }
        }
    }
}
