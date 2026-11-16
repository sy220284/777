package com.labteto.dshmobile.local.work

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Convert native Responses custom apply_patch text to an ordinary Git patch.
 * No filesystem mutation occurs here. The existing git apply --check and
 * git apply sequence remains the only writer and transaction boundary.
 */
internal fun normalizeWorkspacePatch(patch: String, workspaceRoot: File): String {
    if (!patch.trimStart().startsWith("*** Begin Patch")) return patch
    val lines = patch.replace("\r\n", "\n").trimEnd('\n').split('\n')
    require(lines.first() == "*** Begin Patch" && lines.last() == "*** End Patch") {
        "Codex 补丁必须以 *** Begin Patch 开始、以 *** End Patch 结束"
    }
    val root = workspaceRoot.canonicalFile.toPath()
    val result = StringBuilder()
    val edited = linkedSetOf<String>()
    var cursor = 1

    fun filename(raw: String): String {
        require(raw.isNotBlank() && raw == raw.trim() && '\\' !in raw &&
            !raw.startsWith('/') && raw.none { it.code < 32 || it.code == 127 }) {
            "补丁路径无效"
        }
        require(raw.split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "补丁路径不得跨越工作区或包含空目录"
        }
        require(File(workspaceRoot, raw).canonicalFile.toPath().startsWith(root)) {
            "补丁路径越过工作区边界"
        }
        return raw
    }

    fun read(path: String): String {
        val file = File(workspaceRoot, path)
        require(file.isFile && file.length() <= 8_000_000L) {
            "Codex 补丁只能更新不超过 8MB 的已有文本文件"
        }
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(file.readBytes())).toString()
    }

    fun textLines(s: String): List<String> =
        if (s.isEmpty()) emptyList() else s.removeSuffix("\n").split('\n')

    fun diff(path: String, previous: String?, next: String?) {
        require(previous != next) { "补丁没有实际文件更改：$path" }
        val before = textLines(previous.orEmpty())
        val after = textLines(next.orEmpty())
        val oldName = if (previous != null) "a/$path" else "/dev/null"
        val newName = if (next != null) "b/$path" else "/dev/null"
        result.append("diff --git a/$path b/$path\n")
        if (previous == null) result.append("new file mode 100644\n")
        if (next == null) result.append("deleted file mode 100644\n")
        result.append("--- $oldName\n+++ $newName\n")
        var prefix = 0
        while (prefix < before.size && prefix < after.size &&
            before[prefix] == after[prefix]) prefix++
        var suffix = 0
        while (suffix < before.size - prefix && suffix < after.size - prefix &&
            before[before.lastIndex - suffix] == after[after.lastIndex - suffix]) suffix++
        val contextBefore = minOf(3, prefix)
        val contextAfter = minOf(3, suffix)
        val start = prefix - contextBefore
        val oldEnd = before.size - suffix + contextAfter
        val newEnd = after.size - suffix + contextAfter
        val oldCount = oldEnd - start
        val newCount = newEnd - start
        val oldStart = if (oldCount == 0) start else start + 1
        val newStart = if (newCount == 0) start else start + 1
        result.append("@@ -$oldStart,$oldCount +$newStart,$newCount @@\n")
        for (i in start until prefix) result.append(" " + before[i] + "\n")
        for (i in prefix until before.size - suffix) {
            result.append("-" + before[i] + "\n")
            if (previous != null && !previous.endsWith("\n") && i == before.lastIndex)
                result.append("\\ No newline at end of file\n")
        }
        for (i in prefix until after.size - suffix) {
            result.append("+" + after[i] + "\n")
            if (next != null && !next.endsWith("\n") && i == after.lastIndex)
                result.append("\\ No newline at end of file\n")
        }
        for (i in before.size - suffix until oldEnd) {
            result.append(" " + before[i] + "\n")
            if (previous != null && !previous.endsWith("\n") && i == before.lastIndex)
                result.append("\\ No newline at end of file\n")
        }
    }

    while (cursor < lines.lastIndex) {
        val header = lines[cursor++]
        val kind = when {
            header.startsWith("*** Add File: ") -> "add"
            header.startsWith("*** Delete File: ") -> "delete"
            header.startsWith("*** Update File: ") -> "update"
            else -> error("未知 Codex 文件操作：$header")
        }
        val name = filename(header.substringAfter(": "))
        require(edited.add(name)) { "同一补丁中不得重复操作文件：$name" }
        val file = File(workspaceRoot, name)
        when (kind) {
            "add" -> {
                require(!file.exists()) { "文件已存在：$name" }
                val added = mutableListOf<String>()
                while (cursor < lines.lastIndex && !lines[cursor].startsWith("*** ")) {
                    val line = lines[cursor++]
                    require(line.startsWith("+")) { "新增文件每行必须使用 + 前缀" }
                    added += line.substring(1)
                }
                require(added.isNotEmpty()) { "新增文件不能为空" }
                diff(name, null, added.joinToString("\n", postfix = "\n"))
            }
            "delete" -> {
                require(cursor == lines.lastIndex || lines[cursor].startsWith("*** ")) {
                    "删除文件操作后不接受其他内容"
                }
                diff(name, read(name), null)
            }
            else -> {
                val before = read(name)
                val current = textLines(before).toMutableList()
                var destination: String? = null
                if (cursor < lines.lastIndex && lines[cursor].startsWith("*** Move to: ")) {
                    destination = filename(lines[cursor++].removePrefix("*** Move to: "))
                    require(edited.add(destination)) { "移动目标与其他操作冲突：$destination" }
                    require(!File(workspaceRoot, destination).exists()) {
                        "移动目标已存在：$destination"
                    }
                }
                var searchFrom = 0
                var changed = false
                while (cursor < lines.lastIndex && !lines[cursor].startsWith("*** ")) {
                    var anchor: String? = null
                    if (lines[cursor].startsWith("@@")) {
                        anchor = lines[cursor++].removePrefix("@@").trim().takeIf(String::isNotEmpty)
                    }
                    val chunk = mutableListOf<String>()
                    var atEnd = false
                    while (cursor < lines.lastIndex &&
                        (!lines[cursor].startsWith("*** ") || lines[cursor] == "*** End of File") &&
                        !lines[cursor].startsWith("@@")) {
                        val line = lines[cursor++]
                        if (line == "*** End of File") {
                            atEnd = true
                            break
                        }
                        require(line.isNotEmpty() && line[0] in setOf(' ', '+', '-')) {
                            "修改行必须以空格、+ 或 - 开始"
                        }
                        chunk += line
                    }
                    require(chunk.isNotEmpty() && chunk.any { it[0] == '+' || it[0] == '-' }) {
                        "Codex 修改块需要真实新增或删除行"
                    }
                    if (anchor != null) {
                        val matches = (searchFrom until current.size).filter { current[it] == anchor }
                        require(matches.size == 1) { "修改锚点不唯一或不存在：$anchor" }
                        searchFrom = matches.single() + 1
                    }
                    val old = chunk.filter { it[0] != '+' }.map { it.substring(1) }
                    val new = chunk.filter { it[0] != '-' }.map { it.substring(1) }
                    val locations = if (old.isEmpty()) {
                        listOf(if (atEnd) current.size else searchFrom)
                    } else (searchFrom..(current.size - old.size).coerceAtLeast(searchFrom - 1))
                        .filter { start ->
                            start + old.size <= current.size &&
                                current.subList(start, start + old.size) == old
                        }
                    require(locations.size == 1) {
                        "修改上下文不唯一或不存在：$name；请提供更完整上下文"
                    }
                    val start = locations.single()
                    if (atEnd) require(start + old.size == current.size) {
                        "*** End of File 必须匹配文件尾部"
                    }
                    repeat(old.size) { current.removeAt(start) }
                    current.addAll(start, new)
                    searchFrom = start + new.size
                    changed = true
                }
                require(changed) { "更新文件必须包含修改块" }
                val after = current.joinToString("\n") + if (before.endsWith('\n')) "\n" else ""
                if (destination == null) diff(name, before, after)
                else {
                    diff(name, before, null)
                    diff(destination, null, after)
                }
            }
        }
    }
    require(result.isNotEmpty()) { "补丁没有操作" }
    return result.toString()
}
