package com.labteto.dshmobile.di

import okhttp3.OkHttpClient

/**
 * Application interception alone misses OkHttp's internal follow-up redirects and route attempts.
 * Enforce policy twice: at original call admission (including cached responses), and at each
 * physical network exchange before headers, cookies or API tokens leave the device.
 */
internal fun OkHttpClient.Builder.enforceManagedNetworkDestinations(
    requireAllowed: (scheme: String, host: String) -> Unit,
): OkHttpClient.Builder = apply {
    addInterceptor { chain ->
        val url = chain.request().url
        requireAllowed(url.scheme, url.host)
        chain.proceed(chain.request())
    }
    addNetworkInterceptor { chain ->
        val url = chain.request().url
        requireAllowed(url.scheme, url.host)
        chain.proceed(chain.request())
    }
}
