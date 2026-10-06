package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.interop.github.GitHubConnectorPlugin
import com.labteto.dshmobile.interop.github.GitHubConnectorStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * ToolsFeature owner for user-visible GitHub credential management.
 *
 * Validation reuses the connector's exact GitHub API contract without installing a second plugin
 * or creating another ToolRegistry. The encrypted credential authority remains
 * [LocalGitHubCredentialStore], shared by the live GitHub tool plugin.
 */
@Singleton
internal class LocalGitHubCredentialManager @Inject constructor(
    private val credentials: LocalGitHubCredentialStore,
    http: OkHttpClient,
    json: Json,
) {
    private val validator = GitHubConnectorPlugin(
        http = http,
        json = json,
        credentialProvider = credentials::get,
    )

    internal suspend fun configured(): Boolean = credentials.configured()

    internal suspend fun configure(token: String): GitHubConnectorStatus =
        persistValidatedGitHubCredential(
            token = token,
            validate = validator::validateCredential,
            save = credentials::put,
        )

    internal suspend fun clear() = credentials.clear()
}

internal suspend fun persistValidatedGitHubCredential(
    token: String,
    validate: suspend (String) -> GitHubConnectorStatus,
    save: suspend (String) -> Unit,
): GitHubConnectorStatus {
    val status = validate(token)
    save(token)
    return status
}
