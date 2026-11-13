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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Fixed upstream path; the ZIP importer still validates every downloaded file. */
internal fun curatedGitHubSkillUrl(id: String): String {
    require(id.matches(Regex("[a-z][a-z0-9-]{1,47}"))) { "官方技能标识格式无效" }
    return "https://github.com/openai/skills/tree/main/skills/.curated/$id"
}

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

/** Source provenance is imported atomically with the ZIP contents. */
internal fun githubSkillSourceEvidence(source: PublicGitHubSkillLocation, commit: String): ByteArray {
    require(commit.matches(Regex("[0-9a-fA-F]{40}"))) { "GitHub 固定提交无效" }
    return buildJsonObject {
        put("source_type", "github")
        put("owner", source.owner)
        put("repository", source.repository)
        put("source_ref", source.ref)
        put("directory", source.directory)
        put("commit", commit.lowercase())
        put("source_url", "https://github.com/${source.owner}/${source.repository}/tree/${commit.lowercase()}/${source.directory}")
    }.toString().toByteArray(Charsets.UTF_8)
}

/**
 * Validate the immutable source receipt before constructing a share URL. The untrusted JSON
 * cannot choose a different host, revision, repository, or directory for a share action.
 */
internal fun parseGitHubSkillSourceLink(document: String): String {
    require(document.toByteArray(Charsets.UTF_8).size in 1..8192) { "技能来源记录长度无效" }
    val value = Json.parseToJsonElement(document).jsonObject
    fun field(name: String): String =
        (value[name] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("技能来源缺少 $name")
    require(field("source_type") == "github") { "技能来源类型不受支持" }
    val owner = field("owner")
    val repository = field("repository")
    val directory = field("directory")
    val ref = field("source_ref")
    val commit = field("commit")
    require(owner.matches(Regex("[A-Za-z0-9-]{1,39}")) &&
        repository.matches(Regex("[A-Za-z0-9_.-]{1,100}")) &&
        repository !in setOf(".", "..") &&
        ref.matches(Regex("[A-Za-z0-9._-]{1,100}")) &&
        commit.matches(Regex("[0-9a-f]{40}")) &&
        directory.isNotBlank() && directory.split('/').all {
            it.matches(Regex("[A-Za-z0-9._-]{1,100}")) && it !in setOf(".", "..")
        }) { "GitHub 技能来源字段无效" }
    val canonical = "https://github.com/$owner/$repository/tree/$commit/$directory"
    require(field("source_url") == canonical) { "技能来源链接与固定版本不符" }
    return canonical
}

/** Re-reads and validates the installed origin before any network check or replacement. */
internal fun parseGitHubSkillUpdateSource(document: String): Pair<PublicGitHubSkillLocation, String> {
    parseGitHubSkillSourceLink(document)
    val fields = Json.parseToJsonElement(document).jsonObject
    fun value(key: String) = fields.getValue(key).jsonPrimitive.content
    val source = PublicGitHubSkillLocation.parse(
        "https://github.com/${value("owner")}/${value("repository")}/tree/${value("source_ref")}/${value("directory")}",
    )
    return source to value("commit")
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
        val commit = resolveCommit(source)
        skills.importSkill("github-skill.zip", download(source, commit))
    }

    suspend fun availableUpdate(id: String): String? = withContext(Dispatchers.IO) {
        val document = skills.githubSourceEvidence(id)
            ?: throw IllegalArgumentException("此技能没有可验证的 GitHub 来源")
        val (source, currentCommit) = parseGitHubSkillUpdateSource(document)
        resolveCommit(source).takeUnless { it == currentCommit }
    }

    suspend fun updateInstalled(id: String, expectedCommit: String): String = withContext(Dispatchers.IO) {
        require(expectedCommit.matches(Regex("[0-9a-f]{40}"))) { "更新版本无效" }
        val document = skills.githubSourceEvidence(id)
            ?: throw IllegalArgumentException("此技能没有可验证的 GitHub 来源")
        val (source, currentCommit) = parseGitHubSkillUpdateSource(document)
        val latest = resolveCommit(source)
        require(latest == expectedCommit && latest != currentCommit) {
            "远端版本已变化，请重新检查更新"
        }
        skills.replaceSkillPackage(id, download(source, latest))
    }

    fun canRollback(id: String): Boolean = skills.canRollbackSkill(id)
    suspend fun rollback(id: String): String = withContext(Dispatchers.IO) {
        skills.rollbackSkill(id)
    }

    private suspend fun download(source: PublicGitHubSkillLocation, commit: String): ByteArray {
        // All file downloads are pinned to one immutable GitHub commit.
        val pinned = source.copy(ref = commit)
        val files = linkedMapOf<String, ByteArray>()
        var totalBytes = 0L
        suspend fun collect(path: String, relative: String, depth: Int) {
            require(depth <= 10 && files.size < 128) { "技能目录过大或嵌套过深" }
            val root = get(pinned, path)
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
                        val blob = get(pinned, childPath).jsonObject
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
        require(".github-source.json" !in files) { "源技能包包含保留的来源证据文件" }
        require(files.size < 128) { "附加来源证据后技能文件数量超出限制" }
        files[".github-source.json"] = githubSkillSourceEvidence(source, commit)
        val zipped = ByteArrayOutputStream()
        ZipOutputStream(zipped).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return zipped.toByteArray()
    }

    /** A share link is a read-only local fact; it never fetches a branch or installs anything. */
    fun installedSourceLink(id: String): String? =
        skills.githubSourceEvidence(id)?.let(::parseGitHubSkillSourceLink)

    suspend fun curatedSkills(): List<String> = withContext(Dispatchers.IO) {
        val official = PublicGitHubSkillLocation(
            owner = "openai", repository = "skills", ref = "main", directory = "skills/.curated")
        (get(official, official.directory) as? JsonArray).orEmpty().mapNotNull {
            val obj = it.jsonObject
            obj["name"]?.jsonPrimitive?.contentOrNull.takeIf { obj["type"]?.jsonPrimitive?.contentOrNull == "dir" }
        }.sorted()
    }

    private fun resolveCommit(source: PublicGitHubSkillLocation): String {
        val url = "https://api.github.com/repos/${source.owner}/${source.repository}/commits/${source.ref}"
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json").get().build()
        val sha = http.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "GitHub 技能版本无法固定（HTTP ${response.code}）" }
            val bytes = response.body?.byteStream()?.use { it.readNBytes(128 * 1024 + 1) }
                ?: error("GitHub 没有返回版本信息")
            require(bytes.size <= 128 * 1024) { "GitHub 版本信息过大" }
            json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
                .jsonObject.string("sha")
        }
        require(sha.matches(Regex("[0-9a-fA-F]{40}"))) { "GitHub 返回的提交编号无效" }
        return sha.lowercase()
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
