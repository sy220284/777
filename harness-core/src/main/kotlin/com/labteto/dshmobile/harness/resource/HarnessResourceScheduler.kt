package com.labteto.dshmobile.harness.resource

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Semaphore

enum class HarnessResourceKind {
    MODEL_REQUEST,
    AGENT,
    TERMINAL,
    VIRTUAL_DISPLAY,
    LANGUAGE_SERVER,
}

enum class HarnessResourcePressure {
    LOW,
    MEDIUM,
    HIGH,
}

data class HarnessResourceBudget(
    val maxModelRequests: Int,
    val maxAgents: Int,
    val maxTerminals: Int = 3,
    val maxVirtualDisplays: Int = 2,
    val maxLanguageServers: Int = 3,
) {
    init {
        require(maxModelRequests in 1..16) { "模型并发预算必须在 1..16 之间" }
        require(maxAgents in 1..32) { "智能体并发预算必须在 1..32 之间" }
        require(maxTerminals in 1..16) { "终端预算必须在 1..16 之间" }
        require(maxVirtualDisplays in 1..8) { "虚拟屏预算必须在 1..8 之间" }
        require(maxLanguageServers in 1..16) { "语言服务预算必须在 1..16 之间" }
    }

    fun limit(kind: HarnessResourceKind): Int = when (kind) {
        HarnessResourceKind.MODEL_REQUEST -> maxModelRequests
        HarnessResourceKind.AGENT -> maxAgents
        HarnessResourceKind.TERMINAL -> maxTerminals
        HarnessResourceKind.VIRTUAL_DISPLAY -> maxVirtualDisplays
        HarnessResourceKind.LANGUAGE_SERVER -> maxLanguageServers
    }
}

data class HarnessResourceLeaseInfo(
    val id: String,
    val kind: HarnessResourceKind,
    val owner: String,
    val acquiredAt: Long,
)

data class HarnessResourceSnapshot(
    val activeModelRequests: Int,
    val activeAgents: Int,
    val activeTerminals: Int,
    val activeVirtualDisplays: Int,
    val activeLanguageServers: Int,
    val budget: HarnessResourceBudget,
    val leases: List<HarnessResourceLeaseInfo> = emptyList(),
) {
    val pressure: HarnessResourcePressure
        get() {
            val ratios = listOf(
                activeModelRequests.toDouble() / budget.maxModelRequests,
                activeAgents.toDouble() / budget.maxAgents,
                activeTerminals.toDouble() / budget.maxTerminals,
                activeVirtualDisplays.toDouble() / budget.maxVirtualDisplays,
                activeLanguageServers.toDouble() / budget.maxLanguageServers,
            )
            val ratio = ratios.maxOrNull() ?: 0.0
            return when {
                ratio >= 1.0 -> HarnessResourcePressure.HIGH
                ratio >= 0.6 -> HarnessResourcePressure.MEDIUM
                else -> HarnessResourcePressure.LOW
            }
        }

    val availableAgentSlots: Int
        get() = (budget.maxAgents - activeAgents).coerceAtLeast(0)
}

class HarnessResourceLease internal constructor(
    val info: HarnessResourceLeaseInfo,
    private val releaseAction: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) releaseAction()
    }
}

/**
 * Shared bounded scheduler for scarce Harness execution resources.
 *
 * Short-lived work uses [withResource]. Long-lived resources such as terminals, virtual displays,
 * and language servers hold a [HarnessResourceLease] until the underlying resource is closed.
 */
class HarnessResourceScheduler(
    val budget: HarnessResourceBudget,
    private val onChanged: (HarnessResourceSnapshot) -> Unit = { },
) {
    private val semaphores = HarnessResourceKind.entries.associateWith { kind ->
        Semaphore(budget.limit(kind))
    }
    private val activeModels = AtomicInteger()
    private val activeAgents = AtomicInteger()
    private val activeTerminals = AtomicInteger()
    private val activeVirtualDisplays = AtomicInteger()
    private val activeLanguageServers = AtomicInteger()
    private val leases = ConcurrentHashMap<String, HarnessResourceLeaseInfo>()

    suspend fun acquire(
        kind: HarnessResourceKind,
        owner: String = kind.name.lowercase(),
    ): HarnessResourceLease {
        val semaphore = semaphores.getValue(kind)
        semaphore.acquire()
        val info = HarnessResourceLeaseInfo(
            id = "lease-" + UUID.randomUUID().toString().replace("-", "").take(16),
            kind = kind,
            owner = owner.take(160),
            acquiredAt = System.currentTimeMillis(),
        )
        increment(kind)
        leases[info.id] = info
        publish()
        return HarnessResourceLease(info) {
            leases.remove(info.id)
            decrement(kind)
            semaphore.release()
            publish()
        }
    }

    suspend fun <T> withResource(
        kind: HarnessResourceKind,
        owner: String = kind.name.lowercase(),
        block: suspend () -> T,
    ): T {
        val lease = acquire(kind, owner)
        return try {
            block()
        } finally {
            lease.close()
        }
    }

    fun snapshot(): HarnessResourceSnapshot = HarnessResourceSnapshot(
        activeModelRequests = activeModels.get(),
        activeAgents = activeAgents.get(),
        activeTerminals = activeTerminals.get(),
        activeVirtualDisplays = activeVirtualDisplays.get(),
        activeLanguageServers = activeLanguageServers.get(),
        budget = budget,
        leases = leases.values.sortedBy(HarnessResourceLeaseInfo::acquiredAt),
    )

    private fun increment(kind: HarnessResourceKind) {
        counter(kind).incrementAndGet()
    }

    private fun decrement(kind: HarnessResourceKind) {
        counter(kind).decrementAndGet()
    }

    private fun counter(kind: HarnessResourceKind): AtomicInteger = when (kind) {
        HarnessResourceKind.MODEL_REQUEST -> activeModels
        HarnessResourceKind.AGENT -> activeAgents
        HarnessResourceKind.TERMINAL -> activeTerminals
        HarnessResourceKind.VIRTUAL_DISPLAY -> activeVirtualDisplays
        HarnessResourceKind.LANGUAGE_SERVER -> activeLanguageServers
    }

    private fun publish() {
        runCatching { onChanged(snapshot()) }
    }
}
