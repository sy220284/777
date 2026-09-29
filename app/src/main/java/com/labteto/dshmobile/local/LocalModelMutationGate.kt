package com.labteto.dshmobile.local

import kotlinx.coroutines.sync.Mutex

internal object LocalModelMutationGate {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T {
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}
