package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.observability.AppLog
import com.labteto.dshmobile.local.LocalModelException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** A small real completion verifies the credential, route and model without saving a draft. */
class LocalModelConnectionTester @Inject constructor(private val gateway: LocalModelGateway) {
    suspend fun test(
        key: String,
        url: String,
        model: String,
        protocol: LocalModelProtocol = LocalModelPresets.protocolFor(model, url),
    ): String = runProbe(key) {
        gateway.probeApiKey(
            apiKey = key,
            baseUrl = url,
            model = model,
            protocol = protocol,
        )
    }

    internal suspend fun testStoredRoute(
        apiKey: String,
        model: String,
        baseUrl: String,
        protocol: LocalModelProtocol?,
        profiles: List<LocalModelProfile>,
        readKey: suspend (String) -> String?,
        profileId: String? = null,
    ): String {
        if (model.isBlank()) return "请选择模型"
        val draft = try {
            resolveLocalModelApiKeyDraft(apiKey, model, baseUrl, protocol, profiles, readKey, profileId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IllegalArgumentException) {
            AppLog.warn("ModelProbe", "failure=true operation=resolve_stored_model_route cause_type=IllegalArgumentException")
            return error.message ?: "模型配置无效"
        } catch (error: Exception) {
            AppLog.warn("ModelProbe", "failure=true operation=resolve_stored_model_route failure_kind=${modelProbeFailureKind(error)}")
            val message = error.message.orEmpty().let { raw ->
                apiKey.trim().takeIf(String::isNotBlank)?.let { raw.replace(it, "••••") } ?: raw
            }
            return "连接失败：${message.take(240)}"
        }
        return test(draft.key, draft.profile.baseUrl, draft.profile.model, draft.profile.protocol)
    }

    suspend fun testProfile(profile: LocalModelProfile): String = runProbe {
        gateway.probeProfile(profile)
    }

    private suspend fun runProbe(
        secret: String? = null,
        block: suspend () -> Unit,
    ): String = try {
        withTimeout(20_000L) { block() }
        "连接成功，模型已完成实际响应"
    } catch (error: CancellationException) {
        if (error is TimeoutCancellationException) {
            AppLog.warn("ModelProbe", "failure=true operation=test_model_connection cause_type=TimeoutCancellationException")
            "检测超时，请检查网络或接口地址"
        } else throw error
    } catch (error: Exception) {
        AppLog.warn("ModelProbe", "failure=true operation=test_model_connection failure_kind=${modelProbeFailureKind(error)}${(error as? LocalModelException)?.let { " code=${it.code} status=${it.status ?: 0} origin=${it.origin?.wireValue ?: "unspecified"}" }.orEmpty()}")
        val message = error.message.orEmpty().let { raw ->
            secret?.takeIf(String::isNotBlank)?.let { raw.replace(it, "••••") } ?: raw
        }
        "连接失败：${message.take(240)}"
    }
}

/** Stable categories independent of R8 class names; no credential values. */
internal fun modelProbeFailureKind(error: Throwable): String = when {
    error is LocalModelException && error.status == 401 -> "invalid_credentials"
    error is LocalModelException && error.status == 403 -> "provider_forbidden"
    error is LocalModelException && error.status == 429 -> "provider_rate_limited"
    error is LocalModelException && error.code.contains("PROTOCOL") -> "protocol_mismatch"
    error is LocalModelException && error.code.contains("CONTEXT") -> "context_limit"
    error is UnknownHostException -> "dns_failure"
    error is SSLException -> "tls_failure"
    error is ConnectException -> "connection_refused"
    error is SocketTimeoutException || error is TimeoutCancellationException -> "probe_timeout"
    error is LocalModelException -> "model_${error.code.take(48)}"
    error is IllegalArgumentException -> "invalid_route"
    else -> "unknown_failure"
}
