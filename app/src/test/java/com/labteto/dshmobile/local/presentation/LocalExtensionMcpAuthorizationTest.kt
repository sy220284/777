package com.labteto.dshmobile.local.presentation

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalExtensionMcpAuthorizationTest {
    private fun request(id: String, endpoint: String = "https://example.com/$id") =
        LocalExtensionMcpAuthorization(id, endpoint)

    @Test fun failingDependencyDoesNotPreventIndependentInstallationAndReportsFailures() = runBlocking {
        val attempted = mutableListOf<String>()
        val started = mutableListOf<String>()
        val finished = mutableListOf<String>()
        val failures = authorizeLocalExtensionMcpBatch(
            requests = listOf(request("first"), request("broken"), request("third")),
            authorize = { item ->
                attempted += item.serverId
                if (item.serverId == "broken") throw IOException("connection refused")
            },
            onStart = { item, index, total ->
                started += "${item.serverId}:$index/$total"
            },
            onFinish = { finished += it.serverId },
        )
        assertEquals(listOf("first", "broken", "third"), attempted)
        assertEquals(listOf("first:1/3", "broken:2/3", "third:3/3"), started)
        assertEquals(attempted, finished)
        assertEquals(listOf("broken"), failures.map { it.serverId })
        assertEquals("connection refused", failures.single().message)
    }

    @Test fun deniedAuthorizationStopsFollowingBrowserPromptsAndReleasesProgress() = runBlocking {
        val attempted = mutableListOf<String>()
        val finished = mutableListOf<String>()
        val outcome = runCatching {
            authorizeLocalExtensionMcpBatch(
                listOf(request("granted"), request("denied"), request("not-attempted")),
                authorize = { item ->
                    attempted += item.serverId
                    if (item.serverId == "denied") throw CancellationException("consent denied")
                },
                onFinish = { finished += it.serverId },
            )
        }
        assertTrue(outcome.exceptionOrNull() is CancellationException)
        assertEquals(listOf("granted", "denied"), attempted)
        assertEquals(attempted, finished)
    }

    @Test fun duplicateOfficialAndSkillRequirementNeverRepeatsLogin() = runBlocking {
        var opens = 0
        val failures = authorizeLocalExtensionMcpBatch(
            listOf(request("figma"), request("figma"), request("other")),
            authorize = { opens++ },
        )
        assertTrue(failures.isEmpty())
        assertEquals(2, opens)
    }

    @Test fun mismatchedEndpointsForSameServiceCannotSilentlyAuthorizeWrongAccount() = runBlocking {
        var opens = 0
        val result = runCatching {
            authorizeLocalExtensionMcpBatch(
                listOf(request("figma", "https://a.example/mcp"),
                    request("figma", "https://b.example/mcp")),
                authorize = { opens++ },
            )
        }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, opens)
    }
}
