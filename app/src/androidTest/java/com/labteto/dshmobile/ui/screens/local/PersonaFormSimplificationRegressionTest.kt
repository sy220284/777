package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import com.labteto.dshmobile.local.chat.CharacterFact
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Rule
import org.junit.Test

class PersonaFormSimplificationRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun creatingPersonaShowsAllThirteenPresetFieldsInPersonalToExternalOrder() {
        compose.setContent {
            DshTheme {
                ChatPersonaDialog(
                    profile = PersonaProfile(name = "", coreIdentity = ""),
                    onSave = { Result.success(Unit) },
                    onAutoFill = { _, _ -> Result.failure(IllegalStateException("not configured")) },
                    onDismiss = {},
                    creatingNew = true,
                )
            }
        }

        // 所有预置字段无需点击「添加」就能填写，顺序遵循自身 → 认知 → 表达 → 外部世界。
        listOf(
            "人物名字", "这个人是谁", "性格特点", "看重什么、如何取舍", "如何看待自己",
            "眼中的世界", "表里反差", "日常喜好与习惯", "说话方式",
            "重要经历", "在意的人与关系", "出自哪部作品", "补充设定",
        ).forEach { compose.onNodeWithText(it).assertExists() }
        // 此前删去的六项不能因为默认展开所有字段而回来。
        listOf("命运选择", "情感烙印", "人生引力", "未竟之事", "能力与代价", "感知视角")
            .forEach { compose.onNodeWithText(it).assertDoesNotExist() }

        // 扩展入口只负责添加新的资料，不是显示预置字段的前置步骤。
        // 13 个字段使按钮位于可滚动弹层底部，必须先使按钮真正可见再触发点击。
        compose.onNodeWithTag("personaAddFieldPicker")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()
        compose.onNodeWithText("收起扩展资料").assertExists()
        compose.onNodeWithText("自定义设定名称").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("人物专属知识库").assertExists()
    }

    @Test
    fun existingPersonaKeepsPopulatedFactsAndShowsAllPresetFields() {
        val persona = PersonaProfile(
            name = "测试人物",
            coreIdentity = "肩负使命的旅人",
            franchise = "原创世界",
            facts = listOf(CharacterFact(
                id = "history",
                category = "biography",
                content = "在远方长大",
            )),
        )
        compose.setContent {
            DshTheme {
                ChatPersonaDialog(
                    profile = persona,
                    onSave = { Result.success(Unit) },
                    onAutoFill = { _, _ -> Result.failure(IllegalStateException("not configured")) },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("人生经历").assertExists()
        compose.onNodeWithText("在远方长大").assertExists()
        compose.onNodeWithText("出自哪部作品").assertExists()
        compose.onNodeWithText("性格特点").assertExists()
        compose.onNodeWithText("说话方式").assertExists()
        compose.onNodeWithText("情感烙印").assertDoesNotExist()
    }
}
