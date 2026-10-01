package com.labteto.dshmobile.connection

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.automation.WebhookTokenStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionPersistenceAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun concurrentHostUpdatesDoNotLoseEntries() = withStore { dataStore, _ ->
        val store = HostsStore(dataStore, RelayCredentialStore(dataStore), context)
        coroutineScope {
            repeat(24) { index ->
                launch(Dispatchers.Default) {
                    store.upsertHost(
                        HostConfig(
                            id = "host-$index",
                            name = "Host $index",
                            host = "relay-$index.example",
                            port = 443,
                            useTls = true,
                            relayDeviceId = "device-$index",
                        ),
                    )
                }
            }
        }
        assertEquals(24, store.hosts.first().size)
    }

    @Test
    fun concurrentSettingTransformsMergeInsteadOfOverwritingEachOther() = withStore { dataStore, _ ->
        val store = HostsStore(dataStore, RelayCredentialStore(dataStore), context)
        coroutineScope {
            launch(Dispatchers.Default) {
                store.setSetting { it.copy(themePreference = "dark") }
            }
            launch(Dispatchers.Default) {
                store.setSetting { it.copy(notifyTurnComplete = false) }
            }
        }
        val settings = store.settingsOnce()
        assertEquals("dark", settings.themePreference)
        assertEquals(false, settings.notifyTurnComplete)
    }

    @Test
    fun retiredLocalePreferenceIsRemovedWithoutChangingOtherSettings() = withStore { dataStore, _ ->
        val legacyLocale = stringPreferencesKey("locale")
        val theme = stringPreferencesKey("theme")
        dataStore.edit { prefs ->
            prefs[legacyLocale] = "en"
            prefs[theme] = "dark"
        }

        val store = HostsStore(dataStore, RelayCredentialStore(dataStore), context)
        store.clearRetiredLocalePreference()

        assertNull(dataStore.data.first()[legacyLocale])
        assertEquals("dark", store.settingsOnce().themePreference)
    }

    @Test
    fun accentThemePersistsAcrossStoreReads() = withStore { dataStore, _ ->
        val store = HostsStore(dataStore, RelayCredentialStore(dataStore), context)
        assertEquals("celadon", store.settingsOnce().accentTheme)

        store.setSetting { it.copy(accentTheme = "zhusha") }

        assertEquals("zhusha", store.settingsOnce().accentTheme)
    }

    @Test
    fun adaptiveBackgroundContrastDefaultsOnAndPersists() = withStore { dataStore, _ ->
        val store = HostsStore(dataStore, RelayCredentialStore(dataStore), context)
        assertEquals(true, store.settingsOnce().backgroundAdaptiveContrast)

        store.setSetting { it.copy(backgroundAdaptiveContrast = false) }

        assertEquals(false, store.settingsOnce().backgroundAdaptiveContrast)
    }

    @Test
    fun appearanceReadingPreferencesDefaultClampAndPersist() = withStore { dataStore, _ ->
        val store = HostsStore(dataStore, RelayCredentialStore(dataStore), context)
        val defaults = store.settingsOnce()
        assertEquals(1.0f, defaults.textScale)
        assertEquals(0, defaults.textWeightAdjustment)
        assertEquals(0.5f, defaults.wallpaperSurfaceTransparency)

        store.setSetting {
            it.copy(
                textScale = 1.2f,
                textWeightAdjustment = 2,
                wallpaperSurfaceTransparency = 0.8f,
            )
        }

        val saved = store.settingsOnce()
        assertEquals(1.2f, saved.textScale)
        assertEquals(2, saved.textWeightAdjustment)
        assertEquals(0.8f, saved.wallpaperSurfaceTransparency)
    }

    @Test
    fun repairingWithoutANewPinClearsTheOldFingerprint() = withStore { dataStore, _ ->
        val store = HostsStore(dataStore, RelayCredentialStore(dataStore), context)
        store.rememberHost(
            name = "relay",
            host = "relay.example",
            port = 443,
            isLoopback = false,
            relay = RelayIdentity(
                deviceId = "old-device",
                useTls = true,
                fingerprint = "old-pin",
                tokenExpiresAt = 10L,
            ),
        )
        val repaired = store.rememberHost(
            name = "relay",
            host = "relay.example",
            port = 443,
            isLoopback = false,
            relay = RelayIdentity(
                deviceId = "new-device",
                useTls = true,
                fingerprint = null,
                tokenExpiresAt = 20L,
            ),
        )
        assertNull(repaired.relayFingerprint)
        assertEquals("new-device", repaired.relayDeviceId)
    }

    @Test
    fun concurrentWebhookGetOrCreateReturnsOneStableToken() = withStore { dataStore, _ ->
        val store = WebhookTokenStore(dataStore)
        val tokens = java.util.Collections.synchronizedList(mutableListOf<String>())
        coroutineScope {
            repeat(12) {
                launch(Dispatchers.Default) { tokens += store.getOrCreate() }
            }
        }
        assertEquals(1, tokens.toSet().size)
        assertEquals(tokens.first(), store.get())
    }

    @Test
    fun concurrentCredentialWritesDoNotDropOtherHosts() = withStore { dataStore, _ ->
        val store = RelayCredentialStore(dataStore)
        coroutineScope {
            repeat(12) { index ->
                launch(Dispatchers.Default) { store.put("host-$index", "token-$index") }
            }
        }
        repeat(12) { index ->
            assertEquals("token-$index", store.token("host-$index"))
        }
    }

    private fun <T> withStore(
        block: suspend (
            androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
            File,
        ) -> T,
    ): T = runBlocking {
        val file = File(context.cacheDir, "connection-store-${UUID.randomUUID()}.preferences_pb")
        file.delete()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file },
        )
        try {
            block(dataStore, file)
        } finally {
            scope.cancel()
            file.delete()
        }
    }
}
