package com.labteto.dshmobile.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/** The subset of a GitHub release this app reads. */
@Serializable
private data class GithubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
)

@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<GithubAsset> = emptyList(),
)

/** A release newer than the running build, including a directly verifiable APK when published. */
data class AvailableUpdate(
    val version: String,
    val url: String,
    val apkUrl: String? = null,
    val apkName: String? = null,
    val checksumUrl: String? = null,
)

/** Is [candidate] a later version than [current]? */
internal fun isNewerVersion(candidate: String, current: String): Boolean {
    data class ParsedVersion(
        val core: List<Int>,
        val releaseRevision: List<Int>?,
    )

    fun parse(value: String): ParsedVersion? {
        val normalized = value.trim().removePrefix("v").substringBefore('+')
        if (normalized.isBlank()) return null
        val coreText = normalized.substringBefore('-')
        val core = coreText.split('.').map { part ->
            part.toIntOrNull() ?: return null
        }
        if (core.isEmpty()) return null

        // 777.N is this app's release revision, not a generic semantic-version pre-release label.
        // Other suffixes (rc/beta/etc.) keep the previous behavior: they do not make an otherwise
        // equal core version count as an upgrade.
        val suffix = normalized.substringAfter('-', missingDelimiterValue = "")
        val revision = suffix
            .takeIf { it.matches(Regex("""777(?:\.\d+)*""")) }
            ?.split('.')
            ?.map { it.toInt() }

        return ParsedVersion(core = core, releaseRevision = revision)
    }

    fun compareParts(left: List<Int>, right: List<Int>): Int {
        for (i in 0 until maxOf(left.size, right.size)) {
            val a = left.getOrElse(i) { 0 }
            val b = right.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    val candidateVersion = parse(candidate) ?: return false
    val currentVersion = parse(current) ?: return true

    val coreComparison = compareParts(candidateVersion.core, currentVersion.core)
    if (coreComparison != 0) return coreComparison > 0

    val candidateRevision = candidateVersion.releaseRevision
    val currentRevision = currentVersion.releaseRevision
    if (candidateRevision != null || currentRevision != null) {
        return compareParts(candidateRevision.orEmpty(), currentRevision.orEmpty()) > 0
    }
    return false
}

/**
 * Manual GitHub release checker.
 *
 * Nothing calls this at application startup. Each explicit Settings tap performs a fresh request,
 * so a user can retry after a failed connection or re-check while the app stays open.
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkNow(currentVersion: String): AvailableUpdate? {
        val release = fetchLatest() ?: error("无法获取最新发行版")
        val version = release.tagName.trim().removePrefix("v")
        if (version.isEmpty() || !isNewerVersion(version, currentVersion)) return null

        val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
        val checksums = release.assets.firstOrNull {
            it.name.equals("SHA256SUMS.txt", ignoreCase = true)
        }
        return AvailableUpdate(
            version = version,
            url = release.htmlUrl.ifBlank { RELEASES_URL },
            apkUrl = apk?.downloadUrl?.takeIf(String::isNotBlank),
            apkName = apk?.name?.takeIf(String::isNotBlank),
            checksumUrl = checksums?.downloadUrl?.takeIf(String::isNotBlank),
        )
    }

    private suspend fun fetchLatest(): GithubRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string() ?: return@use null
            json.decodeFromString(GithubRelease.serializer(), body)
        }
    }

    private companion object {
        const val REPO = "sy220284/777"
        const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPO/releases/latest"
        const val RELEASES_URL = "https://github.com/$REPO/releases/latest"
    }
}
