package com.labteto.dshmobile.ui.audit

import android.Manifest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import com.labteto.dshmobile.local.chat.PersonaPresetCatalog
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.MainActivity
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.shenyan.SY_COMPOSER_FIELD_TAG
import org.junit.After
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** Traverse the production navigation and keep each actual page, without sending a model request. */
class MainPagesAuditTourTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val permission = object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(permission).around(compose)
    @After fun keepLastNativeScreenAndSemantics() {
        captureUiAudit(compose, "main-tour-last-screen")
        val directory = File(compose.activity.getExternalFilesDir(null), "ui-audit")
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        File(directory, "main-tour-last-semantics.txt").writeText(
            roots.fetchSemanticsNodes().indices.joinToString("\n") { roots[it].printToString() },
        )
    }

    private fun text(resource: Int) = compose.activity.getString(resource)
    private fun drawerVisible(): Boolean = listOf(R.string.local_usage_chat, R.string.local_usage_work)
        .all { resource -> compose.onAllNodesWithText(text(resource), useUnmergedTree = true).fetchSemanticsNodes()
            .any { it.boundsInRoot.left >= 0 && it.boundsInRoot.right > 0 } }
    private fun back() {
        Espresso.pressBack()
        compose.waitForIdle()
    }
    private fun openDrawer() {
        compose.onNodeWithContentDescription(text(R.string.app_open_navigation)).performClick()
        captureUiAudit(compose, "main-drawer-open-attempt")
        compose.waitUntil(10_000) { drawerVisible() }
    }
    private fun selectMode(resource: Int) {
        openDrawer()
        compose.onNodeWithText(text(resource)).performClick()
        compose.waitForIdle()
        if (drawerVisible()) back()
    }
    private fun page(resource: Int, name: String, inspect: (() -> Unit)? = null) {
        openDrawer()
        val entry = compose.onNodeWithText(text(resource))
        if (resource != R.string.settings_title) entry.performScrollTo()
        entry.performClick()
        compose.waitUntil(10_000) { !drawerVisible() }
        captureUiAudit(compose, name)
        inspect?.invoke()
        back()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(SY_COMPOSER_FIELD_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun allMainRoutesKeepTheirRealNavigationAndNativeAppearance() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag(SY_COMPOSER_FIELD_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText(text(R.string.shenyan_onboarding_skip)).fetchSemanticsNodes().isNotEmpty()) {
            captureUiAudit(compose, "main-first-launch")
            compose.onNodeWithText(text(R.string.shenyan_onboarding_skip)).performClick()
        }
        selectMode(R.string.local_usage_work)
        captureUiAudit(compose, "main-work")
        openDrawer(); captureUiAudit(compose, "main-sidebar-work"); back()
        page(R.string.chatlist_workspace_files, "main-workspace")
        page(R.string.tasks_title, "main-tasks-work") {
            compose.onNodeWithContentDescription(text(R.string.tasks_new)).performClick()
            compose.onNodeWithTag("menu-item:${text(R.string.tasks_create_manual)}").performClick()
            captureUiAudit(compose, "main-task-editor")
            compose.onAllNodes(hasSetTextAction())[0].performClick().performTextInput("整理本周的阅读笔记和后续研究问题")
            captureUiAudit(compose, "main-task-editor-keyboard")
            Espresso.pressBack()
            captureUiAudit(compose, "main-task-editor-keyboard-dismissed")
            // A Back press while editing can first dismiss the IME. Ensure the
            // editor's own Back action is exercised before leaving the task page.
            val taskRootAction = text(R.string.tasks_new)
            if (compose.onAllNodesWithContentDescription(taskRootAction)
                    .fetchSemanticsNodes().isEmpty()) {
                compose.onNodeWithContentDescription(text(R.string.common_back)).performClick()
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription(taskRootAction)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            captureUiAudit(compose, "main-task-editor-closed")
        }
        page(R.string.tools_title, "main-tools") {
            compose.onNodeWithText(text(R.string.skills_title)).performClick()
            captureUiAudit(compose, "main-skills")
            compose.onNodeWithText("连接器").performClick()
            captureUiAudit(compose, "main-connectors")
        }
        page(R.string.settings_title, "main-settings") {
            compose.onNodeWithText(text(R.string.settings_page_models)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.local_api_model_add)).performClick()
            captureUiAudit(compose, "main-model-connection-form")
            back()
            back()
        }
        selectMode(R.string.local_usage_chat)
        captureUiAudit(compose, "main-chat")
        openDrawer(); captureUiAudit(compose, "main-sidebar-chat"); back()
        page(R.string.persona_gallery_title, "main-personas") {
            compose.onNodeWithText(text(R.string.persona_gallery_add)).performClick()
            captureUiAudit(compose, "main-persona-presets")
            compose.onAllNodesWithText(text(R.string.persona_gallery_preset_add))[0].performClick()
            compose.waitUntil(30_000) {
                compose.onAllNodesWithText(text(R.string.persona_gallery_preset_taken)).fetchSemanticsNodes().isNotEmpty()
            }
            back()
            compose.onNodeWithText(PersonaPresetCatalog.presets.first().persona.name).performClick()
            captureUiAudit(compose, "main-persona-detail")
            compose.onNodeWithText(text(R.string.persona_gallery_view_profile)).performScrollTo().performClick()
            captureUiAudit(compose, "main-persona-profile")
            back()
            back()
        }
        page(R.string.chat_diary_title, "main-diary")
        page(R.string.tasks_chat_title, "main-tasks-chat")
    }
}
