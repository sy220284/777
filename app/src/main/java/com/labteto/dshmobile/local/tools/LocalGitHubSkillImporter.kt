package com.labteto.dshmobile.local.tools

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class PublicGitHubSkillLocation(
    val owner: String, val repository: String, val ref: String, val directory: String,
) {
    companion object {
        fun parse(link: String): PublicGitHubSkillLocation {
            val url = URI(link.trim())
            require(url.scheme.equals("https", true) && url.host.equals("github.com", true) &&
                url.userInfo == null && url.port == -1 && url.rawQuery == null && url.rawFragment == null) {
                "请输入 GitHub 网站的技能目录地址"
            }
            val parts = url.path.split('/').filter(String::isNotBlank)
            require(parts.size >= 5 && parts[2] in setOf("tree", "blob")) {
                "地址格式：github.com/owner/repo/tree/branch/skills/name"
            }
            val owner = parts[0]
            val repo = parts[1].removeSuffix(".git")
            val ref = parts[3]
            val directory = parts.drop(4).joinToString("/").removeSuffix("/SKILL.md")
            val ownerPattern = Regex("[A-Za-z0-9-]{1,39}")
            val repoPattern = Regex("[A-Za-z0-9_.-]{1,100}")
            require(ownerPattern.matches(owner) && repoPattern.matches(repo) &&
                repo != "." && repo != ".." && Regex("[A-Za-z0-9._-]{1,100}").matches(ref) &&
                directory.isNotBlank() && directory.split('/').all {
                    it.matches(Regex("[A-Za-z0-9._-]{1,100}")) && it != "." && it != ".."
                }) { "技能仓库路径或分支无效" }
            return PublicGitHubSkillLocation(owner, repo, ref, directory)
        }
    }
}

/** GitHub's directory listing is metadata, never permission to walk outside the chosen folder. */
internal fun requireGitHubSkillChildPath(parent: String, name: String, advertisedPath: String): String {
    require(name.matches(Regex("[A-Za-z0-9._-]{1,100}")) && name != "." && name != "..") {
        "技能文件名无效"
    }
    require(advertisedPath == "$parent/$name") {
        "GitHub 技能目录条目越过所选目录或返回了不一致的路径"
    }
    return advertisedPath
}

/** Fetches a single public GitHub skill folder, then delegates admission to the existing
 * ZIP importer. No desktop Python, Git binary, remote execution or hidden cloud service. */
@Singleton
internal class LocalGitHubSkillImporter @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
    private val skills: LocalSkillManager,
) {
    suspend fun install(link: String): String = withContext(Dispatchers.IO) {
        val source = PublicGitHubSkillLocation.parse(link)
        val files = linkedMapOf<String, ByteArray>()
        var totalBytes = 0L
        suspend fun collect(path: String, relative: String, depth: Int) {
            require(depth <= 10 && files.size < 128) { "技能目录过大或嵌套过深" }
            val root = get(source, path)
            val entries = when (root) {
                is JsonArray -> root.toList()
                is JsonObject -> listOf(root)
                else -> error("GitHub 返回了意外的目录格式")
            }
            for (entry in entries) {
                val item = entry.jsonObject
                val name = item.string("name")
                val childPath = requireGitHubSkillChildPath(path, name, item.string("path"))
                val childRelative = if (relative.isEmpty()) name else "$relative/$name"
                when (item.string("type")) {
                    "dir" -> collect(childPath, childRelative, depth + 1)
                    "file" -> {
                        require(childRelative !in files) { "技能目录包含重复文件，拒绝覆盖" }
                        val blob = get(source, childPath).jsonObject
                        require(blob.string("encoding") == "base64") { "技能文件不可按 Base64 读取" }
                        val value = blob.string("content").replace("\n", "")
                        val bytes = Base64.getDecoder().decode(value)
                        totalBytes += bytes.size
                        require(bytes.size <= 8 * 1024 * 1024 && totalBytes <= 24L * 1024 * 1024 &&
                            files.size < 128) { "技能资源超过本机导入容量" }
                        files[childRelative] = bytes
                    }
                    else -> error("暂不支持此 GitHub 文件类型")
                }
            }
        }
        collect(source.directory, "", 0)
        require("SKILL.md" in files) { "所选 GitHub 目录中没有 SKILL.md" }
        val zipped = ByteArrayOutputStream()
        ZipOutputStream(zipped).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        skills.importSkill("github-skill.zip", zipped.toByteArray())
    }

    suspend fun curatedSkills(): List<String> = withContext(Dispatchers.IO) {
        val official = PublicGitHubSkillLocation(
            owner = "openai", repository = "skills", ref = "main", directory = "skills/.curated")
        (get(official, official.directory) as? JsonArray).orEmpty().mapNotNull {
            val obj = it.jsonObject
            obj["name"]?.jsonPrimitive?.contentOrNull.takeIf { obj["type"]?.jsonPrimitive?.contentOrNull == "dir" }
        }.sorted()
    }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull ?: error("GitHub 技能缺少 $key")

    private fun get(source: PublicGitHubSkillLocation, path: String): JsonElement {
        val encodedPath = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8") }
        val url = "https://api.github.com/repos/${source.owner}/${source.repository}/contents/$encodedPath?ref=${source.ref}"
        val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json").get().build()
        return http.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "无法读取 GitHub 技能（HTTP ${response.code}）" }
            val data = response.body?.byteStream()?.use { stream ->
                // The whole directory/API response is small; downloaded assets have separate bounds.
                stream.readNBytes(12 * 1024 * 1024 + 1)
            } ?: error("GitHub 返回空内容")
            require(data.size <= 12 * 1024 * 1024) { "GitHub 响应过大" }
            json.parseToJsonElement(data.toString(Charsets.UTF_8))
        }
    }
}
