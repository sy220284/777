package com.labteto.dshmobile.local.tools

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream
import com.labteto.dshmobile.core.net.withCancellableHttpResponse
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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


/**
 * Resolve an immutable public Git revision from GitHub's advertised Git refs, which are not
 * subject to the GitHub REST API's unauthenticated request quota.
 */
internal fun advertisedGitCommit(advertisement: InputStream, ref: String): String {
    require(ref.matches(Regex("[A-Za-z0-9._-]{1,100}"))) { "GitHub 分支或标签无效" }
    val matches = mutableMapOf<String, String>()
    DataInputStream(advertisement).use { input ->
        while (true) {
            val first = input.read()
            if (first < 0) break
            val header = ByteArray(4)
            header[0] = first.toByte()
            input.readFully(header, 1, 3)
            val size = header.toString(Charsets.US_ASCII).toIntOrNull(16)
                ?: throw IOException("GitHub Git 引用响应格式无效")
            if (size == 0 || size == 1 || size == 2) continue
            require(size in 4..65520) { "GitHub Git 引用长度无效" }
            val packet = ByteArray(size - 4)
            input.readFully(packet)
            val item = packet.toString(Charsets.UTF_8).substringBefore('\u0000').trimEnd('\n')
            val space = item.indexOf(' ')
            if (space == 40 && item.substring(0, space).matches(Regex("[0-9a-fA-F]{40}"))) {
                matches[item.substring(space + 1)] = item.substring(0, 40).lowercase()
            }
        }
    }
    return matches["refs/heads/$ref"] ?: matches["refs/tags/$ref^{}"]
        ?: matches["refs/tags/$ref"]
        ?: throw IOException("GitHub 未找到分支或标签：$ref")
}

/** Select only the requested subtree from a public immutable repository snapshot. */
internal fun writePinnedGitHubSkillArchive(
    source: PublicGitHubSkillLocation,
    commit: String,
    snapshot: InputStream,
    destination: OutputStream,
): Int {
    val prefix = source.directory.trimEnd('/') + "/"
    var count = 0
    var archiveRoot: String? = null
    ZipInputStream(snapshot.buffered()).use { incoming ->
        ZipOutputStream(destination.buffered()).use { outgoing ->
            while (true) {
                val entry = incoming.nextEntry ?: break
                val root = entry.name.substringBefore('/')
                require(root.isNotBlank() && root != "." && root != "..") {
                    "GitHub 仓库压缩包根目录无效"
                }
                if (archiveRoot == null) archiveRoot = root
                require(archiveRoot == root) { "GitHub 仓库压缩包包含不一致的根目录" }
                val path = entry.name.substringAfter('/', "")
                if (!entry.isDirectory && path.startsWith(prefix)) {
                    val relative = path.removePrefix(prefix)
                    require(relative.isNotBlank() && relative != ".github-source.json" &&
                        !relative.startsWith("/") && '\\' !in relative &&
                        relative.split('/').none { it.isBlank() || it == "." || it == ".." }) {
                        "GitHub 技能资源路径不安全"
                    }
                    outgoing.putNextEntry(ZipEntry(relative))
                    incoming.copyTo(outgoing)
                    outgoing.closeEntry()
                    count++
                }
                incoming.closeEntry()
            }
            require(count > 0) { "GitHub 所选目录没有技能文件" }
            outgoing.putNextEntry(ZipEntry(".github-source.json"))
            outgoing.write(githubSkillSourceEvidence(source, commit))
            outgoing.closeEntry()
        }
    }
    return count
}

/** Fetches public GitHub skills as a single pinned snapshot and admits them through the shared
 * streaming ZIP importer. REST API quota exhaustion must not make official skills unusable. */
@Singleton
internal class LocalGitHubSkillImporter @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
    private val skills: LocalSkillManager,
    private val credentials: LocalGitHubCredentialStore,
    @ApplicationContext private val context: Context,
) {
    suspend fun install(link: String): String = withContext(Dispatchers.IO) {
        val source = PublicGitHubSkillLocation.parse(link)
        installPinned(source, resolveCommit(source), null)
    }

    suspend fun availableUpdate(id: String): String? = withContext(Dispatchers.IO) {
        val evidence = skills.githubSourceEvidence(id)
            ?: throw IllegalArgumentException("此技能没有可验证的 GitHub 来源")
        val (source, currentCommit) = parseGitHubSkillUpdateSource(evidence)
        resolveCommit(source).takeUnless { it == currentCommit }
    }

    suspend fun updateInstalled(id: String, expectedCommit: String): String = withContext(Dispatchers.IO) {
        require(expectedCommit.matches(Regex("[0-9a-f]{40}"))) { "更新版本无效" }
        val evidence = skills.githubSourceEvidence(id)
            ?: throw IllegalArgumentException("此技能没有可验证的 GitHub 来源")
        val (source, currentCommit) = parseGitHubSkillUpdateSource(evidence)
        val latest = resolveCommit(source)
        require(latest == expectedCommit && latest != currentCommit) {
            "远端版本已变化，请重新检查更新"
        }
        installPinned(source, latest, id)
    }

    fun canRollback(id: String): Boolean = skills.canRollbackSkill(id)

    suspend fun rollback(id: String): String = withContext(Dispatchers.IO) {
        skills.rollbackSkill(id)
    }

    fun installedSourceLink(id: String): String? =
        skills.githubSourceEvidence(id)?.let(::parseGitHubSkillSourceLink)

    /** Listing is read-only. If the public REST quota is exhausted, enumerate the pinned ZIP. */
    suspend fun curatedSkills(): List<String> = withContext(Dispatchers.IO) {
        val official = PublicGitHubSkillLocation(
            owner = "openai", repository = "skills", ref = "main", directory = "skills/.curated")
        try {
            (get(official.directory, official) as? JsonArray).orEmpty().mapNotNull {
                val entry = it.jsonObject
                if (entry["type"]?.jsonPrimitive?.contentOrNull != "dir") null
                else entry["name"]?.jsonPrimitive?.contentOrNull
            }.sorted()
        } catch (failure: GitHubSkillApiException) {
            if (failure.status != 403 && failure.status != 429) throw failure
            val pinned = resolveCommit(official)
            useSnapshot(official, pinned) { stream ->
                val names = linkedSetOf<String>()
                ZipInputStream(stream.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val path = entry.name.substringAfter('/', "")
                        val relative = path.removePrefix("skills/.curated/")
                        if (path.startsWith("skills/.curated/") &&
                            relative.contains('/') && relative.substringBefore('/').isNotBlank()) {
                            names += relative.substringBefore('/')
                        }
                        zip.closeEntry()
                    }
                }
                names.sorted()
            }
        }
    }

    private suspend fun installPinned(
        source: PublicGitHubSkillLocation, commit: String, replaceId: String?,
    ): String {
        val owner = currentCoroutineContext()
        val scratch = File.createTempFile("github-skill-", ".zip", context.cacheDir)
        try {
            useSnapshot(source, commit) { archive ->
                scratch.outputStream().use { output ->
                    writePinnedGitHubSkillArchive(source, commit, archive, output)
                }
            }
            owner.ensureActive()
            return scratch.inputStream().use { input ->
                val stream = object : java.io.FilterInputStream(input) {
                    override fun read(): Int {
                        owner.ensureActive()
                        return super.read().also { owner.ensureActive() }
                    }
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        owner.ensureActive()
                        return `in`.read(bytes, offset, length).also { owner.ensureActive() }
                    }
                }
                if (replaceId == null) skills.importSkill("github-skill.zip", stream) { owner.ensureActive() }
                else skills.replaceSkillPackage(replaceId, stream) { owner.ensureActive() }
            }
        } finally {
            if (scratch.exists()) require(scratch.delete()) { "临时技能包清理失败" }
        }
    }

    private suspend fun <T> useSnapshot(
        source: PublicGitHubSkillLocation, commit: String, consume: (InputStream) -> T,
    ): T {
        val url = "https://codeload.github.com/${source.owner}/${source.repository}/zip/$commit"
        val request = Request.Builder().url(url).get().build()
        val owner = currentCoroutineContext()
        return withCancellableHttpResponse(http.newCall(request)) { response ->
            require(response.isSuccessful) {
                "无法下载 GitHub 技能目录（HTTP ${response.code}），请检查网络"
            }
            val body = response.body ?: throw IOException("GitHub 仓库压缩包为空")
            body.byteStream().use { raw ->
                val stream = object : java.io.FilterInputStream(raw) {
                    override fun read(): Int {
                        owner.ensureActive()
                        return super.read().also { owner.ensureActive() }
                    }
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        owner.ensureActive()
                        return `in`.read(bytes, offset, length).also { owner.ensureActive() }
                    }
                }
                consume(stream).also { owner.ensureActive() }
            }
        }
    }

    private suspend fun resolveCommit(source: PublicGitHubSkillLocation): String {
        if (source.ref.matches(Regex("[0-9a-fA-F]{40}"))) return source.ref.lowercase()
        val request = Request.Builder()
            .url("https://github.com/${source.owner}/${source.repository}.git/info/refs?service=git-upload-pack")
            .header("Accept", "application/x-git-upload-pack-advertisement").get().build()
        try {
            return http.newCall(request).execute().use { response ->
                require(response.isSuccessful) {
                    "GitHub 公开版本查询失败（HTTP ${response.code}）"
                }
                val body = response.body ?: throw IOException("GitHub 未返回公开版本信息")
                advertisedGitCommit(body.byteStream(), source.ref)
            }
        } catch (publicError: Exception) {
            if (publicError is CancellationException) throw publicError
            val token = credentials.get()?.takeIf(String::isNotBlank)
                ?: throw IOException("GitHub 公开仓库连接失败，请检查网络或在工具设置中连接 GitHub 账户", publicError)
            val url = "https://api.github.com/repos/${source.owner}/${source.repository}/commits/${source.ref}"
            val api = Request.Builder().url(url)
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer $token").get().build()
            return http.newCall(api).execute().use { response ->
                require(response.isSuccessful) {
                    "GitHub 版本查询失败（HTTP ${response.code}），请检查账户权限"
                }
                val data = response.body?.string() ?: throw IOException("GitHub 没有返回版本信息")
                val sha = json.parseToJsonElement(data).jsonObject.string("sha")
                require(sha.matches(Regex("[0-9a-fA-F]{40}"))) { "GitHub 返回的提交编号无效" }
                sha.lowercase()
            }
        }
    }

    private suspend fun get(path: String, source: PublicGitHubSkillLocation): JsonElement {
        val encoded = path.split('/').joinToString("/") {
            java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        val url = "https://api.github.com/repos/${source.owner}/${source.repository}/contents/$encoded?ref=${source.ref}"
        val token = credentials.get()?.takeIf(String::isNotBlank)
        val builder = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
        if (token != null) builder.header("Authorization", "Bearer $token")
        return http.newCall(builder.get().build()).execute().use { response ->
            if (!response.isSuccessful) throw GitHubSkillApiException(response.code)
            val body = response.body?.byteStream() ?: throw IOException("GitHub 返回空目录")
            val bytes = body.use { it.readNBytes(4 * 1024 * 1024 + 1) }
            require(bytes.size <= 4 * 1024 * 1024) { "GitHub 目录元数据异常庞大" }
            json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
        }
    }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull ?: error("GitHub 技能缺少 $key")
}

private class GitHubSkillApiException(val status: Int) :
    IOException("GitHub 目录请求失败（HTTP $status）")
