package com.labteto.dshmobile.connection

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.labteto.dshmobile.DshApplication
import com.labteto.dshmobile.core.wire.WireJson
import com.labteto.dshmobile.core.wire.dto.HostDescription
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Persists remembered hosts and app settings. */
@Singleton
class HostsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val credentials: RelayCredentialStore,
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val HOSTS = stringPreferencesKey("hosts_json")
        val BACKGROUND = booleanPreferencesKey("background")
        val BACKGROUND_IMAGE = stringPreferencesKey("background_image_path")
        val BACKGROUND_ADAPTIVE_CONTRAST = booleanPreferencesKey("background_adaptive_contrast")
        val NOTIFY_TURN = booleanPreferencesKey("notify_turn")
        val NOTIFY_GOAL = booleanPreferencesKey("notify_goal")
        val NOTIFY_ACTION = booleanPreferencesKey("notify_action")
        val THEME = stringPreferencesKey("theme")
        val LOCALE = stringPreferencesKey("locale")
        val LAST_SESSIONS = stringPreferencesKey("last_sessions_json")
        val SESSION_SORT = stringPreferencesKey("session_sort")
        val DESIRED_HOST_ID = stringPreferencesKey("desired_host_id")
    }

    private val hostsSerializer = ListSerializer(HostConfig.serializer())
    private val lastSessionsSerializer = MapSerializer(String.serializer(), String.serializer())

    val hosts: Flow<List<HostConfig>> = dataStore.data.map(::decodeHosts)

    val settings: Flow<AppSettings> = dataStore.data.map(::decodeSettings)

    suspend fun settingsOnce(): AppSettings = settings.first()

    suspend fun setDesiredHost(id: String?) {
        dataStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.DESIRED_HOST_ID) else prefs[Keys.DESIRED_HOST_ID] = id
        }
    }

    suspend fun desiredHostOnce(): HostConfig? {
        val snapshot = dataStore.data.first()
        val id = snapshot[Keys.DESIRED_HOST_ID] ?: return null
        val host = decodeHosts(snapshot).firstOrNull { it.id == id }
        if (host == null) {
            // Clear only the stale value we actually observed. A concurrent connect may already
            // have selected another host, and a blind setDesiredHost(null) would erase that choice.
            dataStore.edit { prefs ->
                if (prefs[Keys.DESIRED_HOST_ID] == id) prefs.remove(Keys.DESIRED_HOST_ID)
            }
        }
        return host
    }

    suspend fun upsertHost(config: HostConfig) {
        dataStore.edit { prefs ->
            val current = decodeHosts(prefs).toMutableList()
            current.removeAll { it.host == config.host && it.port == config.port }
            current.add(0, config)
            prefs[Keys.HOSTS] = WireJson.encodeToString(hostsSerializer, current)
        }
    }

    suspend fun touchHost(host: String, port: Int) {
        val now = System.currentTimeMillis()
        dataStore.edit { prefs ->
            val current = decodeHosts(prefs)
            prefs[Keys.HOSTS] = WireJson.encodeToString(
                hostsSerializer,
                current.map {
                    if (it.host == host && it.port == port) it.copy(lastConnectedAt = now) else it
                },
            )
        }
    }

    /**
     * Remember (or refresh) one endpoint.
     *
     * The id is stable across reconnects: `host:port` is the identity, so a returning harness keeps
     * the id it already had. Minting a fresh UUID every time made the id useless as a list key and
     * as a handle for anything stored per host. Cached describe fields survive a call that does not
     * supply them.
     */
    suspend fun rememberHost(
        name: String,
        host: String,
        port: Int,
        isLoopback: Boolean,
        useTls: Boolean = false,
        description: HostDescription? = null,
        relay: RelayIdentity? = null,
    ): HostConfig {
        var remembered: HostConfig? = null
        dataStore.edit { prefs ->
            val current = decodeHosts(prefs).toMutableList()
            val existing = current.firstOrNull { it.host == host && it.port == port }
            val config = HostConfig(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = name,
                host = host,
                port = port,
                isLoopback = isLoopback,
                lastConnectedAt = System.currentTimeMillis(),
                lastHome = description?.home ?: existing?.lastHome,
                // A fresh pairing replaces the complete relay identity. In particular, a null
                // fingerprint is meaningful: it says platform trust is now used, so an old pin
                // must not survive the re-pair.
                useTls = relay?.useTls ?: useTls,
                relayFingerprint = if (relay != null) relay.fingerprint else existing?.relayFingerprint,
                relayDeviceId = if (relay != null) relay.deviceId else existing?.relayDeviceId,
                relayTokenExpiresAt = if (relay != null) relay.tokenExpiresAt else existing?.relayTokenExpiresAt ?: 0L,
            )
            current.removeAll { it.host == host && it.port == port }
            current.add(0, config)
            prefs[Keys.HOSTS] = WireJson.encodeToString(hostsSerializer, current)
            remembered = config
        }
        return checkNotNull(remembered)
    }

    /** Fold a fresh host description into the remembered entry without touching its recency. */
    suspend fun cacheDescription(host: String, port: Int, description: HostDescription) {
        dataStore.edit { prefs ->
            val current = decodeHosts(prefs)
            if (current.none { it.host == host && it.port == port }) return@edit
            prefs[Keys.HOSTS] = WireJson.encodeToString(
                hostsSerializer,
                current.map {
                    if (it.host == host && it.port == port) it.copy(lastHome = description.home) else it
                },
            )
        }
    }

    /**
     * Forget an endpoint, and the credential that went with it.
     *
     * The token is dropped in the same act rather than left to expire. It would otherwise outlive
     * everything that could ever present it, and a stored secret nothing can use is only a liability
     * — the relay's own device entry is revoked from the relay, not from here.
     */
    suspend fun removeHost(id: String) {
        dataStore.edit { prefs ->
            prefs[Keys.HOSTS] = WireJson.encodeToString(
                hostsSerializer,
                decodeHosts(prefs).filterNot { it.id == id },
            )
            if (prefs[Keys.DESIRED_HOST_ID] == id) prefs.remove(Keys.DESIRED_HOST_ID)
        }
        credentials.remove(id)
    }

    /**
     * The session last opened on [hostKey] (`"host:port"`), or null when this harness has not been
     * used before. Keyed per host because session ids are host-scoped — one global key would try to
     * reopen a stale id from a different harness after every host switch.
     */
    suspend fun lastSessionId(hostKey: String): String? = lastSessions()[hostKey]

    /** Remember [sessionId] as the landing session for [hostKey], keeping the newest 8 hosts. */
    suspend fun setLastSessionId(hostKey: String, sessionId: String) {
        dataStore.edit { prefs ->
            val next = LinkedHashMap<String, String>()
            next[hostKey] = sessionId
            decodeLastSessions(prefs).forEach { (key, value) -> if (key != hostKey) next[key] = value }
            val trimmed = next.entries.take(MAX_REMEMBERED_HOSTS).associate { it.key to it.value }
            prefs[Keys.LAST_SESSIONS] = WireJson.encodeToString(lastSessionsSerializer, trimmed)
        }
    }

    /** Forget every remembered endpoint and all host-scoped landing metadata atomically. */
    suspend fun clearHosts() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.HOSTS)
            prefs.remove(Keys.DESIRED_HOST_ID)
            prefs.remove(Keys.LAST_SESSIONS)
        }
    }

    /** Forget every remembered landing session (the Settings "clear data" action). */
    suspend fun clearLastSessions() {
        dataStore.edit { it.remove(Keys.LAST_SESSIONS) }
    }

    private suspend fun lastSessions(): Map<String, String> =
        decodeLastSessions(dataStore.data.first())

    private fun decodeSettings(prefs: Preferences): AppSettings =
        AppSettings(
            keepConnectedInBackground = prefs[Keys.BACKGROUND] ?: false,
            notifyTurnComplete = prefs[Keys.NOTIFY_TURN] ?: true,
            notifyGoal = prefs[Keys.NOTIFY_GOAL] ?: true,
            notifyNeedsAction = prefs[Keys.NOTIFY_ACTION] ?: true,
            themePreference = prefs[Keys.THEME] ?: "system",
            backgroundImagePath = prefs[Keys.BACKGROUND_IMAGE],
            backgroundAdaptiveContrast = prefs[Keys.BACKGROUND_ADAPTIVE_CONTRAST] ?: true,
            localeOverride = when (val tag = prefs[Keys.LOCALE]) {
                "en" -> "en"
                "zh", "zh-CN", "zh_CN" -> "zh-CN"
                else -> null
            },
        )

    private fun decodeHosts(prefs: Preferences): List<HostConfig> {
        val raw = prefs[Keys.HOSTS] ?: return emptyList()
        return runCatching {
            WireJson.decodeFromString(hostsSerializer, raw).sortedByDescending { it.lastConnectedAt }
        }.getOrDefault(emptyList())
    }

    private fun decodeLastSessions(prefs: Preferences): Map<String, String> {
        val raw = prefs[Keys.LAST_SESSIONS] ?: return emptyMap()
        return runCatching { WireJson.decodeFromString(lastSessionsSerializer, raw) }.getOrDefault(emptyMap())
    }

    /** Drawer session ordering: `"manual"` follows the workspace order, `"updated"` sorts by recency. */
    val sessionSort: Flow<String> = dataStore.data.map { it[Keys.SESSION_SORT] ?: "manual" }

    suspend fun setSessionSort(value: String) {
        dataStore.edit { it[Keys.SESSION_SORT] = value }
    }

    suspend fun setSetting(transform: (AppSettings) -> AppSettings) {
        var committed: AppSettings? = null
        dataStore.edit { prefs ->
            val next = transform(decodeSettings(prefs))
            prefs[Keys.BACKGROUND] = next.keepConnectedInBackground
            prefs[Keys.NOTIFY_TURN] = next.notifyTurnComplete
            prefs[Keys.NOTIFY_GOAL] = next.notifyGoal
            prefs[Keys.NOTIFY_ACTION] = next.notifyNeedsAction
            prefs[Keys.THEME] = next.themePreference
            next.backgroundImagePath?.let { prefs[Keys.BACKGROUND_IMAGE] = it }
                ?: prefs.remove(Keys.BACKGROUND_IMAGE)
            prefs[Keys.BACKGROUND_ADAPTIVE_CONTRAST] = next.backgroundAdaptiveContrast
            next.localeOverride?.let { prefs[Keys.LOCALE] = it } ?: prefs.remove(Keys.LOCALE)
            committed = next
        }
        // Mirrored out to SharedPreferences as well: the scheme has to be readable before any
        // activity exists, and DataStore cannot be read from there. Mirror only after the
        // transactional write succeeds so a failed DataStore update cannot leave startup theme
        // state ahead of the durable settings.
        DshApplication.storeThemePreference(context, checkNotNull(committed).themePreference)
    }

    private companion object {
        /** Bound on the remembered-session map. */
        const val MAX_REMEMBERED_HOSTS = 8
    }
}

/**
 * What a successful relay pairing tells this app about an endpoint.
 *
 * Grouped rather than passed as four loose parameters because they are only ever meaningful
 * together: a fingerprint without a device id describes a relay this device cannot talk to, and a
 * device id without a scheme describes one it cannot address.
 */
data class RelayIdentity(
    val deviceId: String,
    val useTls: Boolean,
    val fingerprint: String?,
    val tokenExpiresAt: Long,
)
