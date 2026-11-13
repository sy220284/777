package com.labteto.dshmobile.device

import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime authority for virtual displays. Tool calls may access only displays
 * created by the tool capability; Agent-owned displays are intentionally absent
 * from the tool catalog even when both share the same Android display manager.
 *
 * This tracks actual Controller-created display IDs, not caller-supplied names.
 */
internal class VirtualDisplayOwnership {
    private enum class Scope { TOOL, AGENT }

    private val owners = ConcurrentHashMap<String, Scope>()

    fun registerTool(id: String) = register(id, Scope.TOOL)

    fun registerAgent(id: String) = register(id, Scope.AGENT)

    private fun register(id: String, scope: Scope) {
        require(id.isNotBlank()) { "虚拟屏标识不能为空" }
        check(owners.putIfAbsent(id, scope) == null) { "虚拟屏标识重复：$id" }
    }

    fun requireTool(id: String) {
        require(owners[id] == Scope.TOOL) { "虚拟屏不属于工具执行作用域：$id" }
    }

    fun isTool(id: String): Boolean = owners[id] == Scope.TOOL

    fun isAgent(id: String): Boolean = owners[id] == Scope.AGENT

    fun releaseTool(id: String): Boolean = owners.remove(id, Scope.TOOL)

    fun releaseAgent(id: String): Boolean = owners.remove(id, Scope.AGENT)

    fun clear() = owners.clear()
}
