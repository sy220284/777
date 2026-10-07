package com.labteto.dshmobile.data

import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.connection.ConnectionUiState
import com.labteto.dshmobile.core.wire.dto.RemoteEventFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Owns Remote Session's connection-generation observation and baseline scheduling.
 *
 * ConnectionManager remains the network/reconnect owner. This class only translates connection
 * lifecycle into Session baseline work, coalesces repeated baseline requests, and forwards host
 * event frames into the single Remote Session ingress.
 */
internal class RemoteSessionHostSync(
    private val scope: CoroutineScope,
    private val connectionState: StateFlow<ConnectionUiState>,
    private val eventFrames: Flow<RemoteEventFrame>,
    private val onGenerationRetired: () -> Unit,
    private val runBaseline: suspend () -> Unit,
    private val onEventFrame: (RemoteEventFrame) -> Unit,
    private val logger: (String, Throwable?) -> Unit,
) {
    private val baselineGate = ConflatedRefreshGate()

    fun start() {
        observeConnection()
        observeEvents()
    }

    fun requestBaseline() {
        scope.launch {
            baselineGate.request {
                try {
                    runBaseline()
                } catch (error: Exception) {
                    logger("baseline failed", error)
                }
            }
        }
    }

    private fun observeConnection() {
        scope.launch {
            var previous = connectionState.value
            connectionState.collect { current ->
                val initialConnect = !previous.hasConnected && current.hasConnected
                val reconnect = previous.hasConnected &&
                    previous.phase == ConnectionPhase.RECONNECTING &&
                    current.phase == ConnectionPhase.CONNECTED
                val retiredGeneration =
                    previous.phase == ConnectionPhase.CONNECTED &&
                        current.phase != ConnectionPhase.CONNECTED

                if (retiredGeneration) onGenerationRetired()
                previous = current
                if (initialConnect || reconnect) requestBaseline()
            }
        }
    }

    private fun observeEvents() {
        scope.launch {
            eventFrames.collect(onEventFrame)
        }
    }
}
