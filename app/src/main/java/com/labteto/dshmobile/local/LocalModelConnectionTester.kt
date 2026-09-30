package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.LocalModelGateway
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** A small real completion verifies the key, route and model without saving the draft. */
class LocalModelConnectionTester @Inject constructor(private val gateway: LocalModelGateway) {
    suspend fun test(
        key: String,
        url: String,
        model: String,
    ): String = try {
        withTimeout(20_000L) {
            gateway.probeApiKey(
                apiKey = key,
                baseUrl = url,
                model = model,
                protocol = LocalModelPresets.protocolFor(model, url),
            )
        }
        "连接成功，模型已返回响应"
    } catch (error: CancellationException) {
        if (error is TimeoutCancellationException) "检测超时，请检查网络或接口地址" else throw error
    } catch (error: Exception) {
        "连接失败：${error.message.orEmpty().replace(key, "••••").take(240)}"
    }
}
