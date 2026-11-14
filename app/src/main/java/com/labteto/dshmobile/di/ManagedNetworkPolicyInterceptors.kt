package com.labteto.dshmobile.di

import okhttp3.OkHttpClient
import okhttp3.Request
import com.labteto.dshmobile.local.web.PinnedWebRequestOrigin

/**
 * Application interception alone misses OkHttp's internal follow-up redirects and route attempts.
 * Enforce policy twice: at original call admission (including cached responses), and at each
 * physical network exchange before headers, cookies or API tokens leave the device.
 */
internal fun OkHttpClient.Builder.enforceManagedNetworkDestinations(
    requireAllowed: (scheme: String, host: String) -> Unit,
): OkHttpClient.Builder = apply {
    fun checkDestination(request: Request) {
        val route = request.tag(PinnedWebRequestOrigin::class.java)
        if (route == null) {
            requireAllowed(request.url.scheme, request.url.host)
        } else {
            // The web resolver has already checked the original host and pinned the
            // public IP. A proxy must not turn that pin into an unrelated whitelist key.
            check(route.scheme.equals(request.url.scheme, ignoreCase = true) &&
                route.pinnedHost.equals(request.url.host, ignoreCase = true)) {
                "代理请求目标与已验证路由不一致"
            }
            requireAllowed(route.scheme, route.host)
        }
    }
    // First check prevents disallowed calls even if a cached response is available.
    addInterceptor { chain ->
        checkDestination(chain.request())
        chain.proceed(chain.request())
    }
    // OkHttp can follow redirects internally; keep this check for every actual HTTP exchange.
    addNetworkInterceptor { chain ->
        checkDestination(chain.request())
        chain.proceed(chain.request())
    }
}
