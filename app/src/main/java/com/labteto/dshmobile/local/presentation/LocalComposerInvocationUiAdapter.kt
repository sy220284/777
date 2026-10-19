package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.tools.LocalPinnedInvocation
import com.labteto.dshmobile.local.tools.LocalPinnedInvocationStore
import com.labteto.dshmobile.uicontract.ComposerInvocationUiPort
import com.labteto.dshmobile.uicontract.ToolsUiPort
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.UiInvocationKind
import com.labteto.dshmobile.uicontract.UiInvocationOption
import com.labteto.dshmobile.uicontract.UiInvocationRef
import com.labteto.dshmobile.uicontract.UiToolRegistration
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map

@Singleton
internal class LocalComposerInvocationUiAdapter @Inject constructor(
    private val store: LocalPinnedInvocationStore,
    private val tools: ToolsUiPort,
) : ComposerInvocationUiPort {
    override val pinned = store.selected.map { it.map { item -> item.asUi() } }

    override suspend fun options(): List<UiInvocationOption> {
        val selected = store.current()
        val available = mutableListOf<UiInvocationOption>()
        if (tools.githubConfigured()) {
            available += UiInvocationOption(UiInvocationRef(UiInvocationKind.CONNECTOR, "github"),
                "GitHub", "仓库、提交与 PR")
        }
        tools.servers().filter {
            it.registration == UiToolRegistration.REGISTERED && it.tools.isNotEmpty()
        }.forEach { server ->
            available += UiInvocationOption(UiInvocationRef(UiInvocationKind.CONNECTOR, server.id),
                server.id, "MCP · ${server.tools.size} 项工具")
        }
        tools.installedSkills().forEach { skill ->
            available += UiInvocationOption(UiInvocationRef(UiInvocationKind.SKILL, skill.name),
                skill.displayName,
                if (skill.modelInvocable) skill.description else "仅手动选择时调用 · ${skill.description}")
        }
        val availableRefs = available.map { it.ref }.toSet()
        // Do not silently remove a selection when its provider is temporarily offline.
        return available + selected.map { item -> item.asUi() }
            .filterNot { it in availableRefs }
            .map { UiInvocationOption(it, it.id, "已断开或已卸载 · 可取消固定", false) }
    }

    override fun toggle(ref: UiInvocationRef): UiCommandReceipt = try {
        store.toggle(ref.asDomain())
        UiCommandReceipt.Accepted
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { UiCommandReceipt.Failed(error.message ?: "无法保存选择") }

    private fun UiInvocationRef.asDomain() = LocalPinnedInvocation(
        if (kind == UiInvocationKind.CONNECTOR) LocalPinnedInvocation.Kind.CONNECTOR
        else LocalPinnedInvocation.Kind.SKILL, id,
    )
    private fun LocalPinnedInvocation.asUi() = UiInvocationRef(
        if (kind == LocalPinnedInvocation.Kind.CONNECTOR) UiInvocationKind.CONNECTOR
        else UiInvocationKind.SKILL, id,
    )
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal abstract class LocalComposerInvocationUiBindings {
    @dagger.Binds @Singleton
    abstract fun bind(impl: LocalComposerInvocationUiAdapter): ComposerInvocationUiPort
}
