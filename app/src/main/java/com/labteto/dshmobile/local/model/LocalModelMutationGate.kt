package com.labteto.dshmobile.local.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex

internal object LocalModelMutationGate {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.lock()
        try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}
