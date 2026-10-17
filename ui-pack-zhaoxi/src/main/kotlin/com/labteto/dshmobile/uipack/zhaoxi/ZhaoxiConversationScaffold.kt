package com.labteto.dshmobile.uipack.zhaoxi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxEmpty

/**
 * 会话外壳骨架（E8.11）：conversation.* 域渲染器共用的外壳，
 * 端口经 ZhaoxiConversationPorts 聚合注入（对齐 V6ConversationPorts 形状）。
 *
 * 合并版 R4（PR #705 merged）已删除会话级 ConversationUiPack 契约；
 * 本外壳不再是独立包入口，仅作为 ZhaoxiApplicationScenePack 内
 * conversation.* 场景的共享实现底座。
 */
@Composable
internal fun ZhaoxiConversationScaffold(
    ports: ZhaoxiConversationPorts,
    workMode: Boolean,
    onBack: () -> Unit,
) {
    // TODO(IMPL)：快照订阅（collectAsStateWithLifecycle）、阅读锚点恢复、
    // 历史分页（history.messagePage）、草稿绑定（drafts.updateDraft）、
    // 回执呈现（Accepted≠完成、OutcomeUnknown 显式标注）。
    // 行为基准参照 ui-pack-v6/V6ConversationScene.kt，视觉基准参照原型 index.html。
    Column(
        Modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        ZxEmpty(
            title = if (workMode) "工作会话" else "聊天会话",
            body = "朝夕会话外壳骨架已挂载（E8.11）：端口经 ZhaoxiConversationPorts 注入，" +
                "场景实现按 TODO(IMPL) 标记推进。",
        )
    }
}
