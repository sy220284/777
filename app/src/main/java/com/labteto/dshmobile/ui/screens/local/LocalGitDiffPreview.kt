package com.labteto.dshmobile.ui.screens.local

import java.io.ByteArrayOutputStream
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.theme.DsType
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.SyMetric

/** UI-only representation: Git remains the single source for patch and revision metadata. */
internal enum class GitDiffLineKind { META, HUNK, ADD, REMOVE, CONTEXT }
internal data class GitDiffPreviewLine(val content: String, val kind: GitDiffLineKind)
internal data class GitDiffPreviewFile(
    val path: String,
    val added: Int,
    val removed: Int,
    val lines: List<GitDiffPreviewLine>,
)
internal data class GitDiffPreviewSummary(
    val files: List<GitDiffPreviewFile>,
    val omittedLines: Int,
    val omittedFiles: Int,
)

/** Git quotes paths with spaces/non-ASCII using C escapes and octal UTF-8 bytes. */
internal fun gitDiffTargetPath(header: String): String? {
    if (!header.startsWith("diff --git ")) return null
    val quoted = header.lastIndexOf(" \"b/")
    if (quoted >= 0 && header.endsWith('"')) {
        val encoded = header.substring(quoted + 4, header.length - 1)
        val bytes = ByteArrayOutputStream(encoded.length)
        var index = 0
        while (index < encoded.length) {
            if (encoded[index] != '\\') {
                val nextEscape = encoded.indexOf('\\', index).let {
                    if (it < 0) encoded.length else it
                }
                bytes.write(encoded.substring(index, nextEscape).toByteArray(Charsets.UTF_8))
                index = nextEscape
                continue
            }
            if (index + 3 < encoded.length &&
                encoded.substring(index + 1, index + 4).all { it in '0'..'7' }) {
                bytes.write(encoded.substring(index + 1, index + 4).toInt(8))
                index += 4
                continue
            }
            val character = when (encoded.getOrNull(index + 1)) {
                'a' -> '\u0007'
                'b' -> '\b'
                't' -> '\t'
                'n' -> '\n'
                'v' -> '\u000B'
                'f' -> '\u000C'
                'r' -> '\r'
                '\\' -> '\\'
                '"' -> '"'
                null -> '\\'
                else -> encoded[index + 1]
            }
            bytes.write(character.toString().toByteArray(Charsets.UTF_8))
            index += if (index + 1 < encoded.length) 2 else 1
        }
        return bytes.toByteArray().toString(Charsets.UTF_8).takeIf(String::isNotEmpty)
    }
    return header.substringAfterLast(" b/", missingDelimiterValue = "")
        .takeIf(String::isNotEmpty)
}

/** Parse Git unified diff as read-only data, with explicit bounds on UI rows. */
internal fun parseGitDiffPreview(
    patch: String,
    maxFiles: Int = 30,
    maxRows: Int = 360,
): GitDiffPreviewSummary {
    require(maxFiles in 1..100 && maxRows in 1..1_000)
    data class Draft(val path: String, val rows: MutableList<GitDiffPreviewLine> = mutableListOf(),
        var added: Int = 0, var removed: Int = 0)
    val drafts = mutableListOf<Draft>()
    var active: Draft? = null
    var totalRows = 0
    var omitted = 0
    var hiddenFiles = 0
    for (line in patch.lineSequence()) {
        if (line.startsWith("diff --git ")) {
            val target = gitDiffTargetPath(line)
            if (drafts.size < maxFiles) {
                active = Draft(target ?: "未知文件")
                drafts.add(active)
            } else {
                active = null
                hiddenFiles++
            }
            continue
        }
        // A patch received from an external Git producer can start with a hunk.
        if (active == null && drafts.isEmpty()) {
            active = Draft("差异内容")
            drafts.add(active)
        }
        val row = when {
            line.startsWith("@@") -> GitDiffLineKind.HUNK
            line.startsWith("+++") || line.startsWith("---") ||
                line.startsWith("index ") || line.startsWith("rename ") ||
                line.startsWith("new file ") || line.startsWith("deleted file ") ||
                line.startsWith("Binary files ") -> GitDiffLineKind.META
            line.startsWith("+") -> GitDiffLineKind.ADD
            line.startsWith("-") -> GitDiffLineKind.REMOVE
            else -> GitDiffLineKind.CONTEXT
        }
        val section = active ?: continue
        if (row == GitDiffLineKind.ADD) section.added++
        if (row == GitDiffLineKind.REMOVE) section.removed++
        totalRows++
        if (totalRows <= maxRows) section.rows.add(GitDiffPreviewLine(line, row))
        else omitted++
    }
    return GitDiffPreviewSummary(
        files = drafts.map { GitDiffPreviewFile(it.path, it.added, it.removed, it.rows) },
        omittedLines = omitted,
        omittedFiles = hiddenFiles,
    )
}

/** Compact, readable code-diff cards; full raw patch is still available from the parent copy action. */
@Composable
internal fun LocalGitDiffPreview(patch: String) {
    val summary = remember(patch) { parseGitDiffPreview(patch) }
    val colors = LocalSyColors.current
    var expanded by remember(patch) { mutableStateOf(emptySet<Int>()) }
    Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm)) {
        Text("变更文件 · ${summary.files.size}" +
            if (summary.omittedFiles > 0) "（还有 ${summary.omittedFiles} 个未展示）" else "",
            style = DsType.small13Strong, color = colors.textSecondary)
        summary.files.forEachIndexed { index, file ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(file.path, style = DsType.small13Strong, color = colors.text,
                        modifier = Modifier.weight(1f))
                    Text("+${file.added}  −${file.removed}",
                        style = DsType.caption11, color = colors.textSecondary)
                }
                val visibleCount = if (index in expanded) 120 else 20
                SelectionContainer {
                    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        file.lines.take(visibleCount).forEach { line ->
                            val tint = when (line.kind) {
                                GitDiffLineKind.ADD -> colors.successInk
                                GitDiffLineKind.REMOVE -> colors.dangerInk
                                GitDiffLineKind.HUNK -> colors.textSecondary
                                else -> colors.text
                            }
                            Text(line.content, color = tint, style = DsType.caption11,
                                fontFamily = FontFamily.Monospace, softWrap = false)
                        }
                    }
                }
                if (file.lines.size > 20) {
                    SyButton(
                        text = if (index in expanded) "收起此文件" else "展开此文件",
                        onClick = {
                            expanded = if (index in expanded) expanded - index
                                else expanded + index
                        },
                        size = SyButtonSize.Small, variant = SyButtonVariant.Ghost,
                    )
                }
            }
        }
        if (summary.omittedLines > 0 || summary.files.any { it.lines.size > 120 }) {
            Text("预览按文件与行数限制展示，完整内容请使用“复制差异内容”。",
                style = DsType.caption11, color = colors.textSecondary)
        }
    }
}
