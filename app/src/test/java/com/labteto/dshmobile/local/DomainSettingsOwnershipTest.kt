package com.labteto.dshmobile.local

import android.content.SharedPreferences
import com.labteto.dshmobile.local.agent.LocalAgentRuntimeSettings
import com.labteto.dshmobile.local.model.LocalModelConfigContract
import com.labteto.dshmobile.local.model.LocalModelExecutionSettings
import com.labteto.dshmobile.local.model.LocalModelProfile
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainSettingsOwnershipTest {
    @Test
    fun agentRuntimeSettingsOwnNormalizationAndPersistence() {
        val preferences = fakePreferences()

        val written = LocalAgentRuntimeSettings.write(
            preferences = preferences,
            mainMaxSteps = Int.MAX_VALUE,
            subagentMaxSteps = Int.MIN_VALUE,
        )
        val restored = LocalAgentRuntimeSettings.read(preferences)

        assertEquals(512, written.mainMaxSteps)
        assertEquals(1, written.subagentMaxSteps)
        assertEquals(written, restored)
    }

    @Test
    fun modelExecutionSettingsOwnRetryAndWorkerProfilePersistence() {
        val preferences = fakePreferences()
        val profiles = listOf(
            LocalModelProfile(
                id = "worker-a",
                model = "worker-model",
                baseUrl = "https://example.test",
            ),
        )

        val written = LocalModelExecutionSettings.write(
            preferences = preferences,
            modelAttempts = 99,
            workerProfileId = " worker-a ",
            availableProfiles = profiles,
        )
        val restored = LocalModelExecutionSettings.read(preferences, profiles)

        assertEquals(LocalModelConfigContract.MODEL_ATTEMPTS_MAX, written.modelAttempts)
        assertEquals("worker-a", written.workerProfileId)
        assertEquals(written, restored)
    }

    @Test
    fun modelExecutionSettingsRejectMissingWorkerProfile() {
        val preferences = fakePreferences()

        val failure = runCatching {
            LocalModelExecutionSettings.write(
                preferences = preferences,
                modelAttempts = 3,
                workerProfileId = "missing",
                availableProfiles = emptyList(),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    private fun fakePreferences(): SharedPreferences {
        val values = linkedMapOf<String, Any?>()
        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "putInt", "putString", "putBoolean", "putLong", "putFloat", "putStringSet" -> {
                    values[args!![0] as String] = args[1]
                    proxy
                }
                "remove" -> {
                    values.remove(args!![0] as String)
                    proxy
                }
                "clear" -> {
                    values.clear()
                    proxy
                }
                "apply" -> null
                "commit" -> true
                "toString" -> "FakeSharedPreferences.Editor"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> error("Unexpected editor call: ${method.name}")
            }
        } as SharedPreferences.Editor

        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getInt" -> values[args!![0] as String] as? Int ?: args[1] as Int
                "getString" -> values[args!![0] as String] as? String ?: args[1] as String?
                "getBoolean" -> values[args!![0] as String] as? Boolean ?: args[1] as Boolean
                "getLong" -> values[args!![0] as String] as? Long ?: args[1] as Long
                "getFloat" -> values[args!![0] as String] as? Float ?: args[1] as Float
                "getStringSet" -> @Suppress("UNCHECKED_CAST")
                    ((values[args!![0] as String] as? Set<String>) ?: args[1] as? Set<String>)
                "contains" -> values.containsKey(args!![0] as String)
                "getAll" -> values.toMap()
                "edit" -> editor
                "registerOnSharedPreferenceChangeListener",
                "unregisterOnSharedPreferenceChangeListener" -> null
                "toString" -> "FakeSharedPreferences"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> error("Unexpected preference call: ${method.name}")
            }
        } as SharedPreferences
    }
}
