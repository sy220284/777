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
    FILE_IO,
    MEDIA_DECODE,
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
    val maxFileIo: Int = 2,
    val maxMediaDecode: Int = 1,
) {
    init {
        require(maxModelRequests in 1..16) { "模型并发预算必须在 1..16 之间" }
        require(maxAgents in 1..32) { "智能体并发预算必须在 1..32 之间" }
        require(maxTerminals in 1..16) { "终端预算必须在 1..16 之间" }
        require(maxVirtualDisplays in 1..8) { "虚拟屏预算必须在 1..8 之间" }
        require(maxLanguageServers in 1..16) { "语言服务预算必须在 1..16 之间" }
        require(maxFileIo in 1..8 && maxMediaDecode in 1..4) { "大文件并发预算无效" }
    }

    fun limit(kind: HarnessResourceKind): Int = when (kind) {
        HarnessResourceKind.MODEL_REQUEST -> maxModelRequests
        HarnessResourceKind.AGENT -> maxAgents
        HarnessResourceKind.TERMINAL -> maxTerminals
        HarnessResourceKind.VIRTUAL_DISPLAY -> maxVirtualDisplays
        HarnessResourceKind.LANGUAGE_SERVER -> maxLanguageServers
        HarnessResourceKind.FILE_IO -> maxFileIo
        HarnessResourceKind.MEDIA_DECODE -> maxMediaDecode
    }
}

data class HarnessResourceLeaseInfo(
    val id: String,
    val kind: HarnessResourceKind,
    val owner: String,
    val acquiredAt: Long,
    /** Reserved cost units; one heavy file operation may occupy two file-I/O slots. */
    val units: Int = 1,
)

data class HarnessResourceSnapshot(
    val activeModelRequests: Int,
    val activeAgents: Int,
    val activeTerminals: Int,
    val activeVirtualDisplays: Int,
    val activeLanguageServers: Int,
    val activeFileIo: Int = 0,
    val activeMediaDecode: Int = 0,
    val budget: HarnessResourceBudget,
    val leases: List<HarnessResourceLeaseInfo> = emptyList(),
    /** Derived from the exact lease ledger, never a second mutable counter. */
    val activeUnits: Map<HarnessResourceKind, Int> = emptyMap(),
) {
    val pressure: HarnessResourcePressure
        get() {
            fun ratio(kind: HarnessResourceKind, count: Int): Double =
                (activeUnits[kind] ?: count).toDouble() / budget.limit(kind)
            val ratios = listOf(
                ratio(HarnessResourceKind.MODEL_REQUEST, activeModelRequests),
                ratio(HarnessResourceKind.AGENT, activeAgents),
                ratio(HarnessResourceKind.TERMINAL, activeTerminals),
                ratio(HarnessResourceKind.VIRTUAL_DISPLAY, activeVirtualDisplays),
                ratio(HarnessResourceKind.LANGUAGE_SERVER, activeLanguageServers),
                ratio(HarnessResourceKind.FILE_IO, activeFileIo),
                ratio(HarnessResourceKind.MEDIA_DECODE, activeMediaDecode),
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
    /** Immutable startup ceiling for consumers deriving pressure from runtime reductions. */
    val startupBudget: HarnessResourceBudget = budget
    private var currentBudget = budget
    val budget: HarnessResourceBudget get() = synchronized(leaseStateLock) { currentBudget }
    private val capacityRevision = MutableStateFlow(0L)
    // FIFO tickets retain the fairness of the old semaphore when capacity changes.
    private val admissionQueues = HarnessResourceKind.values()
        .associateWith { java.util.ArrayDeque<Long>() }
    private var nextTicket = 0L
    private val activeModels = AtomicInteger()
    private val activeAgents = AtomicInteger()
    private val activeTerminals = AtomicInteger()
    private val activeVirtualDisplays = AtomicInteger()
    private val activeLanguageServers = AtomicInteger()
    private val activeFileIo = AtomicInteger()
    private val activeMediaDecode = AtomicInteger()
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
        units: Int = 1,
    ): HarnessResourceLease {
        require(units in 1..32) { "资源请求成本须在 1..32 之间" }
        // Refresh the device budget at the admission boundary, not on a polling timer.
        beforeAcquire()
        val ticket = synchronized(leaseStateLock) {
            val id = ++nextTicket
            admissionQueues.getValue(kind).addLast(id)
            id
        }
        try {
            while (true) {
                var admitted: HarnessResourceLeaseInfo? = null
                val observedRevision = synchronized(leaseStateLock) {
                    val queue = admissionQueues.getValue(kind)
                    val capacity = currentBudget.limit(kind)
                    // An adaptive device budget may shrink below the original request cost.
                    // Charge every available slot instead of blocking that job forever.
                    val actualUnits = minOf(units, capacity)
                    val occupied = leases.values.sumOf { if (it.kind == kind) it.units else 0 }
                    if (queue.peekFirst() == ticket && occupied <= capacity - actualUnits) {
                        queue.removeFirst()
                        admitted = HarnessResourceLeaseInfo(
                            id = "lease-" + UUID.randomUUID().toString().replace("-", "").take(16),
                            kind = kind,
                            owner = owner.take(160),
                            acquiredAt = System.currentTimeMillis(),
                            units = actualUnits,
                        )
                        increment(kind)
                        leases[admitted!!.id] = admitted!!
                        // The next FIFO waiter may also fit the enlarged capacity.
                        capacityRevision.value += 1
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
        } finally {
            synchronized(leaseStateLock) {
                if (admissionQueues.getValue(kind).remove(ticket)) {
                    capacityRevision.value += 1
                }
            }
        }
    }

    suspend fun <T> withResource(
        kind: HarnessResourceKind,
        owner: String = kind.name.lowercase(),
        units: Int = 1,
        block: suspend () -> T,
    ): T {
        val lease = acquire(kind, owner, units)
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
        activeFileIo = activeFileIo.get(),
        activeMediaDecode = activeMediaDecode.get(),
        budget = currentBudget,
        leases = leases.values.sortedBy(HarnessResourceLeaseInfo::acquiredAt),
        activeUnits = HarnessResourceKind.values().associateWith { kind ->
            leases.values.sumOf { if (it.kind == kind) it.units else 0 }
        },
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
        HarnessResourceKind.FILE_IO -> activeFileIo
        HarnessResourceKind.MEDIA_DECODE -> activeMediaDecode
    }

    private fun publish() = synchronized(publicationLock) {
        // Take the snapshot only after acquiring the publication lock. Another thread
        // may already have released/acquired leases while a slow observer was running.
        // Publishing a previously captured snapshot here would roll the UI backwards.
        runCatching { onChanged(snapshot()) }
    }
}

/** Large file transfers reserve the full I/O pool when the device has two available slots. */
fun harnessFileIoUnitsForBytes(bytes: Long): Int =
    if (bytes >= 64L * 1024L * 1024L) 2 else 1
