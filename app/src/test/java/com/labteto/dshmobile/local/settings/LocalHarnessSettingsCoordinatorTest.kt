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

    @Test
    fun agentAndModelRuntimeSettingsPublishInOneSharedPreferenceTransaction() = runTest {
        val saved = linkedMapOf<String, Any?>()
        var editCalls = 0
        var commitCalls = 0
        val recordedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "edit" -> {
                    editCalls++
                    val staged = linkedMapOf<String, Any?>()
                    Proxy.newProxyInstance(
                        SharedPreferences.Editor::class.java.classLoader,
                        arrayOf(SharedPreferences.Editor::class.java),
                    ) { editor, operation, args ->
                        when (operation.name) {
                            "putInt", "putString" -> {
                                staged[args!![0] as String] = args[1]
                                editor
                            }
                            "remove" -> {
                                staged[args!![0] as String] = null
                                editor
                            }
                            "commit" -> { saved.putAll(staged); commitCalls++; true }
                            "apply" -> error("运行参数必须使用可检查结果的 commit")
                            else -> error("Unexpected editor method: ${operation.name}")
                        }
                    } as SharedPreferences.Editor
                }
                else -> error("Unexpected preferences method: ${method.name}")
            }
        } as SharedPreferences
        val file = File(temporary.root, "profile/config.json")
        val store = UserProfileStore(file, Json)
        val runtime = LocalRuntimeStateStore().apply { initialize(LocalHarnessState(sessionId = "settings")) }
        val settings = LocalHarnessSettingsCoordinator(recordedPreferences, store, runtime, backgroundScope)
        settings.configureRuntimeLimits(mainMaxSteps = 40, subagentMaxSteps = 75, modelAttempts = 99)
        assertEquals(1, editCalls)
        assertEquals(1, commitCalls)
        assertEquals(40, saved["main_max_steps"])
        assertEquals(75, saved["subagent_max_steps"])
        assertEquals(5, saved["model_attempts"])
        assertEquals(40, runtime.state.value.mainMaxSteps)
        assertEquals(75, runtime.state.value.subagentMaxSteps)
        assertEquals(5, runtime.state.value.modelState.modelAttempts)
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

    @Test
    fun failedPreferenceCommitDoesNotPublishRuntimeLimits() = runTest {
        var commitCalls = 0
        val rejectingPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "edit" -> Proxy.newProxyInstance(
                    SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java),
                ) { editor, operation, _ ->
                    when (operation.name) {
                        "putInt", "putString", "remove" -> editor
                        "commit" -> { commitCalls++; false }
                        "apply" -> error("不得异步发布失败提交")
                        else -> error("Unexpected editor method: ${operation.name}")
                    }
                } as SharedPreferences.Editor
                else -> error("Unexpected preferences method: ${method.name}")
            }
        } as SharedPreferences
        val runtime = LocalRuntimeStateStore().apply { initialize(LocalHarnessState(sessionId = "settings")) }
        val original = runtime.state.value
        val settings = LocalHarnessSettingsCoordinator(
            rejectingPreferences,
            UserProfileStore(File(temporary.root, "failure.json"), Json),
            runtime,
            backgroundScope,
        )
        assertThrows(IllegalStateException::class.java) {
            settings.configureRuntimeLimits(40, 75, 5)
        }
        assertEquals(1, commitCalls)
        assertEquals(original.mainMaxSteps, runtime.state.value.mainMaxSteps)
        assertEquals(original.subagentMaxSteps, runtime.state.value.subagentMaxSteps)
        assertEquals(original.modelState.modelAttempts, runtime.state.value.modelState.modelAttempts)
    }
}
