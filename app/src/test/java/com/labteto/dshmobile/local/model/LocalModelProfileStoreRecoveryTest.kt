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

    private data class PreferencesFixture(
        val preferences: SharedPreferences,
        val values: MutableMap<String, Any?>,
    )

    private fun fakePreferences(): PreferencesFixture {
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
