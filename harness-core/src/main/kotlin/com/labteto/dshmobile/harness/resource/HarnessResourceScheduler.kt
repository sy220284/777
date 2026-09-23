package com.labteto.dshmobile.harness.resource

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class HarnessResourceKind {
    MODEL_REQUEST,
    AGENT,
}

enum class HarnessResourcePressure {
    LOW,
    MEDIUM,
    HIGH,
}

data class HarnessResourceBudget(
    val maxModelRequests: Int,
    val maxAgents: Int,
) {
    init {
        require(maxModelRequests in 1..16) { "模型并发预算必须在 1..16 之间" }
        require(maxAgents in 1..32) { "智能体并发预算必须在 1..32 之间" }
    }
}

data class HarnessResourceSnapshot(
    val activeModelRequests: Int,
    val activeAgents: Int,
    val budget: HarnessResourceBudget,
) {
    val pressure: HarnessResourcePressure
        get() {
            val modelRatio = activeModelRequests.toDouble() / budget.maxModelRequests
            val agentRatio = activeAgents.toDouble() / budget.maxAgents
            val ratio = maxOf(modelRatio, agentRatio)
            return when {
                ratio >= 1.0 -> HarnessResourcePressure.HIGH
                ratio >= 0.6 -> HarnessResourcePressure.MEDIUM
                else -> HarnessResourcePressure.LOW
            }
        }

    val availableAgentSlots: Int
        get() = (budget.maxAgents - activeAgents).coerceAtLeast(0)
}

/**
 * Shared bounded scheduler for scarce Harness execution resources.
 *
 * Callers wait for a permit instead of independently launching unbounded model requests or agents.
 * The snapshot is intentionally cheap so Android can project pressure into UI and event logs.
 */
class HarnessResourceScheduler(
    val budget: HarnessResourceBudget,
    private val onChanged: (HarnessResourceSnapshot) -> Unit = { },
) {
    private val modelSemaphore = Semaphore(budget.maxModelRequests)
    private val agentSemaphore = Semaphore(budget.maxAgents)
    private val activeModels = AtomicInteger()
    private val activeAgents = AtomicInteger()

    suspend fun <T> withResource(
        kind: HarnessResourceKind,
        block: suspend () -> T,
    ): T {
        val semaphore = when (kind) {
            HarnessResourceKind.MODEL_REQUEST -> modelSemaphore
            HarnessResourceKind.AGENT -> agentSemaphore
        }
        return semaphore.withPermit {
            increment(kind)
            try {
                block()
            } finally {
                decrement(kind)
            }
        }
    }

    fun snapshot(): HarnessResourceSnapshot = HarnessResourceSnapshot(
        activeModelRequests = activeModels.get(),
        activeAgents = activeAgents.get(),
        budget = budget,
    )

    private fun increment(kind: HarnessResourceKind) {
        when (kind) {
            HarnessResourceKind.MODEL_REQUEST -> activeModels.incrementAndGet()
            HarnessResourceKind.AGENT -> activeAgents.incrementAndGet()
        }
        onChanged(snapshot())
    }

    private fun decrement(kind: HarnessResourceKind) {
        when (kind) {
            HarnessResourceKind.MODEL_REQUEST -> activeModels.decrementAndGet()
            HarnessResourceKind.AGENT -> activeAgents.decrementAndGet()
        }
        onChanged(snapshot())
    }
}
