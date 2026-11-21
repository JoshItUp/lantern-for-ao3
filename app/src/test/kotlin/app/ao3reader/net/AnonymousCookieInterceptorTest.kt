package app.ao3reader.net

import app.ao3reader.ao3.net.Anonymous
import app.ao3reader.ao3.net.Ao3Fetcher
import app.ao3reader.ao3.net.FetchResult
import kotlinx.coroutines.test.runTest
import okhttp3.Cache
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AnonymousCookieInterceptorTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer

    /** Minimal in-memory jar keyed by cookie name (one host). */
    private class MemoryJar : CookieJar {
        val cookies = linkedMapOf<String, Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { this.cookies[it.name] = it }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.values.toList()
        fun value(name: String) = cookies[name]?.value
    }

    private val jar = MemoryJar()
    private lateinit var client: OkHttpClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        val url = server.url("/")
        for ((n, v) in listOf("_otwarchive_session" to "sess", "remember_user_token" to "tok", "cf_clearance" to "cf", "__cf_bm" to "bm")) {
            jar.cookies[n] = Cookie.Builder().name(n).value(v).hostOnlyDomain(url.host).path("/").build()
        }
        client = OkHttpClient.Builder().cookieJar(jar).addNetworkInterceptor(AnonymousCookieInterceptor()).build()
    }

    @After fun tearDown() { server.shutdown() }

    private fun call(anonymous: Boolean) {
        val req = Request.Builder().url(server.url("/works/1")).apply {
            if (anonymous) tag(Anonymous::class.java, Anonymous)
        }.build()
        client.newCall(req).execute().close()
    }

    private fun sentCookies(): Map<String, String> =
        server.takeRequest().getHeader("Cookie").orEmpty().split(";").map { it.trim() }.filter { it.isNotEmpty() }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test fun `anonymous request omits the session cookies but keeps cloudflare ones`() {
        server.enqueue(MockResponse().setBody("ok"))
        call(anonymous = true)
        val sent = sentCookies()
        assertFalse(sent.containsKey("_otwarchive_session"))
        assertFalse(sent.containsKey("remember_user_token"))
        assertEquals("cf", sent["cf_clearance"])
        assertEquals("bm", sent["__cf_bm"])
    }

    @Test fun `anonymous with only session cookies sends no cookie header`() {
        jar.cookies.remove("cf_clearance"); jar.cookies.remove("__cf_bm")
        server.enqueue(MockResponse().setBody("ok"))
        call(anonymous = true)
        assertEquals(null, server.takeRequest().getHeader("Cookie"))
    }

    @Test fun `anonymous response cannot replace the stored session`() {
        server.enqueue(
            MockResponse().setBody("ok")
                .addHeader("Set-Cookie", "_otwarchive_session=new; path=/")
                .addHeader("Set-Cookie", "remember_user_token=new; path=/")
                .addHeader("Set-Cookie", "cf_clearance=cf2; path=/")
        )
        call(anonymous = true)
        assertEquals("sess", jar.value("_otwarchive_session"))
        assertEquals("tok", jar.value("remember_user_token"))
        assertEquals("cf2", jar.value("cf_clearance"))
    }

    @Test fun `non-anonymous requests are unchanged`() {
        server.enqueue(
            MockResponse().setBody("ok")
                .addHeader("Set-Cookie", "_otwarchive_session=new; path=/")
        )
        call(anonymous = false)
        val sent = sentCookies()
        assertEquals("sess", sent["_otwarchive_session"])
        assertEquals("tok", sent["remember_user_token"])
        assertEquals("cf", sent["cf_clearance"])
        assertEquals("new", jar.value("_otwarchive_session"))
        assertTrue(jar.cookies.containsKey("remember_user_token"))
    }

    @Test fun `anonymous redirect strips session cookies and ignores Set-Cookie on both hops`() {
        server.enqueue(
            MockResponse().setResponseCode(302).setHeader("Location", "/works/2")
                .addHeader("Set-Cookie", "_otwarchive_session=hop1; path=/")
        )
        server.enqueue(
            MockResponse().setBody("ok")
                .addHeader("Set-Cookie", "remember_user_token=hop2; path=/")
                .addHeader("Set-Cookie", "_otwarchive_session=hop2; path=/")
        )
        call(anonymous = true)
        assertEquals(2, server.requestCount)
        for (path in listOf("/works/1", "/works/2")) {
            val req = server.takeRequest()
            assertEquals(path, req.path)
            val sent = req.getHeader("Cookie").orEmpty()
            assertFalse(sent, sent.contains("_otwarchive_session"))
            assertFalse(sent, sent.contains("remember_user_token"))
            assertTrue(sent, sent.contains("cf_clearance=cf"))
        }
        assertEquals("sess", jar.value("_otwarchive_session"))
        assertEquals("tok", jar.value("remember_user_token"))
    }

    @Test fun `anonymous fetch never gets a cached signed-in response or its Set-Cookie`() = runTest {
        val cached = OkHttpClient.Builder()
            .cookieJar(jar)
            .addNetworkInterceptor(AnonymousCookieInterceptor())
            .cache(Cache(tmp.newFolder("http"), 1024L * 1024))
            .build()
        val fetcher = Ao3Fetcher(cached, "TestAgent/1.0", { true }, sleep = { })
        server.enqueue(
            MockResponse().setBody("signed-in page")
                .setHeader("Cache-Control", "max-age=600")
                .addHeader("Set-Cookie", "_otwarchive_session=from-cache; path=/")
        )
        server.enqueue(MockResponse().setBody("anonymous page").setHeader("Cache-Control", "max-age=600"))
        val url = server.url("/works/1")

        assertEquals("signed-in page", (fetcher.get(url) as FetchResult.Ok).html)
        // The session moved on since that response was cached.
        jar.cookies["_otwarchive_session"] = jar.cookies.getValue("_otwarchive_session").let {
            Cookie.Builder().name(it.name).value("current").hostOnlyDomain(url.host).path("/").build()
        }

        assertEquals("anonymous page", (fetcher.get(url, anonymous = true) as FetchResult.Ok).html)
        assertEquals(2, server.requestCount)
        assertEquals("current", jar.value("_otwarchive_session"))
    }

    @Test fun `anonymous response is not cached for a later signed-in fetch`() = runTest {
        val cached = OkHttpClient.Builder()
            .cookieJar(jar)
            .addNetworkInterceptor(AnonymousCookieInterceptor())
            .cache(Cache(tmp.newFolder("http"), 1024L * 1024))
            .build()
        val fetcher = Ao3Fetcher(cached, "TestAgent/1.0", { true }, sleep = { })
        server.enqueue(MockResponse().setBody("anonymous page").setHeader("Cache-Control", "max-age=600"))
        server.enqueue(MockResponse().setBody("signed-in page"))
        val url = server.url("/works/1")
        assertEquals("anonymous page", (fetcher.get(url, anonymous = true) as FetchResult.Ok).html)
        assertEquals("signed-in page", (fetcher.get(url) as FetchResult.Ok).html)
        assertEquals(2, server.requestCount)
    }
}
