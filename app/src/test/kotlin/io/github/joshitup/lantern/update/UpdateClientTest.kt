package io.github.joshitup.lantern.update

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class UpdateClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: UpdateClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = UpdateClient(OkHttpClient(), server.url("/"), "Lantern/1.0.0", "Owner/repo")
    }

    @After fun tearDown() = server.shutdown()

    private fun release(
        tag: String = "v1.2.3", draft: Boolean = false, prerelease: Boolean = false,
        assets: String = """[{"name":"lantern.apk","browser_download_url":"https://example.com/lantern.apk","size":1234,"content_type":"application/vnd.android.package-archive"}]""",
    ) = """{"tag_name":"$tag","name":"Lantern $tag","body":"## Added\n- thing","html_url":"https://example.com/r","draft":$draft,"prerelease":$prerelease,"unknown":{"x":1},"assets":$assets}"""

    private fun enqueue(body: String, code: Int = 200) = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    @Test fun validRelease_parsed() = runTest {
        enqueue(release())
        val r = client.latest()
        assertEquals(
            ReleaseInfo(ReleaseVersion(1, 2, 3), "Lantern v1.2.3", "## Added\n- thing", "https://example.com/lantern.apk", 1234L, "https://example.com/r"),
            r,
        )
    }

    @Test fun apkByNameOnly_accepted() = runTest {
        enqueue(release(assets = """[{"name":"a.txt","browser_download_url":"https://example.com/a.txt","size":1},{"name":"x.APK","browser_download_url":"https://example.com/x.apk","size":9,"content_type":"application/octet-stream"}]"""))
        assertEquals("https://example.com/x.apk", client.latest()?.apkUrl)
    }

    @Test fun noApkAsset_null() = runTest {
        enqueue(release(assets = """[{"name":"notes.txt","browser_download_url":"https://example.com/n.txt","size":1,"content_type":"text/plain"}]"""))
        assertNull(client.latest())
    }

    @Test fun draft_null() = runTest { enqueue(release(draft = true)); assertNull(client.latest()) }

    @Test fun prerelease_null() = runTest { enqueue(release(prerelease = true)); assertNull(client.latest()) }

    @Test fun badTag_null() = runTest {
        enqueue(release(tag = "v1.0")); assertNull(client.latest())
        enqueue(release(tag = "v1.0.0-beta")); assertNull(client.latest())
    }

    @Test fun rateLimited403_null() = runTest { enqueue("""{"message":"rate limit"}""", 403); assertNull(client.latest()) }

    @Test fun notFound404_null() = runTest { enqueue("{}", 404); assertNull(client.latest()) }

    @Test fun htmlBody_null() = runTest { enqueue("<html><body>oops</body></html>"); assertNull(client.latest()) }

    @Test fun sendsHeaders() = runTest {
        enqueue(release())
        client.latest()
        val req = server.takeRequest()
        assertEquals("/repos/Owner/repo/releases/latest", req.path)
        assertEquals("Lantern/1.0.0", req.getHeader("User-Agent"))
        assertEquals("application/vnd.github+json", req.getHeader("Accept"))
        assertNull(req.getHeader("Cookie"))
    }

    @Test fun byTag_usesTagPath() = runTest {
        enqueue(release(tag = "v2.0.0"))
        val r = client.byTag("v2.0.0")
        assertEquals("/repos/Owner/repo/releases/tags/v2.0.0", server.takeRequest().path)
        assertEquals(ReleaseVersion(2, 0, 0), r?.version)
    }

    @Test fun download_streamsBody() {
        enqueue("apkbytes")
        val resp = client.download(server.url("/f.apk").toString())
        resp.use { assertEquals("apkbytes", it.body.string()) }
        assertNotNull(server.takeRequest().getHeader("User-Agent"))
    }
}
