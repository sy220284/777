package com.labteto.dshmobile.data

import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.connection.ConnectionUiState
import com.labteto.dshmobile.core.wire.dto.RemoteEventFrame
import com.labteto.dshmobile.core.wire.dto.RemoteEventHostInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteSessionHostSyncTest {
    @Test
    fun initialConnectAndReconnectEachScheduleOneBaseline() = runTest {
        val state = MutableStateFlow(ConnectionUiState())
        val events = MutableSharedFlow<RemoteEventFrame>(extraBufferCapacity = 4)
        var baselines = 0
        var retired = 0
        val forwarded = mutableListOf<RemoteEventFrame>()

        val sync = RemoteSessionHostSync(
            scope = backgroundScope,
            connectionState = state,
            eventFrames = events,
            onGenerationRetired = { retired += 1 },
            runBaseline = { baselines += 1 },
            onEventFrame = forwarded::add,
            logger = { _, _ -> },
        )
        sync.start()
        runCurrent()

        state.value = ConnectionUiState(
            phase = ConnectionPhase.CONNECTED,
            hasConnected = true,
        )
        runCurrent()
        assertEquals(1, baselines)

        state.value = ConnectionUiState(
            phase = ConnectionPhase.RECONNECTING,
            hasConnected = true,
        )
        runCurrent()
        assertEquals(1, retired)

        state.value = ConnectionUiState(
            phase = ConnectionPhase.CONNECTED,
            hasConnected = true,
        )
        runCurrent()
        assertEquals(2, baselines)

        val ready = RemoteEventFrame.Ready(
            clientId = "client",
            host = RemoteEventHostInfo(home = "/tmp"),
        )
        events.emit(ready)
        runCurrent()
        assertEquals(listOf(ready), forwarded)
    }
}
