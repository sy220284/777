package com.labteto.dshmobile.connection

import android.app.NotificationChannel
import android.app.NotificationManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.labteto.dshmobile.notify.DshNotifications
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises legacy preference and credential cleanup with actual Android DataStore / Keystore.
 * Uses isolated DataStore files so installed local profiles and sessions are never touched.
 */
@RunWith(AndroidJUnit4::class)
class RetiredRemoteUpgradeAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val oldStrings = listOf(
        "hosts_json", "hosts_json_backup", "desired_host_id",
        "last_sessions_json", "last_sessions_json_backup",
        "harness_sessions_json", "harness_sessions_json_backup",
        "relay_tokens_json", "relay_tokens_json_backup",
    )
    private val oldBooleans = listOf("background", "notify_turn", "notify_goal", "notify_action")

    @Test
    fun removesAllRetiredPreferencesAndPreservesCurrentLocalData() = withDataStore { dataStore ->
        val oldValue = "{\"old-remote-host\":\"secret\"}"
        dataStore.edit { prefs ->
            oldStrings.forEach { prefs[stringPreferencesKey(it)] = oldValue }
            oldBooleans.forEach { prefs[booleanPreferencesKey(it)] = false }
            prefs[stringPreferencesKey("theme")] = "dark"
            prefs[stringPreferencesKey("accent_theme")] = "zhusha"
            prefs[stringPreferencesKey("background_image_path")] = "/data/local-background.jpg"
            prefs[stringPreferencesKey("sidebar_avatar_source")] = "asset:persona"
            prefs[stringPreferencesKey("session_sort")] = "updated"
            prefs[stringPreferencesKey("local_harness_webhook_token")] = "local-token-unchanged"
            prefs[stringPreferencesKey("unrelated_model_state")] = "model-preserved"
            prefs[booleanPreferencesKey("notify_local_jobs")] = false
            prefs[booleanPreferencesKey("background_adaptive_contrast")] = false
            prefs[floatPreferencesKey("text_scale")] = 1.2f
            prefs[intPreferencesKey("text_weight_adjustment")] = 2
            prefs[floatPreferencesKey("wallpaper_surface_transparency")] = 0.7f
        }

        val store = HostsStore(dataStore, context)
        store.clearRetiredRemoteData()
        store.clearRetiredRemoteData() // App relaunch / retries must be idempotent.
        val current = dataStore.data.first()
        oldStrings.forEach { assertNull("Remote key still present: $it", current[stringPreferencesKey(it)]) }
        oldBooleans.forEach { assertNull("Remote switch still present: $it", current[booleanPreferencesKey(it)]) }
        assertEquals("local-token-unchanged", current[stringPreferencesKey("local_harness_webhook_token")])
        assertEquals("model-preserved", current[stringPreferencesKey("unrelated_model_state")])
        assertEquals("updated", store.sessionSort.first())

        val upgraded = HostsStore(dataStore, context).settingsOnce()
        assertEquals("dark", upgraded.themePreference)
        assertEquals("zhusha", upgraded.accentTheme)
        assertEquals("/data/local-background.jpg", upgraded.backgroundImagePath)
        assertEquals("asset:persona", upgraded.sidebarAvatarSource)
        assertFalse(upgraded.notifyLocalJobs)
        assertFalse(upgraded.backgroundAdaptiveContrast)
        assertEquals(1.2f, upgraded.textScale, 0.0001f)
        assertEquals(2, upgraded.textWeightAdjustment)
        assertEquals(0.7f, upgraded.wallpaperSurfaceTransparency, 0.0001f)
    }

    @Test
    fun laterSettingWritesNeverResurrectRetiredKeys() = withDataStore { dataStore ->
        val store = HostsStore(dataStore, context)
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("harness_sessions_json")] = "retired-cookie"
            prefs[booleanPreferencesKey("notify_goal")] = true
        }
        store.clearRetiredRemoteData()
        // setSetting also mirrors the theme to process-global SharedPreferences.
        // Preserve that mirror so instrumented tests cannot change real app appearance.
        val mirror = context.getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE)
        val previousTheme = mirror.getString("theme_preference", null)
        try {
            store.setSetting { it.copy(themePreference = "matte_black", notifyLocalJobs = false) }
            assertEquals("matte_black", store.settingsOnce().themePreference)
            assertFalse(store.settingsOnce().notifyLocalJobs)
            assertNull(dataStore.data.first()[stringPreferencesKey("harness_sessions_json")])
            assertNull(dataStore.data.first()[booleanPreferencesKey("notify_goal")])
        } finally {
            val editor = mirror.edit()
            if (previousTheme == null) editor.remove("theme_preference")
            else editor.putString("theme_preference", previousTheme)
            check(editor.commit())
        }
    }

    @Test
    fun removesOnlyRetiredRelayKeyFromAndroidKeystore() = withDataStore { dataStore ->
        val retainedAlias = "upgrade-test-keep-${UUID.randomUUID()}"
        val legacyAlias = "dsh_relay_tokens"
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            installAesKey(retainedAlias)
            if (!keystore.containsAlias(legacyAlias)) installAesKey(legacyAlias)
            assertTrue(keystore.containsAlias(legacyAlias))
            assertTrue(keystore.containsAlias(retainedAlias))

            HostsStore(dataStore, context).clearRetiredRemoteData()

            assertFalse(keystore.containsAlias(legacyAlias))
            assertTrue("Unrelated Keystore key was deleted", keystore.containsAlias(retainedAlias))
        } finally {
            keystore.deleteEntry(retainedAlias)
        }
    }

    @Test
    fun removesOldNotificationChannelsButKeepsLocalJobs() {
        val manager = context.getSystemService(NotificationManager::class.java)
        listOf("completions", "needs_action", "connection").forEach { id ->
            manager.createNotificationChannel(
                NotificationChannel(id, "retired-$id", NotificationManager.IMPORTANCE_LOW),
            )
        }
        DshNotifications(context).ensureChannels()
        listOf("completions", "needs_action", "connection").forEach { id ->
            assertNull("Retired channel still present: $id", manager.getNotificationChannel(id))
        }
        assertTrue(manager.getNotificationChannel(DshNotifications.CHANNEL_LOCAL_JOBS) != null)
    }

    private fun installAesKey(alias: String) {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        generator.generateKey()
    }

    private fun <T> withDataStore(block: suspend (androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) -> T): T =
        runBlocking {
            val file = File(context.cacheDir, "retired-remote-upgrade-${UUID.randomUUID()}.preferences_pb")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            try {
                block(store)
            } finally {
                scope.cancel()
                file.delete()
            }
        }
}
