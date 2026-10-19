package com.labteto.dshmobile.ui.screens.tools

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.UiGitHubEntry
import com.labteto.dshmobile.uicontract.UiGitHubRepo
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.labteto.dshmobile.ui.theme.DsType
import com.shenyan.designsystem.foundation.LocalSyColors

/** Read-only links: a file always opens on github.com; writes still require Work approval. */
internal fun githubFileWebUrl(repo: String, branch: String, path: String): String =
    "https://github.com/" + repo.split('/').joinToString("/") { Uri.encode(it) } +
        "/blob/" + Uri.encode(branch) + "/" +
        path.split('/').joinToString("/") { Uri.encode(it) }

/** Read-only repository navigation. GitHub writes still go through the existing approval path. */
internal data class GitHubBrowseState(
    val repositories: List<UiGitHubRepo> = emptyList(),
    val entries: List<UiGitHubEntry> = emptyList(),
    val selectedRepository: String? = null,
    val currentDirectory: String = "",
    val loading: Boolean = false,
    val error: String? = null,
)

@Composable
internal fun GitHubRepositoryBrowserSheet(
    state: GitHubBrowseState,
    onBack: () -> Unit,
    onOpenRepository: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenFile: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalSyColors.current
    val clipboard = LocalClipboardManager.current
    val selectedRepo = state.repositories.firstOrNull { it.fullName == state.selectedRepository }
    SyResponsiveSheet(
        title = "GitHub 仓库",
        subtitle = state.selectedRepository?.let { repo ->
            if (state.currentDirectory.isEmpty()) repo else "$repo / ${state.currentDirectory}"
        } ?: "只读查看已连接账户的仓库与目录",
        onDismiss = onDismiss,
    ) {
        SyButton(
            text = "刷新仓库",
            onClick = onRefresh,
            enabled = !state.loading,
            variant = SyButtonVariant.Ghost,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.selectedRepository != null) {
            SyButton(
                text = if (state.currentDirectory.isEmpty()) "返回仓库列表" else "返回上级目录",
                onClick = onBack,
                enabled = !state.loading,
                variant = SyButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (selectedRepo != null) {
            SyButton(
                text = "复制仓库地址",
                onClick = { clipboard.setText(AnnotatedString("https://github.com/${selectedRepo.fullName}")) },
                variant = SyButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.loading) {
            Text("正在读取…", color = colors.textSecondary)
        }
        state.error?.let { Text(it, color = colors.textSecondary) }
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.selectedRepository == null) {
                if (!state.loading && state.error == null && state.repositories.isEmpty()) {
                    Text("当前账户没有可浏览的仓库", color = colors.textSecondary)
                }
                state.repositories.forEach { repo ->
                    SyButton(
                        text = repo.fullName + if (repo.privateRepo) " · 私有" else "",
                        onClick = { onOpenRepository(repo.fullName) },
                        modifier = Modifier.fillMaxWidth(),
                        variant = SyButtonVariant.Ghost,
                        enabled = !state.loading,
                    )
                    if (repo.description.isNotBlank()) {
                        Text(repo.description, style = DsType.small13, color = colors.textSecondary,
                            modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
            } else {
                if (!state.loading && state.error == null && state.entries.isEmpty()) {
                    Text("该目录没有文件", color = colors.textSecondary)
                }
                state.entries.forEach { entry ->
                    if (entry.directory) {
                        SyButton(
                            text = "目录 · ${entry.name}",
                            onClick = { onOpenFolder(entry.path) },
                            modifier = Modifier.fillMaxWidth(),
                            variant = SyButtonVariant.Ghost,
                            enabled = !state.loading,
                        )
                    } else {
                        SyButton(
                            text = "文件 · ${entry.name}",
                            onClick = {
                                val repo = selectedRepo ?: return@SyButton
                                onOpenFile(githubFileWebUrl(repo.fullName, repo.defaultBranch, entry.path))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            variant = SyButtonVariant.Ghost,
                            enabled = !state.loading && selectedRepo != null,
                        )
                    }
                }
            }
        }
    }
}
