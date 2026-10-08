package com.labteto.dshmobile.ui.screens.local

import android.Manifest
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.MainActivity
import com.labteto.dshmobile.ui.components.DS_COMPOSER_FIELD_TAG
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** Check a real cold-started shell, including its initial asynchronous state. */
class LocalColdStartupRegressionTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val notificationPermission = object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            // MainActivity requests this permission on first launch. Resolve the system
            // dialog before launching so it cannot pause the lifecycle being tested.
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(notificationPermission).around(compose)

    @Test
    fun coldLaunchRendersComposerWithNoModeIntroduction() {
        // The full application initializes Hilt, persisted settings and the session before
        // the conversation UI becomes visible. A synchronous query races that initialization.
        compose.activityRule.scenario.onActivity { activity ->
            check(!activity.isFinishing && !activity.isDestroyed) {
                "MainActivity finished before its conversation UI became ready"
            }
        }
        try {
            compose.waitUntil(timeoutMillis = 30_000L) {
                runCatching {
                    compose.onAllNodesWithTag(DS_COMPOSER_FIELD_TAG, useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }.getOrDefault(false)
            }
        } catch (failure: Throwable) {
            // A timeout is a real regression: include the crash buffer for diagnosis rather
            // than dropping this test or treating a process with no Compose tree as success.
            val crashBuffer = runCatching {
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                ParcelFileDescriptor.AutoCloseInputStream(
                    automation.executeShellCommand("logcat -d -b crash -t 60"),
                ).bufferedReader().use { it.readText().takeLast(8_000) }
            }.getOrElse { "unavailable: ${it.message}" }
            val hierarchy = runCatching {
                compose.onRoot(useUnmergedTree = true).printToString().take(8_000)
            }.getOrElse { "unavailable: ${it.message}" }
            throw AssertionError(
                "MainActivity did not render its composer within 30 seconds. " +
                    "Crash buffer:\n$crashBuffer\nCompose hierarchy:\n$hierarchy",
                failure,
            )
        }
        compose.onNodeWithTag(DS_COMPOSER_FIELD_TAG, useUnmergedTree = true).assertExists()
    }
}
