package com.labteto.dshmobile.local.tools.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android Binder identity, not a synthetic test of the UID predicate. */
@RunWith(AndroidJUnit4::class)
class McpPluginWorkerCallerAndroidTest {
    @Test fun hostUidCanOpenDedicatedWorkerWithoutExecutingRemoteTools() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val peer = CompletableDeferred<Messenger>()
        val reply = CompletableDeferred<Bundle>()
        val token = UUID.randomUUID().toString()
        val receiver = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                if (message.what == McpWorkerWire.RESPONSE &&
                    message.arg2 == McpWorkerWire.VERSION &&
                    message.data.getString("token") == token &&
                    message.arg1 == 1) {
                    reply.complete(Bundle(message.data))
                }
            }
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null) peer.completeExceptionally(IOException("MCP Binder missing"))
                else peer.complete(Messenger(binder))
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                if (!peer.isCompleted) peer.completeExceptionally(IOException("MCP Binder disconnected"))
            }
        }
        assertTrue(context.bindService(
            Intent(context, McpPluginWorkerService::class.java),
            connection, Context.BIND_AUTO_CREATE,
        ))
        try {
            val worker = withTimeout(15_000L) { peer.await() }
            worker.send(Message.obtain(null, McpWorkerWire.OPEN, 1, McpWorkerWire.VERSION).apply {
                replyTo = receiver
                data = Bundle().apply {
                    putString("token", token)
                    putString("kind", "http")
                    putString("endpoint", "https://mcp.example.com/mcp")
                }
            })
            val result = withTimeout(15_000L) { reply.await() }
            assertNull(result.getString("error"))
            val workerPid = JSONObject(requireNotNull(result.getString("result"))).getInt("worker_pid")
            assertNotEquals(Process.myPid(), workerPid)
            worker.send(Message.obtain(null, McpWorkerWire.CLOSE, 2, McpWorkerWire.VERSION).apply {
                replyTo = receiver
                data = Bundle().apply { putString("token", token) }
            })
        } finally {
            context.unbindService(connection)
        }
    }
}
