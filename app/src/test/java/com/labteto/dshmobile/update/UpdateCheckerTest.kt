package com.labteto.dshmobile.update

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun `mirror is preferred and keeps update traffic off GitHub`() = runBlocking {
        val requestedHosts = mutableListOf<String>()
        val checker = checker("https://mirror.example/777/") { url ->
            requestedHosts += url.host
            when {
                url.host == "mirror.example" && url.encodedPath == "/777/latest.json" ->
                    200 to mirrorRelease
                else -> error("unexpected request: $url")
            }
        }

        val update = checker.checkNow("0.12.0-777.49")
        assertNotNull(update)
        assertEquals("0.12.0-777.50", update!!.version)
        assertEquals(
            "https://mirror.example/777/releases/v0.12.0-777.50/app-release.apk",
            update.apkUrl,
        )
        assertEquals(1, update.patchChain.size)
        assertEquals(
            "https://mirror.example/777/releases/v0.12.0-777.50/delta-49-to-50.hpatch",
            update.patchChain.single().url,
        )
        assertEquals(listOf("mirror.example"), requestedHosts)
    }

    @Test
    fun `reachable current mirror is authoritative and does not probe GitHub`() = runBlocking {
        val requestedHosts = mutableListOf<String>()
        val checker = checker("https://mirror.example/777/") { url ->
            requestedHosts += url.host
            200 to mirrorRelease
        }

        assertNull(checker.checkNow("0.12.0-777.50"))
        assertEquals(listOf("mirror.example"), requestedHosts)
    }

    @Test
    fun `invalid mirror metadata falls back to GitHub`() = runBlocking {
        val requestedHosts = mutableListOf<String>()
        val invalidMirror = mirrorRelease.replace(
            "releases/v0.12.0-777.50/app-release.apk",
            "../app-release.apk",
        )
        val checker = checker("https://mirror.example/777/") { url ->
            requestedHosts += url.host
            when {
                url.host == "mirror.example" -> 200 to invalidMirror
                url.encodedPath.endsWith("/latest") -> 200 to release
                else -> 403 to "{}"
            }
        }

        val update = checker.checkNow("0.12.0-777.49")
        assertEquals(
            "https://github.com/sy220284/777/releases/download/v0.12.0-777.50/app-release.apk",
            update?.apkUrl,
        )
        assertEquals("mirror.example", requestedHosts.first())
        assertEquals("api.github.com", requestedHosts[1])
    }

    @Test
    fun `latest release still offers full APK when release history fails`() = runBlocking {
        val checker = checker { url ->
            if (url.encodedPath.endsWith("/latest")) 200 to release
            else 403 to "{}"
        }
        val update = checker.checkNow("0.12.0-777.49")
        assertNotNull(update)
        assertEquals("0.12.0-777.50", update!!.version)
        assertEquals(
            "https://github.com/sy220284/777/releases/download/v0.12.0-777.50/app-release.apk",
            update.apkUrl,
        )
        assertEquals(emptyList<DeltaPatch>(), update.patchChain)
    }

    @Test
    fun `release list can recover when latest endpoint fails`() = runBlocking {
        val checker = checker { url ->
            if (url.encodedPath.endsWith("/latest")) 403 to "{}"
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
        assertNull(
            releaseFromWebsiteUrl(
                "https://example.com/sy220284/777/releases/tag/v0.12.0-777.50".toHttpUrl(),
            ),
        )
        assertNull(
            releaseFromWebsiteUrl(
                "https://github.com/other/777/releases/tag/v0.12.0-777.50".toHttpUrl(),
            ),
        )
        assertNull(
            releaseFromWebsiteUrl(
                "https://github.com/sy220284/777/releases/tag/nightly".toHttpUrl(),
            ),
        )
    }

    @Test
    fun `mirror base and asset paths stay on one HTTPS origin`() {
        val base = parseUpdateMirrorBaseUrl("https://bucket.oss-cn-hangzhou.aliyuncs.com/777")
        assertNotNull(base)
        assertEquals(
            "https://bucket.oss-cn-hangzhou.aliyuncs.com/777/releases/v1/app.apk",
            resolveMirrorAssetUrl(base!!, "releases/v1/app.apk"),
        )
        assertNull(resolveMirrorAssetUrl(base, "../app.apk"))
        assertNull(resolveMirrorAssetUrl(base, "/app.apk"))
        assertNull(parseUpdateMirrorBaseUrl("http://bucket.example/777/"))
        assertNull(parseUpdateMirrorBaseUrl("https://user@bucket.example/777/"))
    }

    private fun checker(
        mirrorBaseUrl: String = "",
        result: (HttpUrl) -> Pair<Int, String>,
    ): UpdateChecker {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val (status, body) = result(chain.request().url)
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("test")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        return UpdateChecker(client, mirrorBaseUrl)
    }

    private val release = """
        {"tag_name":"v0.12.0-777.50","html_url":"https://github.com/sy220284/777/releases/tag/v0.12.0-777.50",
        "assets":[{"name":"app-release.apk","browser_download_url":"https://github.com/sy220284/777/releases/download/v0.12.0-777.50/app-release.apk",
        "size":118512196,"digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
        {"name":"SHA256SUMS.txt","browser_download_url":"https://github.com/sy220284/777/releases/download/v0.12.0-777.50/SHA256SUMS.txt"}]}
    """.trimIndent()

    private val mirrorRelease = """
        {
          "schema":1,
          "version":"0.12.0-777.50",
          "apk":{
            "name":"app-release.apk",
            "path":"releases/v0.12.0-777.50/app-release.apk",
            "size":118512196,
            "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          },
          "patches":[{
            "fromVersion":"0.12.0-777.49",
            "toVersion":"0.12.0-777.50",
            "algorithm":"hdiffpatch-window-zstd-v1",
            "path":"releases/v0.12.0-777.50/delta-49-to-50.hpatch",
            "size":1024,
            "sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "sourceSha256":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
            "targetSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          }]
        }
    """.trimIndent()
}
