package com.labteto.dshmobile.local.project

import android.content.Context
import com.labteto.dshmobile.local.persistence.LocalHarnessPreferences
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class LocalProject(
    val id: String,
    val name: String,
    val instructions: String = "",
)

@Serializable
internal data class LocalProjectCatalogState(
    val projects: List<LocalProject> = listOf(LocalProject(DEFAULT_PROJECT_ID, "默认项目")),
    val activeId: String = DEFAULT_PROJECT_ID,
)

internal const val DEFAULT_PROJECT_ID = "local-workspace"
private const val KEY_PROJECT_CATALOG = "local_projects_v1"
private const val MAX_PROJECTS = 64
private const val MAX_PROJECT_NAME_CHARS = 80
private const val MAX_PROJECT_INSTRUCTIONS_CHARS = 8_000

/**
 * ProjectFeature owns only project identity, labels and instructions. Session and Memory remain
 * the authority for their own records and are never copied into this catalog.
 */
@Singleton
internal class LocalProjectFeatureRuntime @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) : ProjectContextPort {
    private val preferences = LocalHarnessPreferences.from(context)
    private val initialCatalog = runCatching { read() }
    private val mutableCatalog = MutableStateFlow(initialCatalog.getOrDefault(LocalProjectCatalogState()))
    private val mutableRecoveryNotice = MutableStateFlow<String?>(
        initialCatalog.exceptionOrNull()?.let { "项目目录无法读取。修改操作已暂停，可备份原始数据后恢复默认目录。" },
    )
    val catalog: StateFlow<LocalProjectCatalogState> = mutableCatalog.asStateFlow()
    val recoveryNotice: StateFlow<String?> = mutableRecoveryNotice.asStateFlow()

    override fun activeProjectId(): String {
        check(mutableRecoveryNotice.value == null) { "项目目录无法读取，请先到项目页备份并恢复，任务尚未执行" }
        return mutableCatalog.value.activeId
    }

    override fun instructionsFor(projectId: String?): String =
        resolveLocalProjectInstructions(mutableCatalog.value, mutableRecoveryNotice.value, projectId)

    @Synchronized
    fun create(name: String): String {
        val normalized = validateName(name)
        val current = mutableCatalog.value
        require(current.projects.size < MAX_PROJECTS) { "项目数量达到上限" }
        val id = UUID.randomUUID().toString()
        save(current.copy(
            projects = current.projects + LocalProject(id, normalized),
            activeId = id,
        ))
        return id
    }

    @Synchronized
    fun select(id: String) {
        val current = mutableCatalog.value
        require(current.projects.any { it.id == id }) { "项目不存在" }
        save(current.copy(activeId = id))
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val normalized = validateName(name)
        val current = mutableCatalog.value
        require(current.projects.any { it.id == id }) { "项目不存在" }
        save(current.copy(projects = current.projects.map { if (it.id == id) it.copy(name = normalized) else it }))
    }

    @Synchronized
    fun updateInstructions(id: String, instructions: String) {
        require(instructions.length <= MAX_PROJECT_INSTRUCTIONS_CHARS) { "项目指令过长" }
        val current = mutableCatalog.value
        require(current.projects.any { it.id == id }) { "项目不存在" }
        save(current.copy(projects = current.projects.map {
            if (it.id == id) it.copy(instructions = instructions) else it
        }))
    }

    private fun read(): LocalProjectCatalogState =
        preferences.getString(KEY_PROJECT_CATALOG, null)?.let { decodeLocalProjectCatalog(it, json) }
            ?: LocalProjectCatalogState()

    /** Keep the invalid original untouched until explicitly backed up and reset. */
    @Synchronized
    fun backupAndResetCatalog() {
        check(mutableRecoveryNotice.value != null) { "项目目录正常，无需恢复" }
        val original = preferences.all[KEY_PROJECT_CATALOG]?.toString()
            ?: error("原始目录不可读取，恢复已取消")
        val backupKey = KEY_PROJECT_CATALOG + "_backup_" + UUID.randomUUID()
        check(preferences.edit().putString(backupKey, original).remove(KEY_PROJECT_CATALOG).commit()) {
            "备份失败，原始项目目录已保留"
        }
        mutableCatalog.value = LocalProjectCatalogState()
        mutableRecoveryNotice.value = null
    }

    private fun save(next: LocalProjectCatalogState) {
        check(mutableRecoveryNotice.value == null) { "项目目录需先备份恢复" }
        check(preferences.edit().putString(KEY_PROJECT_CATALOG, json.encodeToString(next)).commit()) {
            "项目保存失败"
        }
        mutableCatalog.value = next
    }

    private fun validateName(name: String): String = name.trim().also {
        require(it.isNotEmpty() && it.length <= MAX_PROJECT_NAME_CHARS) { "项目名称长度无效" }
    }
}

/** An unbound conversation may proceed; a damaged bound project must never become empty rules. */
internal fun resolveLocalProjectInstructions(
    catalog: LocalProjectCatalogState,
    recoveryNotice: String?,
    projectId: String?,
): String {
    if (projectId.isNullOrBlank()) return ""
    check(recoveryNotice == null) { "项目目录无法读取，请先到项目页备份并恢复，任务尚未执行" }
    return catalog.projects.firstOrNull { it.id == projectId }?.instructions.orEmpty()
}

/** Testable validation of every persisted project identity and instruction. */
internal fun decodeLocalProjectCatalog(raw: String, json: Json): LocalProjectCatalogState {
    val current = json.decodeFromString<LocalProjectCatalogState>(raw)
    require(current.projects.isNotEmpty() && current.projects.size <= MAX_PROJECTS &&
        current.projects.map(LocalProject::id).distinct().size == current.projects.size &&
        current.projects.any { it.id == current.activeId } &&
        current.projects.all {
            it.id.isNotBlank() && it.name.isNotBlank() &&
                it.name.length <= MAX_PROJECT_NAME_CHARS &&
                it.instructions.length <= MAX_PROJECT_INSTRUCTIONS_CHARS
        }) { "本地项目目录数据无效" }
    return current
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class LocalProjectFeatureBindings {
    @Binds abstract fun bindProjectContextPort(runtime: LocalProjectFeatureRuntime): ProjectContextPort
}
