package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.shenyan.designsystem.foundation.LocalSyColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyPopupMenu

/** Browsing retains its space; secondary operations share one discoverable menu. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalWorkspaceBrowseToolbar(
    importing: Boolean,
    enabled: Boolean,
    onImport: (() -> Unit)?,
    onProjects: (() -> Unit)?,
    onReview: (() -> Unit)?,
    onDownloads: (() -> Unit)?,
    onSelect: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onImport != null) {
            SyButton(
                text = if (importing) "导入中…" else "导入手机文件",
                onClick = onImport, enabled = enabled && !importing,
                size = SyButtonSize.Small, variant = SyButtonVariant.Outline,
                icon = FeatherIcons.Plus, modifier = Modifier.testTag("work_import_phone_file"),
            )
        }
        if (onSelect != null) {
            SyButton(
                text = "选择文件", onClick = onSelect, enabled = enabled,
                size = SyButtonSize.Small, variant = SyButtonVariant.Ghost,
                modifier = Modifier.testTag("work_files_select"),
            )
        }
        if (onProjects != null || onReview != null || onDownloads != null) {
            Box {
                SyButton(
                    text = "文件操作", icon = FeatherIcons.MoreVertical,
                    onClick = { expanded = true }, enabled = enabled,
                    size = SyButtonSize.Small, variant = SyButtonVariant.Ghost,
                    modifier = Modifier.testTag("work_files_more"),
                )
                SyPopupMenu(expanded = expanded, onDismiss = { expanded = false }) {
                    @Composable fun Entry(label: String, tag: String, action: (() -> Unit)?, available: Boolean = true, hint: String? = null) {
                        if (action != null) DropdownMenuItem(
                            text = { Column {
                                Text(label)
                                if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall,
                                    color = LocalSyColors.current.textSecondary)
                            } }, enabled = available,
                            modifier = Modifier.testTag(tag),
                            onClick = { expanded = false; action() },
                        )
                    }
                    Entry("管理项目", "work_projects_open", onProjects, enabled)
                    Entry("代码审查", "work_git_review_entry", onReview, enabled)
                    Entry("打开下载", "work_downloads_open", onDownloads, hint = "完成的成果：手机存储/Download/神言神语")
                }
            }
        }
    }
}
