package com.labteto.dshmobile.local.runtime

import android.annotation.SuppressLint
import android.os.PowerManager

/** Service-owned CPU lease, shared by its real active turns and jobs. Never keeps the screen on. */
internal class LocalExecutionWakeLock(power: PowerManager) {
    private val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "777:LocalExecution").apply {
        setReferenceCounted(false)
    }

    val isHeld: Boolean get() = lock.isHeld

    // Long user tasks have no arbitrary wall-clock deadline. The Service releases this on the
    // last real task's finish/cancel, failed promotion, or destruction; step changes never reacquire.
    @SuppressLint("WakelockTimeout")
    fun setActive(active: Boolean) {
        if (active && !lock.isHeld) lock.acquire()
        else if (!active && lock.isHeld) lock.release()
    }
}
