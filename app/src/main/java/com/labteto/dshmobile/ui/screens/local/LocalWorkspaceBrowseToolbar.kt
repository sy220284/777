package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
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
import com.shenyan.designsystem.components.SyIconButton

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
                onClick = onImport, enabled = !importing,
                size = SyButtonSize.Small, variant = SyButtonVariant.Outline,
                icon = FeatherIcons.Plus, modifier = Modifier.testTag("work_import_phone_file"),
            )
        }
        if (onProjects != null || onReview != null || onDownloads != null || onSelect != null) {
            Box {
                SyIconButton(icon = FeatherIcons.MoreVertical,
                    contentDescription = "更多文件操作", onClick = { expanded = true },
                    modifier = Modifier.testTag("work_files_more"))
                SyPopupMenu(expanded = expanded, onDismiss = { expanded = false }) {
                    @Composable fun Entry(label: String, tag: String, action: (() -> Unit)?, available: Boolean = true) {
                        if (action != null) DropdownMenuItem(
                            text = { Text(label) }, enabled = available,
                            modifier = Modifier.testTag(tag),
                            onClick = { expanded = false; action() },
                        )
                    }
                    Entry("选择文件", "work_files_select", onSelect)
                    Entry("管理项目", "work_projects_open", onProjects, enabled)
                    Entry("代码审查", "work_git_review_entry", onReview, enabled)
                    Entry("打开下载", "work_downloads_open", onDownloads)
                }
            }
        }
    }
}
