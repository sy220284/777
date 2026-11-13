package com.labteto.dshmobile.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun `latest release still offers full APK when release history fails`() = runBlocking {
        val checker = checker { path ->
            if (path.endsWith("/latest")) 200 to release
            else 403 to "{}"
        }
        val update = checker.checkNow("0.12.0-777.49")
        assertNotNull(update)
        assertEquals("0.12.0-777.50", update!!.version)
        assertEquals("https://github.com/sy220284/777/releases/download/v0.12.0-777.50/app-release.apk", update.apkUrl)
        assertEquals(emptyList<DeltaPatch>(), update.patchChain)
    }

    @Test
    fun `release list can recover when latest endpoint fails`() = runBlocking {
        val checker = checker { path ->
            if (path.endsWith("/latest")) 403 to "{}"
            else 200 to "[$release]"
        }
        assertEquals("0.12.0-777.50", checker.checkNow("0.12.0-777.49")?.version)
        assertNull(checker.checkNow("0.12.0-777.50"))
    }

    @Test
    fun `website fallback only trusts a published tag from the expected repository`() {
        val valid = releaseFromWebsiteUrl(
            "https://github.com/sy220284/777/releases/tag/v0.12.0-777.50".toHttpUrl(),
        )
        assertEquals("0.12.0-777.50", valid?.version)
        assertEquals(
            "https://github.com/sy220284/777/releases/download/v0.12.0-777.50/SHA256SUMS.txt",
            valid?.checksumUrl,
        )
        assertNull(releaseFromWebsiteUrl("https://example.com/sy220284/777/releases/tag/v0.12.0-777.50".toHttpUrl()))
        assertNull(releaseFromWebsiteUrl("https://github.com/other/777/releases/tag/v0.12.0-777.50".toHttpUrl()))
        assertNull(releaseFromWebsiteUrl("https://github.com/sy220284/777/releases/tag/nightly".toHttpUrl()))
    }

    private fun checker(result: (String) -> Pair<Int, String>): UpdateChecker {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val (status, body) = result(chain.request().url.encodedPath)
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("test")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        return UpdateChecker(client)
    }

    private val release = """
        {"tag_name":"v0.12.0-777.50","html_url":"https://github.com/sy220284/777/releases/tag/v0.12.0-777.50",
        "assets":[{"name":"app-release.apk","browser_download_url":"https://github.com/sy220284/777/releases/download/v0.12.0-777.50/app-release.apk",
        "size":118512196,"digest":"sha256:${"a".repeat(64)}"},
        {"name":"SHA256SUMS.txt","browser_download_url":"https://github.com/sy220284/777/releases/download/v0.12.0-777.50/SHA256SUMS.txt"}]}
    """.trimIndent()
}
