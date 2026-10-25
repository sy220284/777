package com.labteto.dshmobile.local.agent.ipc

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Messenger
import android.os.Message
import android.os.ParcelFileDescriptor
import android.os.IBinder
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.labteto.dshmobile.harness.agent.AgentBackendProtocol
import com.labteto.dshmobile.runtime.FramedAgentChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** No app UID, storage, networking or credentials. Effects require the host's original ToolRegistry. */
class IsolatedAgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var active: Job? = null
    private var channel: FramedAgentChannel? = null
    private val incoming = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.what != 1 || message.sendingUid != applicationInfo.uid) return
            val fields = message.data
            val input = fields.getParcelable("input", ParcelFileDescriptor::class.java) ?: return
            val output = fields.getParcelable("output", ParcelFileDescriptor::class.java)
            if (output == null || active != null) { input.close(); output?.close(); return }
            val turn = fields.getString("turn") ?: run { input.close(); output.close(); return }
            val probe = fields.getString("probe") ?: run { input.close(); output.close(); return }
            val connection = FramedAgentChannel(ParcelFileDescriptor.AutoCloseInputStream(input),
                ParcelFileDescriptor.AutoCloseOutputStream(output))
            channel = connection
            active = scope.launch {
                try {
                    val denied = try {
                        val fd = Os.open(probe, OsConstants.O_RDONLY, 0)
                        Os.close(fd); false
                    } catch (error: ErrnoException) {
                        // Android app-data mount namespaces can hide the host's existing
                        // canary entirely (ENOENT), rather than expose it with EACCES.
                        error.errno == OsConstants.EACCES || error.errno == OsConstants.EPERM ||
                            error.errno == OsConstants.ENOENT
                    }
                    connection.send(AgentBackendProtocol.frame(turn, 0, "hello", buildJsonObject {
                        put("pid", Process.myPid()); put("uid", Process.myUid())
                        put("isolated", Process.isIsolated()); put("private_read_denied", denied)
                    }))
                    AgentBackendProtocol.worker(connection)
                } finally { connection.close(); stopSelf() }
            }
        }
    })
    override fun onBind(intent: Intent): IBinder = incoming.binder
    override fun onDestroy() { channel?.close(); scope.cancel(); super.onDestroy() }
}
