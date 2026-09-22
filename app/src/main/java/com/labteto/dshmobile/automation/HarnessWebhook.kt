package com.labteto.dshmobile.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PersistableBundle
import androidx.core.app.NotificationCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.labteto.dshmobile.harness.plugin.HarnessContext
import com.labteto.dshmobile.harness.plugin.HarnessPlugin
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolResult
import com.labteto.dshmobile.local.LocalHarnessEngine
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class WebhookStatus(
    val enabled: Boolean,
    val port: Int,
    val allowLan: Boolean,
    val tokenHint: String?,
)

data class WebhookRuntimeConfig(
    val enabled: Boolean,
    val port: Int,
    val allowLan: Boolean,
)

@Singleton
class WebhookTokenStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    suspend fun get(): String? {
        val blob = dataStore.data.first()[KEY] ?: return null
        val value = withContext(Dispatchers.Default) { runCatching { decrypt(blob) }.getOrNull() }
        if (value == null) clear()
        return value
    }

    suspend fun getOrCreate(): String {
        get()?.let { return it }
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        put(token)
        return token
    }

    suspend fun rotate(): String {
        clear()
        return getOrCreate()
    }

    suspend fun clear() {
        dataStore.edit { it.remove(KEY) }
    }

    private suspend fun put(value: String) {
        val encrypted = withContext(Dispatchers.Default) { encrypt(value) }
        dataStore.edit { it[KEY] = encrypted }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encoder = Base64.getEncoder()
        return encoder.encodeToString(cipher.iv) + ":" +
            encoder.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    private fun decrypt(blob: String): String {
        val parts = blob.split(':')
        if (parts.size != 2) throw GeneralSecurityException("malformed webhook credential")
        val decoder = Base64.getDecoder()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_BITS, decoder.decode(parts[0])),
        )
        return String(cipher.doFinal(decoder.decode(parts[1])), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        val KEY = stringPreferencesKey("local_harness_webhook_token")
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "dsh_local_harness_webhook_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}

@Singleton
class WebhookController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenStore: WebhookTokenStore,
) {
    private val preferences =
        context.getSharedPreferences("local_harness_webhook", Context.MODE_PRIVATE)

    suspend fun start(port: Int = DEFAULT_PORT, allowLan: Boolean = false): String {
        require(!allowLan) { "Webhook 仅允许本机回环监听；远程访问请使用加密中继" }
        require(port in 1024..65535) { "Webhook 端口必须在 1024..65535" }
        val token = tokenStore.getOrCreate()
        preferences.edit()
            .putBoolean(KEY_ENABLED, true)
            .putInt(KEY_PORT, port)
            .putBoolean(KEY_ALLOW_LAN, false)
            .apply()
        val intent = Intent(context, HarnessWebhookService::class.java)
            .putExtra(EXTRA_PORT, port)
            .putExtra(EXTRA_ALLOW_LAN, false)
        context.startForegroundService(intent)
        val host = "127.0.0.1"
        return "Webhook 已启动：http://$host:$port/run\n令牌：${tokenHint(token)}。完整令牌不会进入会话记录；需要时请使用 webhook_copy_token。"
    }

    fun stop(): Boolean {
        preferences.edit().putBoolean(KEY_ENABLED, false).apply()
        return context.stopService(Intent(context, HarnessWebhookService::class.java))
    }

    suspend fun rotateToken(): String {
        val token = tokenStore.rotate()
        return "Webhook 令牌已轮换：${tokenHint(token)}。完整令牌不会进入会话记录；需要时请使用 webhook_copy_token。"
    }

    suspend fun copyTokenToClipboard(): String {
        val token = tokenStore.getOrCreate()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText("777 Harness Webhook token", token)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        clipboard.setPrimaryClip(clip)
        return "Webhook 完整令牌已复制到系统剪贴板（${tokenHint(token)}）"
    }

    fun runtimeConfig(): WebhookRuntimeConfig = WebhookRuntimeConfig(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        port = preferences.getInt(KEY_PORT, DEFAULT_PORT),
        allowLan = false,
    )

    suspend fun status(): WebhookStatus {
        val token = tokenStore.get()
        return WebhookStatus(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            port = preferences.getInt(KEY_PORT, DEFAULT_PORT),
            allowLan = false,
            tokenHint = token?.let(::tokenHint),
        )
    }

    private fun tokenHint(token: String): String =
        if (token.length <= 8) token else token.take(4) + "…" + token.takeLast(4)

    companion object {
        const val EXTRA_PORT = "port"
        const val EXTRA_ALLOW_LAN = "allow_lan"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PORT = "port"
        private const val KEY_ALLOW_LAN = "allow_lan"
        private const val DEFAULT_PORT = 8765
    }
}

@AndroidEntryPoint
class HarnessWebhookService : Service() {
    @Inject lateinit var engine: LocalHarnessEngine
    @Inject lateinit var controller: WebhookController
    @Inject lateinit var tokenStore: WebhookTokenStore
    @Inject lateinit var resultStore: WebhookResultStore
    @Inject lateinit var json: Json

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val executionMutex = Mutex()
    private val listener by lazy { WebhookListener(scope, ::handle) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        val persisted = controller.runtimeConfig()
        if (intent == null && !persisted.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        val port = intent?.getIntExtra(WebhookController.EXTRA_PORT, persisted.port) ?: persisted.port
        // Ignore persisted legacy LAN settings and intent extras on service restoration.
        try { restartServer(port) } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        listener.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Harness Webhook",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("777 Harness Webhook")
            .setContentText("本机自动化回调服务正在运行")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun restartServer(port: Int) {
        listener.restart(InetSocketAddress("127.0.0.1", port))
    }

    private suspend fun handle(socket: Socket) {
        socket.use { client ->
            val token = tokenStore.getOrCreate()
            client.soTimeout = 10_000
            val request = readHttpRequest(client)
                ?: return respond(client, 400, """{"error":"bad request"}""")
            val method = request.method
            val path = request.path
            val headers = request.headers

            val provided = headers["authorization"]
                ?.removePrefix("Bearer ")
                ?: headers["x-harness-token"]
            if (!constantTimeEquals(token, provided.orEmpty())) {
                return respond(client, 401, """{"error":"unauthorized"}""")
            }

            if (method == "GET" && path.startsWith("/health")) {
                return respond(client, 200, """{"ok":true}""")
            }

            if (method == "GET" && path.startsWith("/result/")) {
                val id = path.removePrefix("/result/").substringBefore('?')
                if (!id.matches(Regex("[A-Za-z0-9._-]{1,80}"))) {
                    return respond(client, 400, """{"error":"invalid request id"}""")
                }
                val stored = resultStore.get(id)
                    ?: return respond(client, 404, """{"error":"result not found"}""")
                val body = json.encodeToString(WebhookRunResult.serializer(), stored)
                return respond(client, 200, body)
            }

            if (method != "POST" || path != "/run") {
                return respond(client, 404, """{"error":"not found"}""")
            }
            if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
                return respond(client, 400, """{"error":"chunked encoding unsupported"}""")
            }
            val prompt = parsePrompt(request.body.toString(Charsets.UTF_8), headers["content-type"])
            if (prompt.isBlank()) return respond(client, 400, """{"error":"empty prompt"}""")

            val requestId = UUID.randomUUID().toString()
            resultStore.update(requestId, status = "queued")
            respond(client, 202, """{"accepted":true,"request_id":"$requestId","result_url":"/result/$requestId"}""")
            scope.launch {
                executionMutex.withLock {
                    resultStore.update(requestId, status = "running")
                    runCatching { engine.runAutomationPrompt(prompt) }
                        .onSuccess { result ->
                            resultStore.update(requestId, status = "completed", result = result)
                        }
                        .onFailure { error ->
                            resultStore.update(
                                requestId,
                                status = "failed",
                                error = error.message ?: error::class.java.simpleName,
                            )
                        }
                }
            }
        }
    }

    private fun readHttpRequest(socket: Socket): HttpRequest? {
        val input = socket.getInputStream()
        val headerBytes = ArrayList<Byte>(512)
        var state = 0
        while (headerBytes.size < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) return null
            val byte = value.toByte()
            headerBytes += byte
            state = when {
                state == 0 && value == '\r'.code -> 1
                state == 1 && value == '\n'.code -> 2
                state == 2 && value == '\r'.code -> 3
                state == 3 && value == '\n'.code -> 4
                value == '\r'.code -> 1
                else -> 0
            }
            if (state == 4) break
        }
        if (state != 4) return null

        val headerText = headerBytes.toByteArray().toString(Charsets.US_ASCII)
        val lines = headerText.split("\r\n")
        val requestLine = lines.firstOrNull()?.split(' ') ?: return null
        if (requestLine.size < 2) return null
        val headers = linkedMapOf<String, String>()
        lines.drop(1).forEach { line ->
            if (line.isBlank()) return@forEach
            val index = line.indexOf(':')
            if (index > 0) {
                headers[line.substring(0, index).trim().lowercase()] =
                    line.substring(index + 1).trim()
            }
        }

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length !in 0..MAX_BODY_BYTES) return null
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(body, offset, length - offset)
            if (read < 0) return null
            offset += read
        }
        return HttpRequest(
            method = requestLine[0].uppercase(),
            path = requestLine[1],
            headers = headers,
            body = body,
        )
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private fun parsePrompt(body: String, contentType: String?): String {
        if (contentType?.contains("application/json", ignoreCase = true) == true) {
            return runCatching {
                json.parseToJsonElement(body).jsonObject["prompt"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }.getOrDefault("")
        }
        return body
    }

    private fun respond(socket: Socket, code: Int, body: String) {
        val label = when (code) {
            200 -> "OK"
            202 -> "Accepted"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            else -> "Error"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $code $label\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        socket.getOutputStream().apply {
            write(header)
            write(bytes)
            flush()
        }
    }

    private fun constantTimeEquals(expected: String, actual: String): Boolean =
        MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            actual.toByteArray(Charsets.UTF_8),
        )

    companion object {
        private const val CHANNEL_ID = "harness_webhook"
        private const val NOTIFICATION_ID = 7711
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_BODY_BYTES = 64 * 1024
    }
}

class WebhookPlugin(
    private val controller: WebhookController,
) : HarnessPlugin {
    override val id: String = "android-webhook"

    override suspend fun install(context: HarnessContext) {
        context.tools.register(
            HarnessTool(
                name = "webhook_start",
                schema = schema(
                    "webhook_start",
                    "启动受令牌保护的 Harness Webhook；默认仅监听 127.0.0.1",
                    mapOf("port" to "integer"),
                ),
                access = ToolAccess.NETWORK,
                approvalPolicy = ToolApprovalPolicy.ALWAYS,
                executor = HarnessToolExecutor { _, input, _ ->
                    ToolResult(
                        controller.start(
                            port = input["port"]?.jsonPrimitive?.content?.toIntOrNull() ?: 8765,
                            allowLan = input["allow_lan"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                        ),
                    )
                },
            ),
        )
        context.tools.register(
            HarnessTool(
                name = "webhook_status",
                schema = schema("webhook_status", "读取 Harness Webhook 状态"),
                access = ToolAccess.READ_ONLY,
                executor = HarnessToolExecutor { _, _, _ ->
                    val state = controller.status()
                    ToolResult(
                        "enabled=${state.enabled}\\nport=${state.port}\\nallow_lan=${state.allowLan}\\ntoken=${state.tokenHint ?: "none"}",
                    )
                },
            ),
        )
        context.tools.register(
            HarnessTool(
                name = "webhook_stop",
                schema = schema("webhook_stop", "停止 Harness Webhook"),
                access = ToolAccess.NETWORK,
                approvalPolicy = ToolApprovalPolicy.ALWAYS,
                executor = HarnessToolExecutor { _, _, _ ->
                    ToolResult(controller.stop().toString())
                },
            ),
        )
        context.tools.register(
            HarnessTool(
                name = "webhook_copy_token",
                schema = schema(
                    "webhook_copy_token",
                    "将 Harness Webhook 完整访问令牌复制到系统敏感剪贴板；令牌不会返回给模型",
                ),
                access = ToolAccess.PRIVILEGED,
                approvalPolicy = ToolApprovalPolicy.ALWAYS,
                executor = HarnessToolExecutor { _, _, _ ->
                    ToolResult(controller.copyTokenToClipboard())
                },
            ),
        )
        context.tools.register(
            HarnessTool(
                name = "webhook_rotate_token",
                schema = schema("webhook_rotate_token", "轮换 Harness Webhook 访问令牌"),
                access = ToolAccess.PRIVILEGED,
                approvalPolicy = ToolApprovalPolicy.ALWAYS,
                executor = HarnessToolExecutor { _, _, _ ->
                    ToolResult(controller.rotateToken())
                },
            ),
        )
    }

    override suspend fun uninstall(context: HarnessContext) {
        listOf(
            "webhook_start",
            "webhook_status",
            "webhook_stop",
            "webhook_copy_token",
            "webhook_rotate_token",
        ).forEach(context.tools::unregister)
    }

    private fun schema(
        name: String,
        description: String,
        properties: Map<String, String> = emptyMap(),
    ) = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("description", description)
            put("parameters", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    properties.forEach { (key, type) ->
                        put(key, buildJsonObject { put("type", type) })
                    }
                })
                put("additionalProperties", false)
            })
        })
    }
}
