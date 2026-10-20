package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.resource.HarnessResourcePressure
import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import com.labteto.dshmobile.local.runtime.LocalDeviceMemorySnapshot
import com.labteto.dshmobile.local.runtime.localResourceBudgetForMemoryClass
import com.labteto.dshmobile.local.runtime.localAdaptiveResourceBudget
import com.labteto.dshmobile.local.runtime.localEffectiveHistoryPressure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalExecutionKernelTest {
    @Test
    fun fallbackBudgetUsesAppHeapWhenPhysicalRamCannotBeRead() {
        assertEquals(2, localResourceBudgetForMemoryClass(192).maxModelRequests)
        assertEquals(4, localResourceBudgetForMemoryClass(256).maxModelRequests)
        assertEquals(5, localResourceBudgetForMemoryClass(384).maxModelRequests)
        assertEquals(6, localResourceBudgetForMemoryClass(512).maxModelRequests)
    }

    @Test
    fun target12And16GbPhonesRespectHeapLimitsAndConservativeFallback() {
        fun capacity(heap: Int, total: Int, available: Int = 4_096) =
            localResourceBudgetForMemoryClass(
                heap,
                LocalDeviceMemorySnapshot(totalRamMb = total, availableRamMb = available),
            )

        assertEquals(8, capacity(256, 11_200).maxModelRequests) // 12 GB target
        assertEquals(8, capacity(384, 11_200).maxModelRequests)
        assertEquals(10, capacity(512, 15_100).maxModelRequests) // 16 GB target
        assertEquals(8, capacity(256, 15_100).maxModelRequests) // Java heap remains a safety cap
        // There are no dedicated low-RAM tuning profiles below the target class.
        assertEquals(3, capacity(384, 9_000).maxModelRequests)
        assertEquals(12, capacity(512, 15_100).maxAgents)
        assertEquals(6, capacity(512, 15_100).maxTerminals)
        assertEquals(3, capacity(512, 15_100).maxVirtualDisplays)
        assertEquals(4, capacity(128, 15_100).maxModelRequests)
    }

    @Test
    fun lowAvailableMemoryProtectsDeviceEvenWithHighPhysicalRam() {
        fun underLoad(free: Int, low: Boolean = false) =
            localResourceBudgetForMemoryClass(
                512, LocalDeviceMemorySnapshot(15_100, free, lowMemory = low),
            )
        assertEquals(10, underLoad(3_000).maxModelRequests)
        assertEquals(4, underLoad(1_500).maxModelRequests)
        assertEquals(3, underLoad(800).maxModelRequests)
        assertEquals(2, underLoad(400).maxModelRequests)
        assertEquals(2, underLoad(3_000, low = true).maxModelRequests)
        assertEquals(2, localResourceBudgetForMemoryClass(
            512, LocalDeviceMemorySnapshot(15_100, 3_000, lowRamDevice = true),
        ).maxModelRequests)
    }

    @Test
    fun adaptiveContextBudgetShrinksUnderPressure() {
        val low = localHistoryBudgetFor(512, HarnessResourcePressure.LOW)
        val medium = localHistoryBudgetFor(512, HarnessResourcePressure.MEDIUM)
        val high = localHistoryBudgetFor(512, HarnessResourcePressure.HIGH)

        assertTrue(low.maxHistoryChars > medium.maxHistoryChars)
        assertTrue(medium.maxHistoryChars > high.maxHistoryChars)
        assertTrue(low.maxToolResultChars > high.maxToolResultChars)
        assertTrue(high.tailChars >= 80_000)
    }

    @Test
    fun resourceBudgetIncludesLongLivedPools() {
        val lowMemory = localResourceBudgetForMemoryClass(192)
        val highMemory = localResourceBudgetForMemoryClass(512)

        assertEquals(1, lowMemory.maxVirtualDisplays)
        assertEquals(2, highMemory.maxVirtualDisplays)
        assertTrue(highMemory.maxTerminals > lowMemory.maxTerminals)
        assertTrue(highMemory.maxLanguageServers > lowMemory.maxLanguageServers)
    }
    @Test
    fun idleDeviceWithReducedCapacityStillTriggersHistoryProtection() {
        val initial = localResourceBudgetForMemoryClass(
            512, LocalDeviceMemorySnapshot(15_100, 4_096),
        )
        val scheduler = HarnessResourceScheduler(initial)
        assertEquals(HarnessResourcePressure.LOW,
            localEffectiveHistoryPressure(scheduler.snapshot(), scheduler.startupBudget))
        scheduler.updateBudget(initial.copy(maxModelRequests = 3, maxAgents = 3, maxTerminals = 2))
        val idle = scheduler.snapshot()
        assertEquals(0, idle.activeAgents)
        assertEquals(HarnessResourcePressure.LOW, idle.pressure)
        assertEquals(HarnessResourcePressure.HIGH,
            localEffectiveHistoryPressure(idle, scheduler.startupBudget))
        scheduler.updateBudget(initial)
        assertEquals(HarnessResourcePressure.LOW,
            localEffectiveHistoryPressure(scheduler.snapshot(), scheduler.startupBudget))
    }

    @Test
    fun cloudModelConcurrencyIsIndependentOfCpuHeadroomButLocalWorkScales() {
        val startup = LocalDeviceMemorySnapshot(15_100, 4_096)
        val initial = localResourceBudgetForMemoryClass(512, startup)
        val busy = localAdaptiveResourceBudget(
            512, initial, startup, cpuHeadroomPercent = 10f,
            thermalStatus = 0, powerSave = false,
        )
        assertEquals(10, busy.maxModelRequests)
        assertTrue(busy.maxAgents < initial.maxAgents)
        assertTrue(busy.maxTerminals < initial.maxTerminals)
        val recovered = localAdaptiveResourceBudget(
            512, initial, startup, cpuHeadroomPercent = 95f,
            thermalStatus = 0, powerSave = false,
        )
        assertEquals(initial, recovered)
    }

    @Test
    fun thermalMemoryAndBatterySignalsChangeAdmissionsWithoutChangingStartupCeilings() {
        val initial = localResourceBudgetForMemoryClass(
            512, LocalDeviceMemorySnapshot(15_100, 4_096),
        )
        fun live(
            available: Int = 4_096, thermal: Int = 0,
            saver: Boolean = false,
        ) = localAdaptiveResourceBudget(
            memoryClassMb = 512,
            initial = initial,
            liveMemory = LocalDeviceMemorySnapshot(15_100, available),
            cpuHeadroomPercent = 90f,
            thermalStatus = thermal,
            powerSave = saver,
        )
        assertEquals(10, live().maxModelRequests)
        assertEquals(3, live(available = 800).maxModelRequests)
        assertEquals(4, live(thermal = 3).maxModelRequests)
        assertEquals(2, live(thermal = 4).maxModelRequests)
        assertEquals(10, live(saver = true).maxModelRequests)
        assertTrue(live(saver = true).maxAgents < initial.maxAgents)
        assertEquals(initial, live())
    }

}
