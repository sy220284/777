package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.capability.HarnessAndroidDeviceProvider
import com.labteto.dshmobile.local.security.LocalEffectiveExecutionPolicy
import java.net.URI

/**
 * Device UI remains owned by Android; browser origins are checked at the actual provider boundary.
 * This does not add a parallel accessibility service or weaken the existing per-tool approval gate.
 */
internal class LocalManagedDeviceProvider(
    private val delegate: HarnessAndroidDeviceProvider,
    private val policy: () -> LocalEffectiveExecutionPolicy,
) : HarnessAndroidDeviceProvider {
    override val capabilities: Set<String> get() = delegate.capabilities

    override suspend fun invoke(capability: String, arguments: Map<String, String>): String {
        if (capability == "android_open_uri") {
            val uri = URI(requireNotNull(arguments["uri"]) { "打开网址时缺少 URI" })
            if (uri.scheme.equals("http", ignoreCase = true) ||
                uri.scheme.equals("https", ignoreCase = true)) {
                require(uri.rawUserInfo == null && uri.host != null && uri.port in -1..65535) {
                    "浏览器网址包含不受信的用户凭据或主机名"
                }
                policy().requireBrowserOrigin(uri.scheme, uri.host, "read")
            }
        }
        return delegate.invoke(capability, arguments)
    }

    override suspend fun acquireAgentVirtualDisplay(owner: String): String =
        delegate.acquireAgentVirtualDisplay(owner)

    override fun releaseAgentVirtualDisplay(id: String) = delegate.releaseAgentVirtualDisplay(id)

    override fun close() = delegate.close()
}
