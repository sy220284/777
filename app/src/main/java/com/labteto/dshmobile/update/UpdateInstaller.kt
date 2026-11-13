package com.labteto.dshmobile.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.github.sisong.HPatch
import com.labteto.dshmobile.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol

data class UpdateInstallResult(
    val launchedInstaller: Boolean,
    val message: String,
)

@Singleton
class UpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    client: OkHttpClient,
) {
    // GitHub release downloads are large. Keep them off HTTP/2 so a CDN stream reset does not
    // surface as stream was reset:CANCEL, and allow a longer idle period on mobile networks.
    private val downloadClient = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .pingInterval(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()
    private val payloadTransfer = UpdatePayloadTransfer(downloadClient)
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun downloadVerifyAndLaunch(update: AvailableUpdate): UpdateInstallResult =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            if (!context.packageManager.canRequestPackageInstalls()) {
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                currentCoroutineContext().ensureActive()
                context.startActivity(settingsIntent)
                return@withContext UpdateInstallResult(
                    launchedInstaller = false,
                    message = "已打开“允许安装未知应用”设置；授权后再次点击更新即可安装。",
                )
            }

            val apkUrl = update.apkUrl ?: error("该发行版没有 APK 资源")
            val apkName = update.apkName ?: "777-${update.version}.apk"
            val expected = update.expectedSha256
                ?: update.checksumUrl
                    ?.let { payloadTransfer.fetchText(it, MAX_CHECKSUM_BYTES) }
                    ?.let { parseUpdateChecksum(it, apkName) }
                ?: error("发行版缺少 ${apkName} 的 SHA-256 校验信息")

            // Update payloads are temporary staging data. Starting a new attempt always discards
            // any APK/patch left by an older attempt instead of accumulating one directory per
            // released version.
            val root = UpdateCache.prepare(
                cacheDir = context.cacheDir,
                currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                nowMillis = System.currentTimeMillis(),
            )
            val target = File(
                root,
                apkName.replace(Regex("[^A-Za-z0-9._-]"), "_"),
            )
            var installerOwnsTarget = false

            try {
                val usedDelta = tryDeltaUpdate(update, target, expected)
                if (!usedDelta) {
                    target.delete()
                    payloadTransfer.downloadFile(
                        url = apkUrl,
                        target = target,
                        maxBytes = MAX_APK_BYTES,
                        expectedBytes = update.apkSize,
                        kind = "APK",
                        accept = "application/vnd.android.package-archive, application/octet-stream",
                    )
                    val actual = sha256(target)
                    if (!actual.equals(expected, ignoreCase = true)) {
                        target.delete()
                        error("APK SHA-256 校验失败：期望 $expected，实际 $actual")
                    }
                }

                currentCoroutineContext().ensureActive()
                val targetVersionCode = verifyPackageAndSigner(target)
                currentCoroutineContext().ensureActive()

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.files",
                    target,
                )
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)

                // Protect exactly this verified APK across a possible process restart while Android
                // reads it. The marker is removed with the APK as soon as the target build is seen,
                // or after the bounded handoff window if installation was cancelled.
                currentCoroutineContext().ensureActive()
                UpdateCache.markInstallerHandoff(
                    root = root,
                    apk = target,
                    targetVersionCode = targetVersionCode,
                    handedAtMillis = System.currentTimeMillis(),
                )
                currentCoroutineContext().ensureActive()
                context.startActivity(intent)
                installerOwnsTarget = true

                maintenanceScope.launch {
                    delay(UpdateCache.INSTALLER_HANDOFF_GRACE_MS)
                    UpdateCache.cleanupStale(
                        cacheDir = context.cacheDir,
                        currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                        nowMillis = System.currentTimeMillis(),
                    )
                }

                UpdateInstallResult(
                    launchedInstaller = true,
                    message = if (usedDelta) {
                        "增量包已合成完整 APK，并通过摘要、包名与签名校验，已交给 Android 系统安装器。"
                    } else {
                        "APK 已通过摘要、包名与签名校验，已交给 Android 系统安装器。"
                    },
                )
            } finally {
                if (!installerOwnsTarget) {
                    // Download, digest, package/signature verification and installer-launch failures
                    // are all terminal for this staging attempt; never leave their APK behind.
                    UpdateCache.discard(root)
                }
            }
        }

    private suspend fun tryDeltaUpdate(
        update: AvailableUpdate,
        target: File,
        expectedTargetSha256: String,
    ): Boolean {
        if (update.patchChain.isEmpty()) return false
        val installedApk = File(context.applicationInfo.sourceDir)
        if (!installedApk.isFile) return false

        val intermediates = mutableListOf<File>()
        var source = installedApk

        try {
            for ((index, step) in update.patchChain.withIndex()) {
                if (!sha256(source).equals(step.sourceSha256, ignoreCase = true)) {
                    cleanupIntermediates(intermediates, target)
                    return false
                }

                val safePatchName = step.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val patchFile = File(target.parentFile, safePatchName)
                val canReusePatch = patchFile.isFile &&
                    patchFile.length() == step.size &&
                    sha256(patchFile).equals(step.expectedSha256, ignoreCase = true)
                if (!canReusePatch) {
                    patchFile.delete()
                    payloadTransfer.downloadFile(
                        url = step.url,
                        target = patchFile,
                        maxBytes = MAX_PATCH_BYTES,
                        expectedBytes = step.size,
                        kind = "增量包",
                        accept = "application/octet-stream",
                    )
                    val patchSha = sha256(patchFile)
                    if (!patchSha.equals(step.expectedSha256, ignoreCase = true)) {
                        patchFile.delete()
                        cleanupIntermediates(intermediates, target)
                        return false
                    }
                }

                val isLast = index == update.patchChain.lastIndex
                val output = if (isLast) {
                    target
                } else {
                    File(
                        target.parentFile,
                        "stage-${index + 1}-" +
                            step.toVersion.replace(Regex("[^A-Za-z0-9._-]"), "_") +
                            ".apk",
                    ).also(intermediates::add)
                }
                output.delete()

                currentCoroutineContext().ensureActive()
                val patchResult = try {
                    HPatch.patch(
                        source.absolutePath,
                        patchFile.absolutePath,
                        output.absolutePath,
                        PATCH_CACHE_BYTES,
                        PATCH_THREADS,
                        true,
                    )
                } finally {
                    // A patch is never needed again after it has been applied. Do not retain it
                    // beside the reconstructed APK while the system installer is open.
                    patchFile.delete()
                }
                currentCoroutineContext().ensureActive()
                if (patchResult != 0 || !output.isFile) {
                    output.delete()
                    cleanupIntermediates(intermediates, target)
                    return false
                }

                val outputSha = sha256(output)
                if (!outputSha.equals(step.targetSha256, ignoreCase = true)) {
                    output.delete()
                    cleanupIntermediates(intermediates, target)
                    return false
                }

                if (source !== installedApk && source != target) {
                    source.delete()
                    intermediates.remove(source)
                }
                source = output
            }

            val valid = source == target &&
                target.isFile &&
                sha256(target).equals(expectedTargetSha256, ignoreCase = true)
            if (!valid) target.delete()
            cleanupIntermediates(intermediates, if (valid) null else target)
            return valid
        } catch (cancelled: CancellationException) {
            cleanupIntermediates(intermediates, target)
            throw cancelled
        } catch (_: LinkageError) {
            cleanupIntermediates(intermediates, target)
            return false
        } catch (_: Exception) {
            cleanupIntermediates(intermediates, target)
            return false
        }
    }

    private fun cleanupIntermediates(files: Collection<File>, target: File?) {
        files.forEach(File::delete)
        target?.delete()
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun verifyPackageAndSigner(apk: File): Long {
        val flags = PackageManager.PackageInfoFlags.of(
            PackageManager.GET_SIGNING_CERTIFICATES.toLong(),
        )
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: error("Android 无法解析下载的 APK")
        require(archive.packageName == context.packageName) {
            "APK 包名不匹配：${archive.packageName}"
        }

        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val installedSigners = signerDigests(installed)
        val archiveSigners = signerDigests(archive)
        require(archiveSigners.isNotEmpty()) { "APK 未包含可验证签名，拒绝自动安装" }
        require(installedSigners == archiveSigners) {
            "APK 签名证书与当前安装版本不一致，拒绝自动安装"
        }
        require(archive.longVersionCode > installed.longVersionCode) {
            "APK 版本号没有高于当前安装版本，拒绝覆盖安装"
        }
        return archive.longVersionCode
    }

    private fun signerDigests(info: android.content.pm.PackageInfo): Set<String> {
        val signingInfo = info.signingInfo ?: return emptySet()
        val signatures = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private companion object {
        const val MAX_APK_BYTES = 200L * 1024L * 1024L
        const val MAX_PATCH_BYTES = 128L * 1024L * 1024L
        const val MAX_CHECKSUM_BYTES = 256L * 1024L
        const val PATCH_CACHE_BYTES = 8L * 1024L * 1024L
        const val PATCH_THREADS = 2
    }
}
