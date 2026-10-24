package com.labteto.dshmobile.local.work

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.harness.resource.HarnessResourceBudget
import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import com.labteto.dshmobile.local.DeepSeekClient
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.agent.LocalSubagentCapabilities
import com.labteto.dshmobile.local.agent.LocalSubagentLaunchSpec
import com.labteto.dshmobile.local.agent.LocalSubagentStatus
import com.labteto.dshmobile.local.agent.captureLocalAgentParentLink
import com.labteto.dshmobile.local.agent.localAgentRunPolicy
import com.labteto.dshmobile.local.jobs.LocalJobManager
import com.labteto.dshmobile.local.model.AnthropicMessagesAdapter
import com.labteto.dshmobile.local.model.AnthropicMessagesClient
import com.labteto.dshmobile.local.model.LocalApiKeyStore
import com.labteto.dshmobile.local.model.LocalModelAdapterRegistry
import com.labteto.dshmobile.local.model.LocalModelCredentialResolver
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalModelRouteCatalog
import com.labteto.dshmobile.local.model.LocalModelRunContext
import com.labteto.dshmobile.local.model.OpenAiCompatibleChatAdapter
import com.labteto.dshmobile.local.model.OpenAiResponsesAdapter
import com.labteto.dshmobile.local.model.OpenAiResponsesClient
import com.labteto.dshmobile.local.model.chatgpt.ChatGptAccountStore
import com.labteto.dshmobile.local.model.chatgpt.ChatGptPlanAuthorizationEvents
import com.labteto.dshmobile.local.model.chatgpt.ChatGptSessionManager
import com.labteto.dshmobile.local.runtime.LocalAgentRunCoordinator
import com.labteto.dshmobile.local.runtime.LocalHarnessResourceState
import com.labteto.dshmobile.local.runtime.LocalKernelState
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.SqliteSessionEventDatabase
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Runner, model codec/gateway, scheduler and SQLite; only provider HTTP replies are scripted. */
@RunWith(AndroidJUnit4::class)
class LocalRecursiveSubagentRunnerAndroidTest {
    @Test fun threeRunnerLevelsPersistParentLinksAndReturnRealFileEvidence() = runBlocking {
        exercise(cancelAtLeaf = false, requestHiddenWrite = false)
    }

    @Test fun parentCancellationReleasesEveryDescendantAgentAndModelLease() = runBlocking {
        exercise(cancelAtLeaf = true, requestHiddenWrite = false)
    }

    @Test fun recursiveLeafCannotExecuteAnUnexposedWriteTool() = runBlocking {
        exercise(cancelAtLeaf = false, requestHiddenWrite = true)
    }

    private suspend fun exercise(cancelAtLeaf: Boolean, requestHiddenWrite: Boolean) = coroutineScope {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "runner-integration-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = root
            override fun getDatabasePath(name: String): File = File(root, name)
        }
        val json = Json { ignoreUnknownKeys = true }
        val storeJob = SupervisorJob()
        val storeScope = CoroutineScope(storeJob + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = storeScope) { File(root, "keys.preferences_pb") }
        val profile = LocalModelProfile("integration-${UUID.randomUUID()}", "fixture-agent", "https://agent-integration.invalid/v1")
        val keys = LocalApiKeyStore(store)
        val enteredLeaf = CountDownLatch(1)
        val releaseLeaf = CountDownLatch(if (cancelAtLeaf) 1 else 0)
        val requests = AtomicInteger()
        val executedReads = AtomicInteger()
        val evidence = File(root, "leaf.txt").apply { writeText("verified-leaf") }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val body = Buffer().also { requireNotNull(chain.request().body).writeTo(it) }.readUtf8()
            val request = json.parseToJsonElement(body).jsonObject
            assertEquals(profile.model, request["model"]!!.jsonPrimitive.content)
            assertEquals("Bearer fixture-only", chain.request().header("Authorization"))
            requests.incrementAndGet()
            val messages = request["messages"]!!.jsonArray.map { it.jsonObject }
            val task = messages.first { it["role"]?.jsonPrimitive?.content == "user" }["content"]!!.jsonPrimitive.content
            val level = (1..3).first { "level-$it" in task }
            val hasResult = messages.any { it["role"]?.jsonPrimitive?.content == "tool" }
            val tool = if (hasResult) null else if (level < 3) "subagent" else if (requestHiddenWrite) "write" else "read"
            if (level == 3 && !hasResult && cancelAtLeaf) {
                enteredLeaf.countDown()
                check(releaseLeaf.await(15, TimeUnit.SECONDS)) { "Parent did not release cancelled HTTP fixture" }
            }
            val message = buildJsonObject {
                put("role", "assistant")
                if (tool == null) {
                    val resultText = messages.last { it["role"]?.jsonPrimitive?.content == "tool" }["content"]!!.jsonPrimitive.content
                    if (level == 3 && requestHiddenWrite) assertTrue(resultText.contains("TOOL") || resultText.contains("工具"))
                    else assertTrue(resultText.contains("verified-leaf"))
                    put("content", "verified-leaf")
                } else {
                    put("tool_calls", JsonArray(listOf(buildJsonObject {
                        put("id", "call-$level")
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tool)
                            put("arguments", if (tool == "subagent") "{\"task\":\"level-${level + 1}\",\"max_steps\":4}"
                                else "{\"path\":\"leaf.txt\"}")
                        })
                    })))
                }
            }
            val response = buildJsonObject {
                put("id", "fixture-${requests.get()}")
                put("model", profile.model)
                put("choices", JsonArray(listOf(buildJsonObject {
                    put("index", 0)
                    put("message", message)
                    put("finish_reason", if (tool == null) "stop" else "tool_calls")
                })))
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val accounts = ChatGptAccountStore(store, json)
        val authorization = ChatGptPlanAuthorizationEvents()
        val gateway = LocalModelGateway(
            LocalModelCredentialResolver(keys, accounts, ChatGptSessionManager(accounts, http, json, authorization), authorization),
            LocalModelAdapterRegistry(OpenAiCompatibleChatAdapter(DeepSeekClient(http, json)),
                OpenAiResponsesAdapter(OpenAiResponsesClient(http, json)),
                AnthropicMessagesAdapter(AnthropicMessagesClient(http, json))),
            LocalModelRouteCatalog(context, json), http,
        )
        val scheduler = HarnessResourceScheduler(HarnessResourceBudget(maxModelRequests = 1, maxAgents = 4))
        val state = MutableStateFlow(LocalHarnessState(sessionId = "integration", loading = false,
            workspacePath = root.path, kernel = LocalKernelState(contextBudgetChars = 100_000,
                resources = LocalHarnessResourceState(maxAgents = 4))))
        val database = SqliteSessionEventDatabase(context, json, "events.db")
        val log = LocalSessionEventLog(File(root, "events.jsonl"), json, sessionId = "integration", sqlite = database)
        val coordinator = LocalAgentRunCoordinator({ log })
        val jobs = LocalJobManager(this, onChanged = {})
        fun schema(name: String): JsonObject = buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", name); put("description", "integration fixture")
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put(if (name == "subagent") "task" else "path", buildJsonObject { put("type", "string") })
                    })
                })
            })
        }
        fun runner(): LocalSubagentRunner = LocalSubagentRunner(
            modelGateway = gateway, state = state, jobs = jobs,
            historySnapshot = { emptyList() }, contextSnapshot = { "fixture workspace" }, eventLog = { log },
            schemas = { _, _, _ -> JsonArray(listOf(schema("read"))) },
            nestedReadonlySchemas = { JsonArray(listOf(schema("subagent"))) },
            newNestedRunner = { runner() },
            execute = { call, mutation, _ ->
                assertFalse(mutation)
                assertEquals("read", call.name)
                assertEquals("leaf.txt", call.arguments["path"]!!.jsonPrimitive.content)
                executedReads.incrementAndGet()
                AgentToolResult(evidence.readText())
            },
            prepareMessages = { messages, _, _, _ -> messages }, resolveImageMode = { mode, _, _ -> mode },
            resourceScheduler = scheduler, runCoordinator = coordinator, compactionPolicy = LocalWorkSubagentCompactionPolicy,
        )
        try {
            keys.putFor(profile.id, "fixture-only")
            val parent = coordinator.start(sessionId = "integration", usageMode = LocalUsageMode.WORK,
                model = profile.model, baseUrl = profile.baseUrl, routeProfile = profile, planMode = false,
                policy = localAgentRunPolicy(LocalUsageMode.WORK), safeAutoApprovalEnabled = false,
                maxSteps = 4, input = "parent", memoryInput = "parent", allowMutation = false)
            withTimeout(30_000L) {
                val run = async(coordinator.executionContext(parent) + LocalModelRunContext(profile)) {
                    runner().runResult(LocalSubagentLaunchSpec(task = "level-1", modelOverride = profile.id,
                        maxSteps = 4, capabilities = LocalSubagentCapabilities(maxDepth = 3),
                        parentLink = captureLocalAgentParentLink("root-delegation")),
                        executionFence = { coordinator.ensureCurrentOwner(parent) })
                }
                if (cancelAtLeaf) {
                    assertTrue(withContext(Dispatchers.IO) { enteredLeaf.await(15, TimeUnit.SECONDS) })
                    run.cancel()
                    releaseLeaf.countDown()
                    run.cancelAndJoin()
                    assertTrue(run.isCancelled)
                } else {
                    val result = run.await()
                    assertEquals(LocalSubagentStatus.COMPLETED, result.status)
                    assertEquals("verified-leaf", result.output)
                    assertEquals(6, requests.get())
                    assertEquals(if (requestHiddenWrite) 0 else 1, executedReads.get())
                }
            }
            val tree = readLocalAgentTree(log)
            val children = tree.nodes.filter { it.agentId != null }
            assertEquals(3, children.size)
            assertTrue(children.all { it.parentRunId != null && tree.nodes.any { node -> node.runId == it.parentRunId } })
            if (cancelAtLeaf) assertTrue("Cancelled descendants must have durable terminal checkpoints",
                children.all { it.status == "cancelled" })
            else assertTrue(children.all { it.status == "completed" })
            assertEquals(0, scheduler.snapshot().activeAgents)
            assertEquals(0, scheduler.snapshot().activeModelRequests)
            assertEquals("verified-leaf", evidence.readText())
        } finally {
            releaseLeaf.countDown()
            log.close()
            database.close()
            storeJob.cancelAndJoin()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
            root.deleteRecursively()
        }
    }
}
