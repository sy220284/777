package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.RpcResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns remote session content-search results and per-connection capability latching. */
internal class SessionSearchRuntime(
    private val apiForHost: (String?) -> DshApiClient?,
    private val activeHostKey: () -> String?,
) {
    private val _results = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val results: StateFlow<List<Pair<String, String>>> = _results.asStateFlow()

    private val _available = MutableStateFlow(true)
    val available: StateFlow<Boolean> = _available.asStateFlow()
    private val requests = SessionAsyncRequestRegistry()

    fun resetCapability() {
        requests.reset()
        _available.value = true
        _results.value = emptyList()
    }

    suspend fun search(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _results.value = emptyList()
            return
        }
        if (!_available.value) return

        val request = requests.capture("search", activeHostKey(), null)
        val api = apiForHost(request.hostKey) ?: run {
            if (request.isCurrent(activeHostKey) { null }) _results.value = emptyList()
            return
        }
        val result = api.sessionSearch(trimmed.take(SESSION_SEARCH_QUERY_MAX_CHARS))
        if (
            !request.isCurrent(activeHostKey) { null } ||
            !isCurrentHostRequest(request.hostKey, api, activeHostKey, apiForHost)
        ) return
        when (result) {
            is RpcResult.Ok -> {
                _results.value = result.value.items.map { it.sessionId to it.snippet }
            }
            is RpcResult.Err -> {
                _available.value = false
                _results.value = emptyList()
            }
        }
    }

    private companion object {
        const val SESSION_SEARCH_QUERY_MAX_CHARS = 500
    }
}
