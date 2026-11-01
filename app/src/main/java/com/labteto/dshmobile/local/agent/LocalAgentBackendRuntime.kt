package com.labteto.dshmobile.local.agent

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.labteto.dshmobile.harness.agent.AgentTurnBackend
import com.labteto.dshmobile.harness.agent.AgentBackendExecutionContext
import com.labteto.dshmobile.harness.agent.AgentRunExecutionContext
import com.labteto.dshmobile.local.agent.ipc.AndroidIsolatedAgentBackend
import com.labteto.dshmobile.local.security.KeystorePreferenceSecretStore
import com.labteto.dshmobile.runtime.AgentTlsBackend
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** UI is the only configuration writer. Tasks persist an immutable route revision, never its token. */
@Singleton
internal class LocalAgentBackendRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    dataStore: DataStore<Preferences>,
) {
    private val store = KeystorePreferenceSecretStore(dataStore, "agent_backend_peer_v1", "dsh.agent.backend.v1")
    private class Peer(val revision: String, val host: String, val port: Int, val pin: String, val token: String)
    private suspend fun peer(): Peer? = store.get()?.let { encoded ->
        val data = Json.parseToJsonElement(encoded).jsonObject
        Peer(data.getValue("revision").jsonPrimitive.content, data.getValue("host").jsonPrimitive.content,
            data.getValue("port").jsonPrimitive.int, data.getValue("pin").jsonPrimitive.content,
            data.getValue("token").jsonPrimitive.content)
    }
    suspend fun status(): String = peer()?.let { "已授权 ${it.host}:${it.port}（证书 ${it.pin.take(12)}…）" } ?: "尚未授权远端执行器"
    suspend fun configure(host: String, port: Int, pin: String, token: String, contextExportConsent: Boolean) {
        require(contextExportConsent) { "必须由用户授权任务上下文传输" }
        require(host.isNotBlank() && host == host.trim() && host.none { it.isWhitespace() || it in "/@?#" })
        require(port in 1..65535 && pin.matches(Regex("[0-9a-fA-F]{64}")))
        require(token.isNotBlank() && token.all { it.code in 33..126 } && token.length <= 4096)
        store.put(buildJsonObject {
            put("revision", UUID.randomUUID().toString()); put("host", host); put("port", port)
            put("pin", pin.lowercase()); put("token", token)
        }.toString())
    }
    suspend fun revoke() = store.clear()
    suspend fun pin(backend: LocalAgentExecutionBackend): LocalSubagentCapabilities {
        val parent = currentCoroutineContext()[LocalAgentBackendSelectionContext]?.capabilities
        if (parent != null && backend == LocalAgentExecutionBackend.IN_PROCESS) return parent
        return LocalSubagentCapabilities(executionBackend = backend,
            remoteBackendRevision = if (backend == LocalAgentExecutionBackend.TRUSTED_REMOTE)
                requireNotNull(peer()) { "请先在工具设置授权远端执行器" }.revision else null)
    }
    suspend fun resolve(capabilities: LocalSubagentCapabilities): AgentTurnBackend? = when (capabilities.executionBackend) {
        LocalAgentExecutionBackend.IN_PROCESS -> null
        LocalAgentExecutionBackend.ISOLATED_PROCESS -> AgentTurnBackend { request, services ->
            withContext(com.labteto.dshmobile.runtime.ProcessIsolationContext() + LocalAgentBackendSelectionContext(capabilities)) {
                AndroidIsolatedAgentBackend(context).run(request, services)
            }
        }
        LocalAgentExecutionBackend.TRUSTED_REMOTE -> {
            val expected = requireNotNull(capabilities.remoteBackendRevision) { "远端执行器未绑定授权版本" }
            val selected = requireNotNull(peer()) { "远端授权已撤销" }
            check(selected.revision == expected) { "远端授权或执行端已变化，原任务停止续跑" }
            val checkGrant: suspend () -> Unit = {
                currentCoroutineContext().ensureActive()
                check(peer()?.revision == expected) { "远端授权已变更或撤销" }
                currentCoroutineContext()[AgentRunExecutionContext]?.requireCurrent()
            }
            val driver = AgentTlsBackend(selected.host, selected.port, selected.pin, selected.token, requireGrantCurrent = checkGrant)
            AgentTurnBackend { request, services ->
                withContext(AgentBackendExecutionContext(checkGrant) + com.labteto.dshmobile.runtime.ProcessIsolationContext() + LocalAgentBackendSelectionContext(capabilities)) {
                    coroutineScope {
                        val monitor = launch { store.changes().collect { checkGrant() } }
                        try { checkGrant(); driver.run(request, services) } finally { monitor.cancel() }
                    }
                }
            }
        }
    }
}
