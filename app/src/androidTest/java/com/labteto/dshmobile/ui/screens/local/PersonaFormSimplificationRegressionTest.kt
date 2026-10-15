package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
                    onAutoFill = { Result.failure(IllegalStateException("not configured")) },
                    onDismiss = {},
                    creatingNew = true,
                )
            }
        }

        // 所有预置字段无需点击「添加」就能填写，顺序遵循自身 → 认知 → 表达 → 外部世界。
        listOf(
            "人物名字", "核心身份", "性格", "价值与取舍", "自我叙事",
            "主观认知", "内外反差", "偏好与习惯", "语言风格",
            "人生经历", "重要人物关系", "作品来源／所属系列", "自定义事实",
        ).forEach { compose.onNodeWithText(it).assertExists() }
        // 此前删去的六项不能因为默认展开所有字段而回来。
        listOf("命运选择", "情感烙印", "人生引力", "未竟之事", "能力与代价", "感知视角")
            .forEach { compose.onNodeWithText(it).assertDoesNotExist() }

        // 扩展入口只负责添加新的资料，不是显示预置字段的前置步骤。
        compose.onNodeWithText("＋ 添加扩展资料").performClick()
        compose.onNodeWithText("自定义设定名称").assertExists()
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
                    onAutoFill = { Result.failure(IllegalStateException("not configured")) },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("人生经历").assertExists()
        compose.onNodeWithText("在远方长大").assertExists()
        compose.onNodeWithText("作品来源／所属系列").assertExists()
        compose.onNodeWithText("性格").assertExists()
        compose.onNodeWithText("语言风格").assertExists()
        compose.onNodeWithText("情感烙印").assertDoesNotExist()
    }
}
