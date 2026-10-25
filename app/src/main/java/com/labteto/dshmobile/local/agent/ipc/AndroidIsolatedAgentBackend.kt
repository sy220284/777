package com.labteto.dshmobile.local.agent.ipc

import android.content.Context
import android.content.ServiceConnection
import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Messenger
import android.os.Message
import android.os.Bundle
import android.os.Process
import com.labteto.dshmobile.harness.agent.AgentTurnBackend
import com.labteto.dshmobile.harness.agent.AgentBackendRequest
import com.labteto.dshmobile.harness.agent.AgentBackendServices
import com.labteto.dshmobile.harness.agent.AgentRunResult
import com.labteto.dshmobile.harness.agent.AgentBackendProtocol
import com.labteto.dshmobile.runtime.FramedAgentChannel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull

/** One bindIsolatedService instance per activation; unbinding revokes its OS process. */
internal class AndroidIsolatedAgentBackend(private val context: Context) : AgentTurnBackend {
    override suspend fun run(request: AgentBackendRequest, services: AgentBackendServices): AgentRunResult {
        val connected = CompletableDeferred<IBinder>()
        var channel: FramedAgentChannel? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { connected.complete(service) }
            override fun onServiceDisconnected(name: ComponentName) { channel?.close() }
            override fun onBindingDied(name: ComponentName) { channel?.close(); connected.completeExceptionally(IllegalStateException("隔离服务已退出")) }
            override fun onNullBinding(name: ComponentName) { connected.completeExceptionally(IllegalStateException("隔离服务拒绝连接")) }
        }
        val probe = File(context.filesDir, "agent-isolation-probe-${UUID.randomUUID()}").apply { writeText("private") }
        var bound = false
        var inputToWorker: Array<ParcelFileDescriptor>? = null
        var outputFromWorker: Array<ParcelFileDescriptor>? = null
        try {
            bound = context.bindIsolatedService(Intent(context, IsolatedAgentService::class.java), Context.BIND_AUTO_CREATE,
                "agent_${UUID.randomUUID().toString().replace("-", "")}", context.mainExecutor, connection)
            check(bound) { "系统未能绑定独立 UID 执行器" }
            val binder = withTimeout(15_000) { connected.await() }
            inputToWorker = ParcelFileDescriptor.createReliablePipe()
            outputFromWorker = ParcelFileDescriptor.createReliablePipe()
            val toWorker = requireNotNull(inputToWorker)
            val fromWorker = requireNotNull(outputFromWorker)
            val established = FramedAgentChannel(ParcelFileDescriptor.AutoCloseInputStream(fromWorker[0]),
                ParcelFileDescriptor.AutoCloseOutputStream(toWorker[1]), onClose = {
                    if (bound) { bound = false; runCatching { context.unbindService(connection) } }
                })
            channel = established
            Messenger(binder).send(Message.obtain().apply {
                what = 1
                data = Bundle().apply {
                    putParcelable("input", toWorker[0]); putParcelable("output", fromWorker[1])
                    putString("turn", request.turnId); putString("probe", probe.path)
                }
            })
            toWorker[0].close(); fromWorker[1].close()
            val hello = withTimeout(15_000) { established.receive() }
            check(hello["version"]?.jsonPrimitive?.intOrNull == AgentBackendProtocol.VERSION &&
                hello["turn"]?.jsonPrimitive?.content == request.turnId && hello["operation"]?.jsonPrimitive?.content == "hello")
            val proof = hello.getValue("body").jsonObject
            check(proof["isolated"]?.jsonPrimitive?.booleanOrNull == true &&
                proof["uid"]?.jsonPrimitive?.intOrNull != Process.myUid() &&
                proof["pid"]?.jsonPrimitive?.intOrNull != Process.myPid() &&
                proof["private_read_denied"]?.jsonPrimitive?.booleanOrNull == true) { "独立 UID 或私有数据隔离验证失败" }
            return AgentBackendProtocol.host(established, request, services)
        } finally {
            channel?.close()
            if (bound) { bound = false; context.unbindService(connection) }
            inputToWorker?.forEach { runCatching { it.close() } }
            outputFromWorker?.forEach { runCatching { it.close() } }
            probe.delete()
        }
    }
}
