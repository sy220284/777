package com.labteto.dshmobile.local.tools

/**
 * One run's model-visible optional-tool selection. The selected names remain the only
 * capability fact; exactFocus only records how that same set was chosen.
 *
 * The live ToolRegistry and per-call permissions remain authoritative for execution.
 */
internal class LocalRunToolSelection : LinkedHashSet<String>() {
    @Volatile
    var exactFocus: Boolean = false
        private set

    /** Explicit select: wins over automatic pins and task hints until the task changes. */
    @Synchronized fun markExactFocus() {
        pendingForNextStep.clear()
        awaitingCapacityCheck.clear()
        exactFocus = true
    }

    /** A model attempt at a registered but unexposed optional tool may prepare a later step. */
    private val pendingForNextStep = linkedSetOf<String>()
    private val awaitingCapacityCheck = linkedSetOf<String>()

    @Synchronized fun hasBudgetOmittedRecoveryCandidate(
        omittedReasons: Map<String, String>,
    ): Boolean = awaitingCapacityCheck.any { name ->
        omittedReasons[name]?.let { it.contains("Token") || it.contains("预算") } == true
    }

    @Synchronized fun finishCapacityCheck() {
        awaitingCapacityCheck.clear()
        pendingForNextStep.clear()
    }

    @Synchronized fun requestForNextStep(name: String): Boolean {
        if (exactFocus && name !in this) return false
        pendingForNextStep += name
        awaitingCapacityCheck += name
        return true
    }

    /** Reapply intent after automatic pins/team tools have rearranged the optional catalog. */
    @Synchronized fun applyRequestedPriority() {
        if (pendingForNextStep.isEmpty()) return
        val promoted = pendingForNextStep.toList()
        val remaining = toList().filterNot { it in pendingForNextStep }
        super.clear()
        super.addAll(promoted)
        super.addAll(remaining)
        // Keep priority until the request's final, post-compaction tool surface
        // is frozen. Re-projection may otherwise restore pinned/default tools ahead.
    }

    /** The task cursor and its model-visible selection share this run's ownership. */
    @Volatile private var lastTask: String? = null

    @Synchronized fun isNewTask(task: String): Boolean {
        if (task == lastTask) return false
        lastTask = task
        return true
    }

    @Synchronized fun resetTask() {
        lastTask = null
        pendingForNextStep.clear()
        awaitingCapacityCheck.clear()
    }

    /** An explicit CORE-only select is a valid empty optional set, not an unprepared task. */
    fun isPreparedForContinuation(): Boolean = exactFocus || isNotEmpty()

    /** A new real user task may use normal relevance/pinned-tool admission again. */
    @Synchronized fun beginNewTask() {
        exactFocus = false
        pendingForNextStep.clear()
        awaitingCapacityCheck.clear()
    }

    override fun clear() {
        super.clear()
        exactFocus = false
        // Internal prioritization also clears and re-adds the selected set. Keep
        // a pending next-step request until it is applied, replaced by exact select,
        // or reset for a new task.
    }
}
