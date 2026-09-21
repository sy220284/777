package com.labteto.dshmobile.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class UpdateInstallResult(
    val launchedInstaller: Boolean,
    val message: String,
)

@Singleton
class UpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
) {
    suspend fun downloadVerifyAndLaunch(update: AvailableUpdate): UpdateInstallResult =
        withContext(Dispatchers.IO) {
            val apkUrl = update.apkUrl ?: error("该发行版没有 APK 资源")
            val apkName = update.apkName ?: "777-${update.version}.apk"
            val directory = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(directory, apkName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            download(apkUrl, target, MAX_APK_BYTES)

            val expected = update.checksumUrl
                ?.let { fetchText(it, MAX_CHECKSUM_BYTES) }
                ?.let { parseChecksum(it, apkName) }
                ?: error("发行版缺少 ${apkName} 的 SHA256SUMS 校验项")
            val actual = sha256(target)
            require(actual.equals(expected, ignoreCase = true)) {
                "APK SHA-256 校验失败：期望 $expected，实际 $actual"
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
                message = "APK 已通过摘要、包名与签名校验，已交给 Android 系统安装器。",
            )
        }

    private fun download(url: String, target: File, maxBytes: Long) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.android.package-archive, application/octet-stream")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "下载 APK 失败：HTTP ${response.code}" }
            val body = response.body ?: error("下载 APK 返回空响应")
            val declared = body.contentLength()
            if (declared > maxBytes) error("APK 超过允许大小：$declared 字节")
            val temp = File(target.parentFile, target.name + ".part")
            temp.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) {
                            temp.delete()
                            error("APK 下载超过大小上限")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }
    }

    private fun fetchText(url: String, maxBytes: Long): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/plain, application/octet-stream")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "下载校验文件失败：HTTP ${response.code}" }
            val body = response.body ?: error("校验文件为空")
            val bytes = body.bytes()
            require(bytes.size <= maxBytes) { "校验文件异常过大" }
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
        const val MAX_CHECKSUM_BYTES = 256L * 1024L
    }
}
