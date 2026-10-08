package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.labteto.dshmobile.MainActivity
import com.labteto.dshmobile.ui.components.DS_COMPOSER_FIELD_TAG
import org.junit.Rule
import org.junit.Test

/** Launch the production composition; isolated composer tests cannot catch shell startup crashes. */
class LocalColdStartupRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun coldLaunchRendersComposerWithNoModeIntroduction() {
        compose.onNodeWithTag(DS_COMPOSER_FIELD_TAG).assertExists()
    }
}
