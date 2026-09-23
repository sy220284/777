package com.labteto.dshmobile.update

import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request

/** The subset of a GitHub release this app reads. */
@Serializable
private data class GithubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
    val size: Long = -1L,
    val digest: String? = null,
)

@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
private data class UpdateManifestApk(
    val name: String = "",
    val size: Long = -1L,
    val sha256: String = "",
)

@Serializable
private data class UpdateManifestPatch(
    val fromVersion: String = "",
    val toVersion: String = "",
    val algorithm: String = "",
    val asset: String = "",
    val size: Long = -1L,
    val sha256: String = "",
    val sourceSha256: String = "",
    val targetSha256: String = "",
)

@Serializable
private data class UpdateManifest(
    val schema: Int = 0,
    val targetVersion: String = "",
    val targetApk: UpdateManifestApk = UpdateManifestApk(),
    val patches: List<UpdateManifestPatch> = emptyList(),
)

data class DeltaPatch(
    val fromVersion: String,
    val toVersion: String,
    val algorithm: String,
    val url: String,
    val name: String,
    val size: Long,
    val expectedSha256: String,
    val sourceSha256: String,
    val targetSha256: String,
)

/** A release newer than the running build, including a directly verifiable APK when published. */
data class AvailableUpdate(
    val version: String,
    val url: String,
    val apkUrl: String? = null,
    val apkName: String? = null,
    val apkSize: Long? = null,
    val expectedSha256: String? = null,
    val checksumUrl: String? = null,
    val patchChain: List<DeltaPatch> = emptyList(),
)

/** Parse GitHub's release-asset digest field, which is currently shaped like `sha256:<hex>`. */
internal fun parseGithubSha256(digest: String?): String? {
    val value = digest?.trim() ?: return null
    val separator = value.indexOf(':')
    if (separator <= 0 || !value.substring(0, separator).equals("sha256", ignoreCase = true)) {
        return null
    }
    return value.substring(separator + 1)
        .takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
        ?.lowercase()
}

private fun normalizeVersion(value: String): String =
    value.trim().removePrefix("v").substringBefore('+')

/** Is [candidate] a later version than [current]? */
internal fun isNewerVersion(candidate: String, current: String): Boolean {
    data class ParsedVersion(
        val core: List<Int>,
        val releaseRevision: List<Int>?,
    )

    fun parse(value: String): ParsedVersion? {
        val normalized = normalizeVersion(value)
        if (normalized.isBlank()) return null
        val coreText = normalized.substringBefore('-')
        val core = coreText.split('.').map { part ->
            part.toIntOrNull() ?: return null
        }
        if (core.isEmpty()) return null

        // 777.N is this app's release revision, not a generic semantic-version pre-release label.
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

internal fun shouldUsePatchChain(apkSize: Long?, patches: List<DeltaPatch>): Boolean {
    if (apkSize == null || apkSize <= 0L || patches.isEmpty()) return false
    val patchBytes = patches.sumOf { it.size.coerceAtLeast(0L) }
    return patchBytes > 0L && patchBytes * 100L < apkSize * PATCH_SIZE_PERCENT_LIMIT
}

/**
 * Manual GitHub release checker.
 *
 * Nothing calls this at application startup. Each explicit Settings tap performs a fresh request.
 * Recent release metadata is fetched so consecutive differential packages can be chained; if any
 * edge is missing or inconsistent the caller simply receives the full APK fallback.
 */
@Singleton
class UpdateChecker @Inject constructor(
    client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // GitHub API/CDN HTTP/2 negotiation is unreliable on some mobile networks and proxies.
    // Keep update checks isolated from the app's shared client, force HTTP/1.1, and retry
    // transient transport failures. This also matches the release download path.
    private val githubClient = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    suspend fun checkNow(currentVersion: String): AvailableUpdate? {
        val releases = fetchRecentReleases()
            .filterNot { it.draft || it.prerelease }
        val release = releases.firstOrNull { candidate ->
            val version = normalizeVersion(candidate.tagName)
            version.isNotEmpty() && isNewerVersion(version, currentVersion)
        } ?: return null

        val version = normalizeVersion(release.tagName)
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
        val checksums = release.assets.firstOrNull {
            it.name.equals("SHA256SUMS.txt", ignoreCase = true)
        }
        val apkDigest = parseGithubSha256(apk?.digest)
        val candidateChain = if (apkDigest != null) {
            resolvePatchChain(
                currentVersion = normalizeVersion(currentVersion),
                latestVersion = version,
                releases = releases,
            )
        } else {
            emptyList()
        }
        val patchChain = candidateChain.takeIf { shouldUsePatchChain(apk?.size, it) }.orEmpty()

        return AvailableUpdate(
            version = version,
            url = release.htmlUrl.ifBlank { RELEASES_URL },
            apkUrl = apk?.downloadUrl?.takeIf(String::isNotBlank),
            apkName = apk?.name?.takeIf(String::isNotBlank),
            apkSize = apk?.size?.takeIf { it > 0L },
            expectedSha256 = apkDigest,
            checksumUrl = checksums?.downloadUrl?.takeIf(String::isNotBlank),
            patchChain = patchChain,
        )
    }

    private suspend fun resolvePatchChain(
        currentVersion: String,
        latestVersion: String,
        releases: List<GithubRelease>,
    ): List<DeltaPatch> {
        var targetVersion = latestVersion
        val reversed = mutableListOf<DeltaPatch>()

        repeat(MAX_PATCH_CHAIN_LENGTH) {
            if (normalizeVersion(targetVersion) == normalizeVersion(currentVersion)) {
                return reversed.asReversed()
            }

            val release = releases.firstOrNull {
                normalizeVersion(it.tagName) == normalizeVersion(targetVersion)
            } ?: return emptyList()
            val manifestAsset = release.assets.firstOrNull {
                it.name.equals(UPDATE_MANIFEST_NAME, ignoreCase = true)
            } ?: return emptyList()
            val manifest = fetchManifest(manifestAsset) ?: return emptyList()
            if (manifest.schema != UPDATE_MANIFEST_SCHEMA ||
                normalizeVersion(manifest.targetVersion) != normalizeVersion(targetVersion)
            ) {
                return emptyList()
            }

            val releaseApk = release.assets.firstOrNull {
                it.name.equals(manifest.targetApk.name, ignoreCase = false)
            } ?: return emptyList()
            val releaseApkSha = parseGithubSha256(releaseApk.digest) ?: return emptyList()
            if (releaseApk.size != manifest.targetApk.size ||
                !releaseApkSha.equals(manifest.targetApk.sha256, ignoreCase = true)
            ) {
                return emptyList()
            }

            val manifestPatch = manifest.patches.singleOrNull {
                normalizeVersion(it.toVersion) == normalizeVersion(targetVersion)
            } ?: return emptyList()
            if (manifestPatch.algorithm != SUPPORTED_PATCH_ALGORITHM ||
                manifestPatch.fromVersion.isBlank() ||
                normalizeVersion(manifestPatch.fromVersion) == normalizeVersion(targetVersion)
            ) {
                return emptyList()
            }

            val patchAsset = release.assets.firstOrNull { it.name == manifestPatch.asset }
                ?: return emptyList()
            val patchSha = parseGithubSha256(patchAsset.digest) ?: return emptyList()
            if (patchAsset.size <= 0L ||
                patchAsset.size != manifestPatch.size ||
                !patchSha.equals(manifestPatch.sha256, ignoreCase = true) ||
                !releaseApkSha.equals(manifestPatch.targetSha256, ignoreCase = true)
            ) {
                return emptyList()
            }

            reversed += DeltaPatch(
                fromVersion = normalizeVersion(manifestPatch.fromVersion),
                toVersion = normalizeVersion(manifestPatch.toVersion),
                algorithm = manifestPatch.algorithm,
                url = patchAsset.downloadUrl,
                name = patchAsset.name,
                size = patchAsset.size,
                expectedSha256 = patchSha,
                sourceSha256 = manifestPatch.sourceSha256.lowercase(),
                targetSha256 = manifestPatch.targetSha256.lowercase(),
            )
            targetVersion = manifestPatch.fromVersion
        }

        return emptyList()
    }

    private suspend fun fetchRecentReleases(): List<GithubRelease> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(RECENT_RELEASES_API)
            .header("Accept", "application/vnd.github+json")
            .header("Cache-Control", "no-cache")
            .get()
            .build()
        val body = executeGithubText(request, "检查 GitHub 发行版")
        json.decodeFromString(ListSerializer, body)
    }

    private suspend fun fetchManifest(asset: GithubAsset): UpdateManifest? = withContext(Dispatchers.IO) {
        val expected = parseGithubSha256(asset.digest) ?: return@withContext null
        if (asset.size !in 1..MAX_MANIFEST_BYTES) return@withContext null
        val request = Request.Builder()
            .url(asset.downloadUrl)
            .header("Accept", "application/json, application/octet-stream")
            .header("Cache-Control", "no-cache")
            .get()
            .build()

        // A missing/unreachable incremental manifest must never make "检查更新" fail. The full APK
        // is always a valid fallback, so manifest transport/parsing failures deliberately fail open.
        val bytes = try {
            executeGithubBytes(
                request = request,
                purpose = "读取增量更新清单",
                maxBytes = MAX_MANIFEST_BYTES,
            )
        } catch (_: IOException) {
            return@withContext null
        }
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) return@withContext null
        runCatching {
            json.decodeFromString(UpdateManifest.serializer(), bytes.toString(Charsets.UTF_8))
        }.getOrNull()
    }

    private suspend fun executeGithubText(request: Request, purpose: String): String =
        executeGithubBytes(request, purpose, MAX_RELEASE_METADATA_BYTES).toString(Charsets.UTF_8)

    private suspend fun executeGithubBytes(
        request: Request,
        purpose: String,
        maxBytes: Long,
    ): ByteArray {
        var lastTransportError: IOException? = null

        repeat(MAX_REQUEST_ATTEMPTS) { attempt ->
            try {
                githubClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val retryable = response.code == 408 ||
                            response.code == 429 ||
                            response.code in 500..599
                        if (!retryable || attempt + 1 >= MAX_REQUEST_ATTEMPTS) {
                            throw IOException("${purpose}失败：HTTP ${response.code}")
                        }
                        lastTransportError = IOException("${purpose}暂时失败：HTTP ${response.code}")
                    } else {
                        val body = response.body ?: throw IOException("${purpose}返回空响应")
                        val declared = body.contentLength()
                        if (declared > maxBytes) {
                            throw IOException("${purpose}响应异常过大：$declared 字节")
                        }
                        return readChecksumBytes(body.byteStream(), maxBytes)
                    }
                }
            } catch (error: IOException) {
                lastTransportError = error
            }

            if (attempt + 1 < MAX_REQUEST_ATTEMPTS) {
                delay(REQUEST_RETRY_BACKOFF_MS * (attempt + 1L))
            }
        }

        throw IOException(
            "${purpose}连接失败，已自动重试 $MAX_REQUEST_ATTEMPTS 次，请检查网络后重试。",
            lastTransportError,
        )
    }

    private companion object {
        const val REPO = "sy220284/777"
        const val MAX_RELEASES_TO_SCAN = 12
        const val MAX_PATCH_CHAIN_LENGTH = 6
        const val MAX_MANIFEST_BYTES = 256L * 1024L
        const val MAX_RELEASE_METADATA_BYTES = 2L * 1024L * 1024L
        const val MAX_REQUEST_ATTEMPTS = 3
        const val REQUEST_RETRY_BACKOFF_MS = 400L
        const val RELEASES_URL = "https://github.com/$REPO/releases/latest"
        const val RECENT_RELEASES_API =
            "https://api.github.com/repos/$REPO/releases?per_page=$MAX_RELEASES_TO_SCAN"
        const val UPDATE_MANIFEST_NAME = "update-manifest.json"
        const val UPDATE_MANIFEST_SCHEMA = 1
        const val SUPPORTED_PATCH_ALGORITHM = "hdiffpatch-window-zstd-v1"

        val ListSerializer = kotlinx.serialization.builtins.ListSerializer(GithubRelease.serializer())
    }
}

private const val PATCH_SIZE_PERCENT_LIMIT = 65L
