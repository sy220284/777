package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Rule
import org.junit.Test

class LocalModeIntroBannerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun absentIntroAndModeSwitchesRenderWithoutStaleOrMissingContent() {
        val intro = mutableStateOf<LocalModeIntro?>(null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val chat = context.getString(R.string.local_mode_intro_chat)
        val work = context.getString(R.string.local_mode_intro_work)
        compose.setContent { DshTheme { LocalModeIntroBanner(intro.value) } }
        compose.onNodeWithText(chat).assertDoesNotExist()
        compose.onNodeWithText(work).assertDoesNotExist()
        compose.runOnIdle { intro.value = LocalModeIntro(LocalUsageMode.CHAT) }
        compose.onNodeWithText(chat).assertExists()
        compose.onNodeWithText(work).assertDoesNotExist()
        compose.runOnIdle { intro.value = LocalModeIntro(LocalUsageMode.WORK) }
        compose.onNodeWithText(work).assertExists()
        compose.onNodeWithText(chat).assertDoesNotExist()
        compose.runOnIdle { intro.value = null }
        compose.onNodeWithText(chat).assertDoesNotExist()
        compose.onNodeWithText(work).assertDoesNotExist()
    }
}
