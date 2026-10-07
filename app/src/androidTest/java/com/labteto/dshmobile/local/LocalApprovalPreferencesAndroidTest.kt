package com.labteto.dshmobile.local

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.local.interaction.LocalApprovalMode
import com.labteto.dshmobile.local.interaction.LocalApprovalPreferences
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalApprovalPreferencesAndroidTest {
    private lateinit var context: Context
    private lateinit var name: String

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "approval-test-${UUID.randomUUID()}"
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun freshInstallDefaultsToDefaultModeWithoutFullAuto() {
        val shared = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val preferences = LocalApprovalPreferences(shared)

        assertEquals(LocalApprovalMode.DEFAULT, preferences.currentMode())
        assertFalse(preferences.isSafeAutoApprovalEnabled())
    }

    @Test
    fun explicitModesSurviveNewPreferenceWrapper() {
        val shared = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        LocalApprovalPreferences(shared).setApprovalMode(LocalApprovalMode.AUTO)
        assertEquals(LocalApprovalMode.AUTO, LocalApprovalPreferences(shared).currentMode())

        LocalApprovalPreferences(shared).setApprovalMode(LocalApprovalMode.MANUAL)
        val restored = LocalApprovalPreferences(shared)
        assertEquals(LocalApprovalMode.MANUAL, restored.currentMode())
        assertFalse(restored.isSafeAutoApprovalEnabled())
    }

    @Test
    fun legacyBooleanMigratesToEquivalentMode() {
        val shared = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        shared.edit().putBoolean("safe_auto_approval", true).commit()

        val restored = LocalApprovalPreferences(shared)
        assertEquals(LocalApprovalMode.AUTO, restored.currentMode())
        assertTrue(restored.isSafeAutoApprovalEnabled())
    }

    @Test
    fun explicitModeWinsOverLegacySessionFlag() {
        val shared = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        LocalApprovalPreferences(shared).setApprovalMode(LocalApprovalMode.MANUAL)

        assertEquals(
            LocalApprovalMode.MANUAL,
            LocalApprovalPreferences(shared).currentMode(legacySessionValue = true),
        )
    }
}
