package com.labteto.dshmobile.local.tools.ipc

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * MCP tool calls have a 65s owner deadline and 120s Binder safety ceiling.
 * A socket becoming silent for OkHttp's default 10s is not a failed MCP call.
 * Enforce a 75s READ IDLE cap, not a 10s deadline for the entire remote tool.
 * The caller still cancels its Call and never replays tools/call on disconnect.
 */
internal fun createMcpWorkerHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(75, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .callTimeout(0, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()
