package com.labteto.dshmobile.local.runtime

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.PowerManager
import android.os.SystemClock
import android.os.health.SystemHealthManager
import com.labteto.dshmobile.harness.resource.HarnessResourceBudget
import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One process-wide capability sampler for Snapdragon, Dimensity and other Android SoCs.
 * Sampling is driven by resource admission and OS memory/thermal events, not a background timer.
 * Android 16 CPU headroom is optional on some devices, so thermal/RAM always remain authoritative.
 */
internal class LocalDeviceCapacityMonitor(
    private val context: Context,
    private val memoryClassMb: Int,
    startupMemory: LocalDeviceMemorySnapshot,
) {
    private val app = context.applicationContext
    private val power = app.getSystemService(PowerManager::class.java)
    private val health = app.getSystemService(SystemHealthManager::class.java)
    private val initialBudget: HarnessResourceBudget =
        localResourceBudgetForMemoryClass(memoryClassMb, startupMemory)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sampleMutex = Mutex()
    private var lastSampleAt = -1L
    private var lastCpuSampleAt = -1L
    private var cpuHeadroomPercent: Float? = null
    private lateinit var scheduler: HarnessResourceScheduler

    private val memoryEvents = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) { scope.launch { refresh(force = true) } }
        override fun onLowMemory() { scope.launch { refresh(force = true) } }
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
    }

    private val thermalEvents = PowerManager.OnThermalStatusChangedListener {
        scope.launch { refresh(force = true) }
    }

    fun bind(resourceScheduler: HarnessResourceScheduler) {
        scheduler = resourceScheduler
        app.registerComponentCallbacks(memoryEvents)
        power?.addThermalStatusListener(app.mainExecutor, thermalEvents)
        scope.launch { refresh(force = true) }
    }

    suspend fun refresh(force: Boolean = false) = withContext(Dispatchers.IO) {
        sampleMutex.withLock {
            val now = SystemClock.elapsedRealtime()
            if (!force && lastSampleAt >= 0 && now - lastSampleAt < 1_000L) return@withLock
            lastSampleAt = now
            // getCpuHeadroom uses a Binder call; never sample it on the UI thread or
            // more frequently than the device's advertised minimum interval.
            if (health != null) {
                val interval = runCatching { health.cpuHeadroomMinIntervalMillis }
                    .getOrDefault(2_000L).coerceAtLeast(1_000L)
                if (lastCpuSampleAt < 0 || now - lastCpuSampleAt >= interval) {
                    lastCpuSampleAt = now
                    cpuHeadroomPercent = runCatching { health.getCpuHeadroom(null) }
                        .getOrNull()?.takeIf { it.isFinite() && it in 0f..100f }
                }
            }
            scheduler.updateBudget(
                localAdaptiveResourceBudget(
                    memoryClassMb = memoryClassMb,
                    initial = initialBudget,
                    liveMemory = readLocalDeviceMemorySnapshot(app),
                    cpuHeadroomPercent = cpuHeadroomPercent,
                    thermalStatus = power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE,
                    powerSave = power?.isPowerSaveMode == true,
                ),
            )
        }
    }
}
