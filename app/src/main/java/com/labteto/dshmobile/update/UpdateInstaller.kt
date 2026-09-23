package com.labteto.dshmobile.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.github.sisong.HPatch
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request

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

    suspend fun downloadVerifyAndLaunch(update: AvailableUpdate): UpdateInstallResult =
        withContext(Dispatchers.IO) {
            val apkUrl = update.apkUrl ?: error("该发行版没有 APK 资源")
            val apkName = update.apkName ?: "777-${update.version}.apk"
            val expected = update.expectedSha256
                ?: update.checksumUrl
                    ?.let { fetchText(it, MAX_CHECKSUM_BYTES) }
                    ?.let { parseChecksum(it, apkName) }
                ?: error("发行版缺少 ${apkName} 的 SHA-256 校验信息")

            val root = File(context.cacheDir, "updates").apply { mkdirs() }
            val versionDirectory = File(
                root,
                update.version.replace(Regex("[^A-Za-z0-9._-]"), "_"),
            ).apply { mkdirs() }
            val target = File(
                versionDirectory,
                apkName.replace(Regex("[^A-Za-z0-9._-]"), "_"),
            )

            val canReuse = target.isFile &&
                (update.apkSize == null || target.length() == update.apkSize) &&
                sha256(target).equals(expected, ignoreCase = true)

            var usedDelta = false
            if (!canReuse) {
                target.delete()
                usedDelta = tryDeltaUpdate(update, target, expected)
                if (!usedDelta) {
                    target.delete()
                    downloadFile(
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
            } else {
                usedDelta = update.patchChain.isNotEmpty()
            }

            verifyPackageAndSigner(target)

            if (!context.packageManager.canRequestPackageInstalls()) {
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(settingsIntent)
                return@withContext UpdateInstallResult(
                    launchedInstaller = false,
                    message = "已打开“允许安装未知应用”设置；授权后再次点击更新即可安装。",
                )
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.files",
                target,
            )
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(intent)
            UpdateInstallResult(
                launchedInstaller = true,
                message = if (usedDelta) {
                    "增量包已合成完整 APK，并通过摘要、包名与签名校验，已交给 Android 系统安装器。"
                } else {
                    "APK 已通过摘要、包名与签名校验，已交给 Android 系统安装器。"
                },
            )
        }

    private fun tryDeltaUpdate(
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
                    downloadFile(
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

                val patchResult = HPatch.patch(
                    source.absolutePath,
                    patchFile.absolutePath,
                    output.absolutePath,
                    PATCH_CACHE_BYTES,
                    PATCH_THREADS,
                    true,
                )
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

    private fun downloadFile(
        url: String,
        target: File,
        maxBytes: Long,
        expectedBytes: Long?,
        kind: String,
        accept: String,
    ) {
        require(expectedBytes == null || expectedBytes in 1..maxBytes) {
            "$kind 大小异常：$expectedBytes 字节"
        }
        val temp = File(target.parentFile, target.name + ".part")
        var lastError: IOException? = null

        repeat(MAX_DOWNLOAD_ATTEMPTS) { attempt ->
            temp.delete()
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", accept)
                    .header("Cache-Control", "no-cache")
                    .get()
                    .build()
                downloadClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("下载 $kind 失败：HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IOException("下载 $kind 返回空响应")
                    val declared = body.contentLength()
                    if (declared > maxBytes) throw IOException("$kind 超过允许大小：$declared 字节")
                    if (expectedBytes != null && declared >= 0L && declared != expectedBytes) {
                        throw EOFException("$kind 响应长度异常：$declared/$expectedBytes 字节")
                    }

                    var total = 0L
                    temp.outputStream().use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > maxBytes ||
                                    (expectedBytes != null && total > expectedBytes)
                                ) {
                                    throw IOException("$kind 下载超过预期大小")
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    if (declared >= 0L && total != declared) {
                        throw EOFException("$kind 下载流提前结束：$total/$declared 字节")
                    }
                    if (expectedBytes != null && total != expectedBytes) {
                        throw EOFException("$kind 下载不完整：$total/$expectedBytes 字节")
                    }
                }

                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                return
            } catch (error: IOException) {
                lastError = error
                temp.delete()
                if (attempt + 1 < MAX_DOWNLOAD_ATTEMPTS) {
                    Thread.sleep(RETRY_BACKOFF_MS * (attempt + 1L))
                }
            }
        }

        throw IOException(
            "$kind 下载连接中断，已自动重试 $MAX_DOWNLOAD_ATTEMPTS 次：" +
                (lastError?.message ?: "未知网络错误"),
            lastError,
        )
    }

    private fun fetchText(url: String, maxBytes: Long): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/plain, application/octet-stream")
            .get()
            .build()
        return downloadClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "下载校验文件失败：HTTP ${response.code}" }
            val body = response.body ?: error("校验文件为空")
            require(body.contentLength() <= maxBytes) { "校验文件异常过大" }
            val bytes = body.byteStream().use { readChecksumBytes(it, maxBytes) }
            bytes.toString(Charsets.UTF_8)
        }
    }

    private fun parseChecksum(text: String, apkName: String): String? =
        text.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"), limit = 2)
                if (parts.size != 2) null
                else parts[0].takeIf {
                    parts[1].removePrefix("*").trim() == apkName &&
                        it.matches(Regex("[0-9a-fA-F]{64}"))
                }
            }
            .firstOrNull()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun verifyPackageAndSigner(apk: File) {
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
        const val MAX_DOWNLOAD_ATTEMPTS = 4
        const val RETRY_BACKOFF_MS = 500L
        const val PATCH_CACHE_BYTES = 8L * 1024L * 1024L
        const val PATCH_THREADS = 2
    }
}
