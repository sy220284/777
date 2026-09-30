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
    fun `Gitee mirror is preferred and keeps update check off GitHub`() = runBlocking {
        val requested = mutableListOf<String>()
        val checker = checker(GITEE_MIRROR) { url ->
            requested += url.toString()
            when (url.encodedPath) {
                "/api/v5/repos/acme/777-mirror/releases/latest" -> 200 to giteeRelease
                "/acme/777-mirror/releases/download/v0.12.0-777.50/update-manifest.json" ->
                    200 to updateManifest
                else -> error("unexpected request: $url")
            }
        }

        val update = checker.checkNow("0.12.0-777.49")
        assertNotNull(update)
        assertEquals("0.12.0-777.50", update!!.version)
        assertEquals(
            "https://gitee.com/acme/777-mirror/releases/download/v0.12.0-777.50/app-release.apk",
            update.apkUrl,
        )
        assertEquals(1, update.patchChain.size)
        assertEquals(
            "https://gitee.com/acme/777-mirror/releases/download/v0.12.0-777.50/delta-49-to-50.hpatch",
            update.patchChain.single().url,
        )
        assertEquals(2, requested.size)
        assertEquals(listOf("gitee.com", "gitee.com"), requested.map { it.toHttpUrl().host })
    }

    @Test
    fun `reachable current Gitee mirror is authoritative and does not probe GitHub`() = runBlocking {
        val requestedHosts = mutableListOf<String>()
        val checker = checker(GITEE_MIRROR) { url ->
            requestedHosts += url.host
            200 to giteeRelease
        }

        assertNull(checker.checkNow("0.12.0-777.50"))
        assertEquals(listOf("gitee.com"), requestedHosts)
    }

    @Test
    fun `invalid Gitee manifest falls back to GitHub`() = runBlocking {
        val requestedHosts = mutableListOf<String>()
        val invalidManifest = updateManifest.replace(
            "\"name\":\"app-release.apk\"",
            "\"name\":\"../app-release.apk\"",
        )
        val checker = checker(GITEE_MIRROR) { url ->
            requestedHosts += url.host
            when {
                url.encodedPath == "/api/v5/repos/acme/777-mirror/releases/latest" ->
                    200 to giteeRelease
                url.host == "gitee.com" -> 200 to invalidManifest
                url.encodedPath.endsWith("/latest") -> 200 to release
                else -> 403 to "{}"
            }
        }

        val update = checker.checkNow("0.12.0-777.49")
        assertEquals(
            "https://github.com/sy220284/777/releases/download/v0.12.0-777.50/app-release.apk",
            update?.apkUrl,
        )
        assertEquals(
            listOf("gitee.com", "gitee.com", "api.github.com", "api.github.com"),
            requestedHosts,
        )
    }

    @Test
    fun `Gitee repo base and asset routes are constrained`() {
        val mirror = parseGiteeMirrorRepository(GITEE_MIRROR)
        assertNotNull(mirror)
        assertEquals("acme", mirror!!.owner)
        assertEquals("777-mirror", mirror.repo)
        assertEquals(
            "https://gitee.com/acme/777-mirror/releases/download/v0.12.0-777.50/app-release.apk",
            giteeReleaseAssetUrl(mirror, "v0.12.0-777.50", "app-release.apk"),
        )
        assertNull(giteeReleaseAssetUrl(mirror, "v0.12.0-777.50", "../app-release.apk"))
        assertNull(parseGiteeMirrorRepository("http://gitee.com/acme/777-mirror/"))
        assertNull(parseGiteeMirrorRepository("https://example.com/acme/777-mirror/"))
        assertNull(parseGiteeMirrorRepository("https://gitee.com/acme/777-mirror/extra/"))
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

    private val giteeRelease = """
        {"tag_name":"v0.12.0-777.50","prerelease":false}
    """.trimIndent()

    private val updateManifest = """
        {
          "schema":1,
          "targetVersion":"0.12.0-777.50",
          "targetApk":{
            "name":"app-release.apk",
            "size":63939424,
            "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          },
          "patches":[{
            "fromVersion":"0.12.0-777.49",
            "toVersion":"0.12.0-777.50",
            "algorithm":"hdiffpatch-window-zstd-v1",
            "asset":"delta-49-to-50.hpatch",
            "size":1024,
            "sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "sourceSha256":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
            "targetSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          }]
        }
    """.trimIndent()

    private companion object {
        const val GITEE_MIRROR = "https://gitee.com/acme/777-mirror/"
    }
}
