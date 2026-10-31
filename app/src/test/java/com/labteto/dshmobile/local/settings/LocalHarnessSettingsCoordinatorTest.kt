package com.labteto.dshmobile.local.settings

import android.content.SharedPreferences
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.profile.UserProfile
import com.labteto.dshmobile.local.profile.UserProfileStore
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
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

    @Test fun saveAcknowledgesOnlyAfterDurableWriteAndSkipsSupersededValues() = runTest {
        val file = File(temporary.root, "profile/ack.json")
        val store = UserProfileStore(file, Json)
        val runtime = LocalRuntimeStateStore().apply { initialize(LocalHarnessState(sessionId = "ack")) }
        val settings = LocalHarnessSettingsCoordinator(preferences, store, runtime, backgroundScope)
        val superseded = async(start = CoroutineStart.UNDISPATCHED) {
            settings.savePersonalization("discarded", true, true)
        }
        val current = async(start = CoroutineStart.UNDISPATCHED) {
            settings.savePersonalization("current", false, true)
        }
        assertFalse(current.isCompleted)
        assertEquals("", runtime.state.value.userRules)
        assertFalse(superseded.await())
        runCurrent()
        assertTrue(current.await())
        assertEquals(UserProfile("current", false, true), store.read())
        assertEquals("current", runtime.state.value.userRules)
    }

    @Test fun failedSaveCannotAcknowledgeDurabilityAndLaterRetrySucceeds() = runTest {
        val parent = File(temporary.root, "blocked-for-ack").apply { writeText("file") }
        val store = UserProfileStore(File(parent, "user.json"), Json)
        val runtime = LocalRuntimeStateStore().apply { initialize(LocalHarnessState(sessionId = "ack")) }
        val settings = LocalHarnessSettingsCoordinator(preferences, store, runtime, backgroundScope)
        val failed = async { runCatching { settings.savePersonalization("first", true, false) } }
        runCurrent()
        assertTrue(failed.await().isFailure)
        assertNotNull(runtime.state.value.error)
        assertEquals("", runtime.state.value.userRules)
        assertTrue(parent.delete())
        assertTrue(parent.mkdir())
        val retry = async { settings.savePersonalization("second", false, true) }
        runCurrent()
        assertTrue(retry.await())
        assertEquals(UserProfile("second", false, true), store.read())
    }

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

    @Test fun inFlightWriteWithRapidSuccessorsAcknowledgesOnlyFinalGeneration() = runBlocking {
        val file = File(temporary.root, "profile/concurrent.json")
        val store = UserProfileStore(file, Json)
        val runtime = LocalRuntimeStateStore().apply { initialize(LocalHarnessState(sessionId = "concurrent")) }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val settings = LocalHarnessSettingsCoordinator(
                preferences, store, runtime, worker,
                writeProfile = { profile ->
                    if (profile.customRules == "first") {
                        started.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "Timed out awaiting the second edit" }
                    }
                    store.write(profile)
                },
            )
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                settings.savePersonalization("first", true, true)
            }
            assertTrue("Writer did not enter the first disk operation", started.await(5, TimeUnit.SECONDS))
            val superseded = async(start = CoroutineStart.UNDISPATCHED) {
                settings.savePersonalization("middle", true, false)
            }
            val newest = async(start = CoroutineStart.UNDISPATCHED) {
                settings.savePersonalization("latest", false, false)
            }
            assertFalse(superseded.await())
            assertFalse(newest.isCompleted)
            assertEquals("", runtime.state.value.userRules)
            release.countDown()
            assertFalse(first.await())
            assertTrue(newest.await())
            assertEquals(UserProfile("latest", false, false), store.read())
            assertEquals("latest", runtime.state.value.userRules)
        } finally {
            release.countDown()
            worker.cancel()
        }
    }

    @Test fun staleDiskFailureCannotOverrideNewerSuccessfulGeneration() = runBlocking {
        val file = File(temporary.root, "profile/recovery.json")
        val store = UserProfileStore(file, Json)
        val runtime = LocalRuntimeStateStore().apply { initialize(LocalHarnessState(sessionId = "failure")) }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val settings = LocalHarnessSettingsCoordinator(
                preferences, store, runtime, worker,
                writeProfile = { profile ->
                    if (profile.customRules == "fails") {
                        started.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "Timed out awaiting recovery" }
                        error("simulated disk failure")
                    }
                    store.write(profile)
                },
            )
            val failed = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { settings.savePersonalization("fails", true, true) }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val recovered = async(start = CoroutineStart.UNDISPATCHED) {
                settings.savePersonalization("recovered", false, true)
            }
            release.countDown()
            assertTrue(failed.await().isFailure)
            assertTrue(recovered.await())
            assertEquals(UserProfile("recovered", false, true), store.read())
            assertEquals("recovered", runtime.state.value.userRules)
            assertNull("Stale failure must not leak into the latest save", runtime.state.value.error)
        } finally {
            release.countDown()
            worker.cancel()
        }
    }

    @Test
    fun agentAndModelRuntimeSettingsPublishInOneSharedPreferenceTransaction() = runTest {
        val saved = linkedMapOf<String, Any?>()
        var editCalls = 0
        var commitCalls = 0
        val recordedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "contains" -> saved.containsKey(args!![0] as String)
                "getInt" -> saved[args!![0] as String] as? Int ?: args[1] as Int
                "getString" -> saved[args!![0] as String] as? String ?: args[1] as String?
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
        assertTrue("workerProfileId must stage in the same editor", saved.containsKey("worker_profile_id"))
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
        val originalPreferences = linkedMapOf<String, Any?>(
            "main_max_steps" to 25,
            "subagent_max_steps" to 50,
            "model_attempts" to 2,
            "worker_profile_id" to "original-worker",
        )
        val saved = LinkedHashMap(originalPreferences)
        val rejectingPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "contains" -> saved.containsKey(args!![0] as String)
                "getInt" -> saved[args!![0] as String] as? Int ?: args[1] as Int
                "getString" -> saved[args!![0] as String] as? String ?: args[1] as String?
                "edit" -> {
                    val staged = linkedMapOf<String, Any?>()
                    Proxy.newProxyInstance(
                        SharedPreferences.Editor::class.java.classLoader,
                        arrayOf(SharedPreferences.Editor::class.java),
                    ) { editor, operation, editorArgs ->
                        when (operation.name) {
                            "putInt", "putString" -> {
                                staged[editorArgs!![0] as String] = editorArgs[1]
                                editor
                            }
                            "remove" -> {
                                staged[editorArgs!![0] as String] = null
                                editor
                            }
                            "commit" -> {
                                // Match Android's in-memory-before-disk behavior:
                                // failed first commit already changes visible preferences.
                                staged.forEach { (key, value) ->
                                    if (value == null) saved.remove(key) else saved[key] = value
                                }
                                commitCalls++
                                commitCalls != 1
                            }
                            "apply" -> error("不得异步发布失败提交")
                            else -> error("Unexpected editor method: ${operation.name}")
                        }
                    } as SharedPreferences.Editor
                }
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
        assertEquals(2, commitCalls)
        assertEquals(originalPreferences, saved)
        assertEquals(original.mainMaxSteps, runtime.state.value.mainMaxSteps)
        assertEquals(original.subagentMaxSteps, runtime.state.value.subagentMaxSteps)
        assertEquals(original.modelState.modelAttempts, runtime.state.value.modelState.modelAttempts)
    }
}
