package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DsHierarchyPageRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun returningRestoresTheParentsDraftAndScrollPosition() {
        var page by mutableStateOf("parent")
        var parentScroll: ScrollState? = null
        compose.setContent {
            DshTheme {
                Column(Modifier.fillMaxSize()) {
                    DsButton(if (page == "parent") "进入" else "返回", onClick = {
                        page = if (page == "parent") "child" else "parent"
                    })
                    DsHierarchyPage(page, { it }, { if (it == "parent") 0 else 1 }, Modifier.weight(1f)) { shown ->
                        var draft by rememberSaveable { mutableStateOf("") }
                        val scroll = rememberScrollState()
                        if (shown == "parent") parentScroll = scroll
                        Column(Modifier.fillMaxSize()) {
                            BasicTextField(draft, { draft = it }, Modifier.testTag("draft-$shown"))
                            Column(Modifier.weight(1f).verticalScroll(scroll)) {
                                repeat(60) { Text("$shown-$it") }
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("draft-parent").performTextReplacement("未完成的设置")
        compose.onNodeWithText("parent-45").performScrollTo()
        var previousScroll = 0
        compose.runOnIdle {
            previousScroll = requireNotNull(parentScroll).value
            assertTrue(previousScroll > 0)
        }
        compose.onNodeWithText("进入").performClick()
        compose.onNodeWithTag("draft-child").assertTextEquals("")
        compose.onNodeWithTag("draft-child").performTextReplacement("子页内容")
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithTag("draft-parent").assertTextEquals("未完成的设置")
        compose.runOnIdle { assertEquals(previousScroll, requireNotNull(parentScroll).value) }
    }
}
