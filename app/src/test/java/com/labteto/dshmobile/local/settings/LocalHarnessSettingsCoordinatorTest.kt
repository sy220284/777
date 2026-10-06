package com.labteto.dshmobile.local.settings

import android.content.SharedPreferences
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.profile.UserProfile
import com.labteto.dshmobile.local.profile.UserProfileStore
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class LocalHarnessSettingsCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val preferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
    ) { _, method, _ -> error("Unexpected preference call: ${method.name}") } as SharedPreferences

    @Test fun rapidEditsPersistLatestValueWithoutWritingSupersededPendingValues() = runTest {
        val file = File(temporary.root, "profile/user.json")
        val store = UserProfileStore(file, Json)
        val baseline = UserProfile(customRules = "baseline")
        store.write(baseline)
        val runtime = LocalRuntimeStateStore().apply { initialize(LocalHarnessState(sessionId = "s")) }
        val settings = LocalHarnessSettingsCoordinator(preferences, store, runtime, backgroundScope)
        repeat(100) { settings.configurePersonalization("edit-$it", false, true) }
        assertEquals("", runtime.state.value.userRules)
        runCurrent()
        assertEquals("edit-99", runtime.state.value.userRules)
        assertEquals(UserProfile("edit-99", false, true), store.read())
        // The recovery generation remains the baseline, proving pending edits did not all hit disk.
        assertEquals(baseline, UserProfileStore(File(file.parentFile, "user.json.bak"), Json).read())
    }

    @Test fun writeFailureIsVisibleAndDoesNotKillTheNextSave() = runTest {
        val parent = File(temporary.root, "blocked").apply { writeText("file") }
        val store = UserProfileStore(File(parent, "user.json"), Json)
        val runtime = LocalRuntimeStateStore().apply { initialize(LocalHarnessState(sessionId = "s")) }
        val settings = LocalHarnessSettingsCoordinator(preferences, store, runtime, backgroundScope)
        settings.configurePersonalization("first", true, false)
        runCurrent()
        assertNotNull(runtime.state.value.error)
        assertEquals("", runtime.state.value.userRules)
        assertTrue(parent.delete())
        assertTrue(parent.mkdir())
        settings.configurePersonalization("second", false, true)
        runCurrent()
        assertEquals("second", runtime.state.value.userRules)
        assertEquals(UserProfile("second", false, true), store.read())
    }
}
