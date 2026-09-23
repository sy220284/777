package com.labteto.dshmobile.connection

import kotlinx.serialization.Serializable

/**
 * One remembered harness endpoint, reached directly or through a `dsh-relay`.
 *
 * The `last*` fields cache the newest `host.describe` so a Recent card can say what the harness is
 * before its liveness probe lands — and can still say it about a harness that is now switched off.
 * They all default, because the whole list is persisted as one JSON blob whose decode failure is
 * swallowed: a field without a default would silently wipe every remembered host on upgrade. That
 * applies just as much to the relay fields below, which is why every one of them has a default even
 * though a paired relay always has three of them set.
 *
 * The bearer token is deliberately **not** here. This record is serialized into plain DataStore;
 * the credential lives in [RelayCredentialStore], keyed by [id].
 */
@Serializable
data class HostConfig(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val isLoopback: Boolean = false,
    /**
     * Speak TLS to this endpoint. The harness itself never serves HTTPS — this is for a reverse
     * proxy someone put in front of it (Caddy at `https://agent.home`, say), which is the only
     * way it is ever reached over TLS.
     */
    val useTls: Boolean = false,
    val lastConnectedAt: Long = 0L,
    /**
     * The host account's home directory, as of the last successful connection.
     *
     * This is the only host fact 0.1.2 still publishes. Through 0.1.1 the record also remembered
     * the harness version, its working directory and its attached-session count, all from
     * `host.describe`; that call is gone and nothing replaces those three, so they are no longer
     * remembered or shown.
     */
    val lastHome: String? = null,
    /** Base64 SHA-256 of the relay's DER SubjectPublicKeyInfo; null when there is nothing to pin. */
    val relayFingerprint: String? = null,
    /** The relay's own id for this device, shown in its device list. Non-null iff this host is paired. */
    val relayDeviceId: String? = null,
    /** Epoch millis the device token expires, as the relay reported it at pairing. */
    val relayTokenExpiresAt: Long = 0L,
) {
    /** Bare `host:port` — the identity key and display form, deliberately scheme-free. */
    val authority: String get() = "$host:$port"
    val baseUrl: String get() = harnessBaseUrl(host, port, useTls)

    /** What a card prints: the authority, scheme-qualified only when it is not the plain default. */
    val displayAddress: String get() = if (useTls) "https://$authority" else authority

    /**
     * Whether this endpoint is a paired relay.
     *
     * Keyed on the device id rather than on the transport: a relay running `tls: off` is still a
     * relay that needs its bearer token, and an `https` address this device never paired with is
     * not one.
     */
    val isRelay: Boolean get() = relayDeviceId != null

    /** Whether traffic to this endpoint travels in the clear. */
    val isPlaintext: Boolean get() = !useTls
}

/** App-level persisted settings (DataStore). Remote control is relay-only. */
data class AppSettings(
    val keepConnectedInBackground: Boolean = false,
    val notifyTurnComplete: Boolean = true,
    val notifyGoal: Boolean = true,
    val notifyNeedsAction: Boolean = true,
    val themePreference: String = "system", // light | dark | system
    val localeOverride: String? = null, // null = system
    /**
     * Absolute path of the copied background image inside app storage, or null for the plain theme
     * colour. The name of the file in [com.labteto.dshmobile.ui.theme.APP_BACKGROUND_DIR] is not
     * stored instead of the path because the path is what `BitmapFactory` wants, and `filesDir`
     * does not move for an installed app.
     */
    val backgroundImagePath: String? = null,
)
