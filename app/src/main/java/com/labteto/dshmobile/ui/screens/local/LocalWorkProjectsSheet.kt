package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.local.work.LocalWorkProjectSnapshot
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.labteto.dshmobile.ui.theme.DsType
import com.shenyan.designsystem.components.SyTextField
import com.shenyan.designsystem.foundation.SyMetric
import kotlinx.coroutines.flow.StateFlow

/** Project actions are Work-owned and session-bound; Files remains the owner of actual documents. */
internal data class LocalWorkProjectsUiActions(
    val snapshots: StateFlow<LocalWorkProjectSnapshot>,
    val create: (String, String) -> Unit,
    val importExisting: (String, String, String?) -> Unit,
    val rename: (String, String) -> Boolean,
    val update: (String, String, String) -> Boolean,
    val changeRoot: (String, String) -> Boolean,
    val moveDirectory: (String, String, String) -> Boolean,
    val move: (String, Int) -> Boolean,
    val delete: (String) -> Boolean,
    val assignCurrentSession: (String, String?) -> Boolean,
)

/**
 * Two levels inside the existing Workspace page: projects list and focused project editor.
 * Avoids technical forms on launch; all operations use the same persistent Work catalog.
 */
@Composable
internal fun LocalWorkProjectsSheet(
    actions: LocalWorkProjectsUiActions,
    sessionId: String,
    currentFolder: String,
    onOpenProject: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val snapshot by actions.snapshots.collectAsStateWithLifecycle()
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    var editorId by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf(".") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }
    var moveDestination by remember { mutableStateOf("") }
    var confirmingMove by remember { mutableStateOf(false) }

    fun openCreate(useCurrent: Boolean) {
        creating = true
        importing = useCurrent
        editorId = null
        name = ""
        folder = if (useCurrent) currentFolder else ""
        moveDestination = ""
        error = null
    }

    val editing = snapshot.projects.firstOrNull { it.id == editorId }
    SyResponsiveSheet(
        title = if (creating) (if (importing) "导入现有文件夹" else "新建项目")
            else if (editing != null) "编辑项目" else "项目管理",
        subtitle = if (creating) "建立项目索引，保留原有文件和 Git 历史"
            else if (editing != null) "修改项目资料与当前会话归属"
            else "集中管理工作、文件夹与相关会话",
        onDismiss = {
            if (creating || editorId != null) {
                creating = false
                editorId = null
                error = null
            } else onDismiss()
        },
        scrollable = true,
    ) {
        error?.let { Text(it, color = colors.dangerInk, style = DsType.caption11) }
        if (creating || editing != null) {
            SyTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("项目名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("work_project_name"),
            )
            if (!creating || importing) {
                SyTextField(
                    value = folder,
                    onValueChange = { folder = it },
                    label = { Text("关联已有文件夹") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("重新关联只修改项目指向；下面的“移动实际文件夹”会迁移文件及 Git 历史。",
                    style = DsType.caption11, color = colors.textSecondary)
                SyButton(
                    text = "使用当前目录",
                    onClick = { folder = currentFolder },
                    variant = SyButtonVariant.Ghost,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (editing != null && editing.root != ".") {
                    SyTextField(
                        value = moveDestination,
                        onValueChange = { moveDestination = it },
                        label = { Text("移动到新的工作区相对目录") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("work_project_move_destination"),
                    )
                    SyButton(
                        text = "移动实际文件夹",
                        enabled = moveDestination.isNotBlank() &&
                            moveDestination.trim() != editing.root,
                        onClick = { confirmingMove = true },
                        variant = SyButtonVariant.Outline,
                        modifier = Modifier.fillMaxWidth().testTag("work_project_move"),
                    )
                    Text(
                        "目标目录需尚不存在，上级文件夹已创建；文件、子目录和 Git 历史将一起移动。",
                        style = DsType.caption11, color = colors.textSecondary,
                    )
                }
            } else {
                Text("将自动创建独立的本地项目文件夹，直接保存即可。",
                    style = DsType.caption11, color = colors.textSecondary)
            }
            SyButton(
                text = if (creating) "保存项目" else "保存修改",
                enabled = name.trim().isNotEmpty() && ((!creating || importing) && folder.trim().isNotEmpty() || creating && !importing),
                onClick = {
                    try {
                        if (creating) {
                            if (importing) actions.importExisting(name, folder, sessionId.takeIf(String::isNotBlank))
                            else actions.create(name, folder)
                        } else if (editing != null) {
                            actions.update(editing.id, name, folder)
                        }
                        editorId = null
                        creating = false
                        error = null
                    } catch (failure: Exception) {
                        error = failure.message ?: "项目保存失败"
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("work_project_save"),
            )
            SyButton(
                text = "返回项目列表",
                onClick = { editorId = null; creating = false; error = null },
                variant = SyButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            SyButton(text = "新建项目", onClick = { openCreate(false) },
                modifier = Modifier.fillMaxWidth().testTag("work_project_create"))
            if (currentFolder != ".") SyButton(
                text = "将当前文件夹登记为项目",
                onClick = { openCreate(true) },
                variant = SyButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth(),
            )
            if (snapshot.projects.isEmpty()) {
                Text("尚无项目。新建一个项目后即可关联工作会话与文件夹。",
                    style = DsType.small13, color = colors.textSecondary)
            }
            snapshot.projects.forEachIndexed { index, project ->
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = SyMetric.gapSm),
                    verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
                ) {
                    val associated = snapshot.sessionProjects[sessionId] == project.id
                    Text(project.name, style = DsType.base16Strong, color = colors.text)
                    Text("文件夹：" + project.root +
                        if (associated) " · 当前会话已关联" else "",
                        style = DsType.caption11, color = colors.textSecondary)
                    SyButton(
                        text = "打开项目文件",
                        onClick = { onOpenProject(project.root) },
                        size = SyButtonSize.Small,
                        variant = SyButtonVariant.Outline,
                        modifier = Modifier.fillMaxWidth().testTag("work_project_open"),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(SyMetric.gapSm)) {
                        SyButton(
                            text = if (associated) "移出会话" else "关联会话",
                            onClick = {
                                try {
                                    actions.assignCurrentSession(sessionId, if (associated) null else project.id)
                                    error = null
                                } catch (failure: Exception) {
                                    error = failure.message ?: "会话关联失败"
                                }
                            },
                            enabled = sessionId.isNotBlank(),
                            variant = SyButtonVariant.Outline,
                            size = SyButtonSize.Small,
                        )
                        SyButton(
                            text = "编辑",
                            onClick = {
                                editorId = project.id
                                name = project.name
                                folder = project.root
                                moveDestination = ""
                                error = null
                            },
                            variant = SyButtonVariant.Ghost,
                            size = SyButtonSize.Small,
                        )
                        if (index > 0) SyButton(
                            text = "上移", onClick = { try {
                                actions.move(project.id, index - 1)
                                error = null
                            } catch (failure: Exception) { error = failure.message ?: "排序失败" } },
                            variant = SyButtonVariant.Ghost, size = SyButtonSize.Small,
                        )
                        SyButton(
                            text = "移除",
                            onClick = { confirmDeleteId = project.id },
                            variant = SyButtonVariant.Ghost,
                            size = SyButtonSize.Small,
                        )
                    }
                }
            }
        }
    }
    if (confirmingMove && editing != null) {
        val destination = moveDestination.trim()
        ConfirmDialog(
            title = "移动实际项目文件夹",
            body = "将“" + editing.root + "”的全部文件移动到“" + destination +
                "”。项目关联会同步更新，原路径将不再存在。",
            confirmLabel = "确认移动",
            onDismiss = { confirmingMove = false },
            onConfirm = {
                try {
                    actions.moveDirectory(editing.id, editing.root, destination)
                    folder = destination
                    moveDestination = ""
                    error = null
                } catch (failure: Exception) {
                    error = failure.message ?: "项目文件夹移动失败"
                }
                confirmingMove = false
            },
        )
    }
    if (confirmDeleteId != null) {
        ConfirmDialog(
            title = "移除项目",
            body = "仅删除项目登记和会话关联，工作文件、Git 仓库与会话历史都会保留。",
            confirmLabel = "确认移除",
            onDismiss = { confirmDeleteId = null },
            onConfirm = {
                val id = confirmDeleteId
                if (id != null) {
                    try {
                        actions.delete(id)
                        if (editorId == id) editorId = null
                        error = null
                    } catch (failure: Exception) {
                        error = failure.message ?: "项目移除失败"
                    }
                }
                confirmDeleteId = null
            },
        )
    }
}
