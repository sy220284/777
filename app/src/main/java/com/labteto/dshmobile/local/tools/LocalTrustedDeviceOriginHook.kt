package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.tools.ToolLifecycleHook
import com.labteto.dshmobile.harness.tools.ToolPreUseDecision
import com.labteto.dshmobile.harness.tools.ToolPreUseRequest
import com.labteto.dshmobile.local.security.LocalEffectiveExecutionPolicy
import java.net.URI
import java.net.URISyntaxException
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Trusted browser-origin pre-use rule. The Android provider rechecks at execution time,
 * after tool approval, so a managed policy update cannot be bypassed by a pending prompt.
 */
internal class LocalTrustedDeviceOriginHook(
    private val policy: () -> LocalEffectiveExecutionPolicy,
) : ToolLifecycleHook {
    override suspend fun beforeUse(request: ToolPreUseRequest): ToolPreUseDecision {
        if (request.tool.name != "android_open_uri") return ToolPreUseDecision.Allow
        val raw = request.input["uri"]?.jsonPrimitive?.contentOrNull
            ?: return ToolPreUseDecision.Deny("网页操作缺少目标地址")
        val uri = try { URI(raw) } catch (_: URISyntaxException) {
            return ToolPreUseDecision.Deny("网页地址无效")
        }
        val scheme = uri.scheme.orEmpty()
        // Non-web schemes continue through Android's existing device permissions.
        if (!scheme.equals("http", true) && !scheme.equals("https", true))
            return ToolPreUseDecision.Allow
        if (uri.isOpaque || uri.rawUserInfo != null || uri.host.isNullOrBlank())
            return ToolPreUseDecision.Deny("网页地址包含无效主机或用户凭据")
        return try {
            policy().requireBrowserOrigin(scheme, uri.host, "read")
            ToolPreUseDecision.Allow
        } catch (_: IllegalArgumentException) {
            ToolPreUseDecision.Deny("网页来源未获当前设备策略许可")
        } catch (_: IllegalStateException) {
            ToolPreUseDecision.Deny("网页来源未获当前设备策略许可")
        }
    }
}
