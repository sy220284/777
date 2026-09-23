package com.labteto.dshmobile.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.After
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
        context = ApplicationProvider.getApplicationContext()
        name = "approval-test-${UUID.randomUUID()}"
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun safeApprovalSurvivesNewPreferenceWrapper() {
        val shared = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        LocalApprovalPreferences(shared).setSafeAutoApprovalEnabled(true)

        assertTrue(LocalApprovalPreferences(shared).isSafeAutoApprovalEnabled())
    }

    @Test
    fun explicitDisableWinsOverLegacySessionFlag() {
        val shared = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val first = LocalApprovalPreferences(shared)
        first.setSafeAutoApprovalEnabled(true)
        first.setSafeAutoApprovalEnabled(false)

        assertFalse(LocalApprovalPreferences(shared).isSafeAutoApprovalEnabled(legacySessionValue = true))
    }

    @Test
    fun legacySessionFlagMigratesOnlyWhenGlobalChoiceIsAbsent() {
        val shared = context.getSharedPreferences(name, Context.MODE_PRIVATE)

        assertTrue(LocalApprovalPreferences(shared).isSafeAutoApprovalEnabled(legacySessionValue = true))
        assertTrue(LocalApprovalPreferences(shared).isSafeAutoApprovalEnabled(legacySessionValue = false))
    }
}
