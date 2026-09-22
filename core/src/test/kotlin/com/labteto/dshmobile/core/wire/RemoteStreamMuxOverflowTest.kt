package com.labteto.dshmobile.core.wire

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class RemoteStreamMuxOverflowTest {
    @Test fun slowConsumerFailsAndCancelsOnlyItsOwnStream() = runTest {
        lateinit var sink: WsChannelSink
        val sent = mutableListOf<String>()
        val mux = RemoteStreamMux { target ->
            sink = target
            object : WsChannel("http://stub/api/remote.mux", OkHttpClient(), target) {
                override fun start() = target.onOpen()
                override fun send(text: String): Boolean { sent += text; return true }
                override fun close() = Unit
            }
        }
        mux.start()
        val slow = mux.open("session/follow")
        val id = Regex("\"streamId\":\"([^\"]+)\"").find(sent.last())!!.groupValues[1]
        val healthy = mux.open("session/follow")
        val otherId = Regex("\"streamId\":\"([^\"]+)\"").find(sent.last())!!.groupValues[1]
        repeat(257) { sink.onMessage("""{"type":"item","streamId":"$id","value":$it}""") }
        repeat(256) { assertEquals(it.toString(), slow.receive().toString()) }
        val error = runCatching { slow.receive() }.exceptionOrNull() as RemoteStreamException
        assertEquals("resource_exhausted", error.error.code)
        assertEquals(1, sent.count { it.contains("\"type\":\"cancel\"") })
        slow.cancel()
        assertEquals(1, sent.count { it.contains("\"type\":\"cancel\"") })
        sink.onMessage("""{"type":"item","streamId":"$otherId","value":42}""")
        assertEquals("42", healthy.receive().toString())
        mux.close()
    }
}
