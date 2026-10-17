package com.labteto.dshmobile.harness.workflow

/**
 * Common DAG topology for Work execution and the persisted Agent Team task reducer.
 *
 * A missing node is never silently admitted by TaskGraphExecutor; callers validate
 * references separately. Completed/deleted domain nodes may be excluded by their
 * owning feature before this pure cycle check.
 */
object TaskGraphTopology {
    fun containsCycle(dependencies: Map<String, List<String>>): Boolean {
        val visiting = HashSet<String>()
        val visited = HashSet<String>()
        fun visit(id: String): Boolean {
            if (id in visited) return false
            if (!visiting.add(id)) return true
            for (dependency in dependencies[id].orEmpty()) {
                if (dependency in dependencies && visit(dependency)) return true
            }
            visiting.remove(id)
            visited.add(id)
            return false
        }
        return dependencies.keys.any(::visit)
    }
}
