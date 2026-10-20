package com.labteto.dshmobile.harness.resource

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

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
    budget: HarnessResourceBudget,
    private val beforeAcquire: suspend () -> Unit = { },
    private val onChanged: (HarnessResourceSnapshot) -> Unit = { },
) {
    // Admission and capacity changes share one lock: shrinking never revokes an active lease.
    private var currentBudget = budget
    val budget: HarnessResourceBudget get() = synchronized(leaseStateLock) { currentBudget }
    private val capacityRevision = MutableStateFlow(0L)
    private val activeModels = AtomicInteger()
    private val activeAgents = AtomicInteger()
    private val activeTerminals = AtomicInteger()
    private val activeVirtualDisplays = AtomicInteger()
    private val activeLanguageServers = AtomicInteger()
    // Counters and the lease registry are one state projection: reports must never see
    // a changed count with a stale lease list (or vice versa).
    private val leaseStateLock = Any()
    private val publicationLock = Any()
    private val leases = linkedMapOf<String, HarnessResourceLeaseInfo>()

    /**
     * Recalculate capacity without replacing the scheduler or cancelling in-flight work.
     * Existing leases may temporarily exceed the new ceiling; admissions resume on release.
     */
    fun updateBudget(next: HarnessResourceBudget) {
        val changed = synchronized(leaseStateLock) {
            if (currentBudget == next) false else {
                currentBudget = next
                capacityRevision.value += 1
                true
            }
        }
        if (changed) publish()
    }

    suspend fun acquire(
        kind: HarnessResourceKind,
        owner: String = kind.name.lowercase(),
    ): HarnessResourceLease {
        // Refresh the device budget at the admission boundary, not on a polling timer.
        beforeAcquire()
        while (true) {
            var admitted: HarnessResourceLeaseInfo? = null
            val observedRevision = synchronized(leaseStateLock) {
                if (counter(kind).get() < currentBudget.limit(kind)) {
                    admitted = HarnessResourceLeaseInfo(
                        id = "lease-" + UUID.randomUUID().toString().replace("-", "").take(16),
                        kind = kind,
                        owner = owner.take(160),
                        acquiredAt = System.currentTimeMillis(),
                    )
                    increment(kind)
                    leases[admitted!!.id] = admitted!!
                }
                capacityRevision.value
            }
            val info = admitted
            if (info != null) {
                publish()
                return HarnessResourceLease(info) {
                    synchronized(leaseStateLock) {
                        leases.remove(info.id)
                        decrement(kind)
                        capacityRevision.value += 1
                    }
                    publish()
                }
            }
            // StateFlow retains the latest revision: no release/update wake-up can be lost.
            capacityRevision.first { it != observedRevision }
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

    fun snapshot(): HarnessResourceSnapshot = synchronized(leaseStateLock) { HarnessResourceSnapshot(
        activeModelRequests = activeModels.get(),
        activeAgents = activeAgents.get(),
        activeTerminals = activeTerminals.get(),
        activeVirtualDisplays = activeVirtualDisplays.get(),
        activeLanguageServers = activeLanguageServers.get(),
        budget = currentBudget,
        leases = leases.values.sortedBy(HarnessResourceLeaseInfo::acquiredAt),
    ) }

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

    private fun publish() = synchronized(publicationLock) {
        // Take the snapshot only after acquiring the publication lock. Another thread
        // may already have released/acquired leases while a slow observer was running.
        // Publishing a previously captured snapshot here would roll the UI backwards.
        runCatching { onChanged(snapshot()) }
    }
}
