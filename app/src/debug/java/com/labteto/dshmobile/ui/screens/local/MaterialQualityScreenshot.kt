package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.components.SyEntryIcons
import com.shenyan.designsystem.components.*
import com.shenyan.designsystem.foundation.SyOption
import com.shenyan.designsystem.foundation.SyPhase

/** Deterministic component QA, not a production destination or real account state. */
@Composable
internal fun MaterialQualityScreenshot() {
    var input by remember { mutableStateOf("与壁纸协调的输入框") }
    var selected by remember { mutableStateOf("one") }
    var checked by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SySectionHeader("材质与组件检查", subtitle = "静态测试内容 · 非账户或业务数据")
        SyMaterialFrame {
            Text("L3 内容卡：文字保持清晰")
            SyTextField(input, { input = it }, label = { Text("L1 输入") })
            SyTextField("", {}, enabled = false, label = { Text("禁用状态") })
            SyTextField("需要更正", {}, isError = true, supportingText = { Text("请检查输入") })
        }
        SySegmentedControl(listOf(SyOption("one", "当前"), SyOption("two", "其他")), selected,
            { selected = it }, stretch = true, modifier = Modifier.fillMaxWidth())
        SyMaterialFrame(tier = SyMaterialTier.L1_SECTION) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SyCheckboxControl(checked, { checked = it })
                SyRadioControl(checked, { checked = true })
                SySwitchControl(checked, { checked = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SyStatusTag("完成", SyPhase.DONE)
                SyStatusTag("等待", SyPhase.BLOCKED)
                SyStatusTag("失败", SyPhase.FAILED)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SyButton("主要操作", {}, modifier = Modifier.weight(1f))
            SyButton("次要操作", {}, style = SyButtonStyle.SECONDARY, modifier = Modifier.weight(1f))
        }
        SyButton("禁用操作", {}, enabled = false, modifier = Modifier.fillMaxWidth())
        SyQuickActionTile(SyEntryIcons.Tools, "功能入口", {})
    }
}
