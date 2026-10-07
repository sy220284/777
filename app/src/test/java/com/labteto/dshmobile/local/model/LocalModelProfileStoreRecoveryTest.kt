package com.labteto.dshmobile.local.model

import android.content.SharedPreferences
import com.labteto.dshmobile.persistence.CorruptPersistedJsonException
import java.lang.reflect.Proxy
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalModelProfileStoreRecoveryTest {
    @Test
    fun firstWriteAlreadyHasRecoverableBackup() {
        val fixture = fakePreferences()
        val store = LocalModelProfileStore(
            preferences = fixture.preferences,
            json = Json { ignoreUnknownKeys = true },
        )
        val profile = LocalModelProfile("profile-a", "model-a", "https://a.example/v1")

        store.write(listOf(profile))
        fixture.values[LocalModelConfigContract.KEY_PROFILES_V3] = "{broken-primary"

        assertEquals(listOf(profile), store.read())
    }

    @Test
    fun corruptPrimaryRecoversPreviousValidProfiles() {
        val fixture = fakePreferences()
        val store = LocalModelProfileStore(
            preferences = fixture.preferences,
            json = Json { ignoreUnknownKeys = true },
        )
        val first = LocalModelProfile("profile-a", "model-a", "https://a.example/v1")
        val second = LocalModelProfile("profile-b", "model-b", "https://b.example/v1")

        store.write(listOf(first))
        store.write(listOf(second))
        fixture.values[LocalModelConfigContract.KEY_PROFILES_V3] = "{broken-primary"

        assertEquals(listOf(first), store.read())
    }

    @Test
    fun committedRemovalDoesNotRestoreDeletedProfile() {
        val fixture = fakePreferences()
        val store = LocalModelProfileStore(
            preferences = fixture.preferences,
            json = Json { ignoreUnknownKeys = true },
        )
        val first = LocalModelProfile("profile-a", "model-a", "https://a.example/v1")
        val second = LocalModelProfile("profile-b", "model-b", "https://b.example/v1")

        store.write(listOf(first, second))
        store.writeAfterRemoval(listOf(second))
        fixture.values[LocalModelConfigContract.KEY_PROFILES_V3] = "{broken-primary"

        assertEquals(listOf(second), store.read())
    }

    @Test
    fun corruptPrimaryWithoutBackupFailsInsteadOfBecomingEmpty() {
        val fixture = fakePreferences()
        fixture.values[LocalModelConfigContract.KEY_PROFILES_V3] = "{broken-primary"
        val store = LocalModelProfileStore(
            preferences = fixture.preferences,
            json = Json { ignoreUnknownKeys = true },
        )

        assertThrows(CorruptPersistedJsonException::class.java) {
            store.read()
        }
    }

    @Test
    fun failedConfigurationCommitRestoresMemoryAndDoesNotAdvanceTransaction() {
        var failNext = false
        val fixture = fakePreferences { if (failNext) { failNext = false; false } else true }
        val store = LocalModelProfileStore(fixture.preferences, Json)
        val old = LocalModelProfile("old", "old-model", "https://old.example/v1")
        val next = LocalModelProfile("next", "next-model", "https://next.example/v1")
        store.commitConfiguration(listOf(old), old, "confirmed")
        val before = store.configurationSnapshot()
        failNext = true
        assertThrows(java.io.IOException::class.java) { store.commitConfiguration(listOf(next), next, "failed") }
        assertEquals(before, store.configurationSnapshot())
        assertEquals(listOf(old), store.read())
        assertEquals("confirmed", store.transactionId())
        assertEquals(old, store.active(old.model, old.baseUrl))
    }

    @Test
    fun configurationAndActiveSelectionAreCommittedTogetherAndRemovalBackupCannotResurrect() {
        var commits = 0
        val fixture = fakePreferences { commits++; true }
        val store = LocalModelProfileStore(fixture.preferences, Json)
        val first = LocalModelProfile("first", "first", "https://first.example/v1")
        val second = LocalModelProfile("second", "second", "https://second.example/v1")
        store.commitConfiguration(listOf(first, second), first, "first-transaction")
        commits = 0
        store.commitConfiguration(listOf(second), second, "remove-transaction")
        assertEquals(1, commits)
        fixture.values[LocalModelConfigContract.KEY_PROFILES_V3] = "{broken"
        assertEquals(listOf(second), store.read())
        assertEquals(second, store.active(second.model, second.baseUrl))
    }

    private data class PreferencesFixture(
        val preferences: SharedPreferences,
        val values: MutableMap<String, Any?>,
    )

    private fun fakePreferences(commitResult: () -> Boolean = { true }): PreferencesFixture {
        val values = linkedMapOf<String, Any?>()
        val pending = linkedMapOf<String, Any?>()
        var clearPending = false
        fun flush() {
            if (clearPending) values.clear()
            pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            pending.clear()
            clearPending = false
        }
        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "putInt", "putString", "putBoolean", "putLong", "putFloat", "putStringSet" -> {
                    pending[args!![0] as String] = args[1]
                    proxy
                }
                "remove" -> {
                    pending[args!![0] as String] = null
                    proxy
                }
                "clear" -> {
                    clearPending = true
                    proxy
                }
                "apply" -> { flush(); null }
                "commit" -> { flush(); commitResult() }
                "toString" -> "FakeSharedPreferences.Editor"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> error("Unexpected editor call: ${method.name}")
            }
        } as SharedPreferences.Editor

        val preferences = Proxy.newProxyInstance(
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

        return PreferencesFixture(preferences, values)
    }
}
