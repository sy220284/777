package com.labteto.dshmobile.di

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedNetworkPolicyInterceptorsTest {
    @Test fun redirectToNewHostIsDeniedBeforeAuthorizationHeaderLeavesDevice() {
        val reached = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/start") { exchange ->
            exchange.responseHeaders.add("Location", "http://localhost:${server.address.port}/secret")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/secret") { exchange ->
            reached.incrementAndGet()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            val checked = mutableListOf<String>()
            val client = OkHttpClient.Builder()
                .enforceManagedNetworkDestinations { _, host ->
                    checked += host
                    check(host == "127.0.0.1") { "禁止向未授权地址发送凭据" }
                }.build()
            var rejected = false
            try {
                client.newCall(Request.Builder()
                    .url("http://127.0.0.1:${server.address.port}/start")
                    .header("Authorization", "Bearer sensitive-value")
                    .build()).execute().use { response ->
                    // A redirected response must never be successfully delivered.
                    rejected = !response.isSuccessful
                }
            } catch (_: IllegalStateException) {
                rejected = true
            }
            assertTrue("重定向访问的管理策略必须拒绝", rejected)
            assertTrue("跳转目标必须被实际逐跳检查", "localhost" in checked)
            assertEquals("未经授权的地址不能收到请求", 0, reached.get())
        } finally {
            server.stop(0)
        }
    }

    @Test fun initialRequestIsCheckedBeforeNetworkAttempt() {
        val checked = mutableListOf<String>()
        val client = OkHttpClient.Builder().enforceManagedNetworkDestinations { _, host ->
            checked += host
            check(host != "127.0.0.1") { "主动拒绝初始请求" }
        }.build()
        var denied = false
        try {
            client.newCall(Request.Builder().url("http://127.0.0.1:1/denied").build())
                .execute().close()
        } catch (_: IllegalStateException) {
            denied = true
        }
        assertTrue(denied)
        assertEquals(listOf("127.0.0.1"), checked)
    }
}
