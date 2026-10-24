package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.session.LocalSessionStorageStatus
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Rule
import org.junit.Test

/** Verify that the excluded Media Studio backup domain is disclosed in the actual settings UI. */
class LocalSessionBackupScopeAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun render(status: LocalSessionStorageStatus?) {
        compose.setContent {
            DshTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    LocalSessionStorageCard(
                        status = status,
                        busy = false,
                        onCompact = {},
                        onExport = {},
                        onRestore = {},
                        onLegacyRestore = {},
                        onCancelRestore = {},
                        onCleanup = {},
                    )
                }
            }
        }
    }

    @Test fun backupScopeWarningAppearsBeforeStorageStatsFinishLoading() {
        render(null)
        compose.onNodeWithText(context.getString(R.string.settings_local_session_storage_backup_scope))
            .performScrollTo()
            .assertExists()
    }

    @Test fun backupScopeWarningStaysVisibleWithActualStorageControls() {
        render(LocalSessionStorageStatus(
            totalBytes = 1024L,
            freeBytes = 1024L * 1024L * 1024L,
            budgetBytes = LocalSessionStorageStatus.DEFAULT_BUDGET_BYTES,
            sessionCount = 2,
            rawSegmentCount = 0,
            compressedSegmentCount = 0,
        ))
        compose.onNodeWithText(context.getString(R.string.settings_local_session_storage_backup_scope))
            .performScrollTo()
            .assertExists()
        compose.onNodeWithText(context.getString(R.string.settings_local_session_storage_export))
            .assertExists()
    }
}
