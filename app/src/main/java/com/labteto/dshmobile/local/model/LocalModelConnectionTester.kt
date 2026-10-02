package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.resolveLocalModelApiKeyDraft
import com.labteto.dshmobile.local.model.LocalModelGateway
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
            return error.message ?: "模型配置无效"
        } catch (error: Exception) {
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
        if (error is TimeoutCancellationException) "检测超时，请检查网络或接口地址" else throw error
    } catch (error: Exception) {
        val message = error.message.orEmpty().let { raw ->
            secret?.takeIf(String::isNotBlank)?.let { raw.replace(it, "••••") } ?: raw
        }
        "连接失败：${message.take(240)}"
    }
}
