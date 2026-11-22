package io.github.joshitup.lantern.ao3.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class Ao3FetcherTest {
    private lateinit var server: MockWebServer
    private var now = 1_000_000L
    private val sleeps = mutableListOf<Long>()
    private var solveCalls = mutableListOf<okhttp3.HttpUrl>()
    private var solveResult = true

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun fetcher() = Ao3Fetcher(
        client = OkHttpClient(),
        userAgent = "TestAgent/1.0",
        solver = { url -> solveCalls += url; solveResult },
        minIntervalMs = 1000,
        clock = { now },
        sleep = { sleeps += it; now += it },
    )

    private val robot = "<html>our systems think you may be a robot</html>"

    @Test fun `ok returns html and sends user agent`() = runTest {
        server.enqueue(MockResponse().setBody("<html>x</html>"))
        val r = fetcher().get(server.url("/works/1"))
        assertTrue(r is FetchResult.Ok)
        assertEquals("<html>x</html>", (r as FetchResult.Ok).html)
        assertEquals("TestAgent/1.0", server.takeRequest().getHeader("User-Agent"))
    }

    @Test fun `accept sends accept and xhr headers`() = runTest {
        server.enqueue(MockResponse().setBody("[]"))
        val accept = "application/json, text/javascript, */*; q=0.01"
        assertTrue(fetcher().get(server.url("/autocomplete/fandom"), accept = accept) is FetchResult.Ok)
        val req = server.takeRequest()
        assertEquals(accept, req.getHeader("Accept"))
        assertEquals("XMLHttpRequest", req.getHeader("X-Requested-With"))
    }

    @Test fun `no accept sends neither header`() = runTest {
        server.enqueue(MockResponse().setBody("ok"))
        fetcher().get(server.url("/works/1"))
        val req = server.takeRequest()
        assertNull(req.getHeader("Accept"))
        assertNull(req.getHeader("X-Requested-With"))
    }

    @Test fun `404 maps to NotFound`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(FetchResult.NotFound, fetcher().get(server.url("/x")))
    }

    @Test fun `plain 429 waits retry-after then retries once`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7"))
        server.enqueue(MockResponse().setBody("ok"))
        val r = fetcher().get(server.url("/x"))
        assertTrue(r is FetchResult.Ok)
        assertTrue(sleeps.contains(7000L))
        assertEquals(2, server.requestCount)
    }

    @Test fun `two plain 429s returns RateLimited`() = runTest {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "5")) }
        assertEquals(FetchResult.RateLimited(5), fetcher().get(server.url("/x")))
        assertEquals(2, server.requestCount)
    }

    @Test fun `missing retry-after defaults to 60s and returns at once`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        assertEquals(FetchResult.RateLimited(60), fetcher().get(server.url("/x")))
        assertFalse(sleeps.contains(60_000L))
        assertEquals(1, server.requestCount)
    }

    @Test fun `retry-after over 30s returns RateLimited without sleeping`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "31"))
        assertEquals(FetchResult.RateLimited(31), fetcher().get(server.url("/x")))
        assertTrue(sleeps.none { it >= 31_000L })
        assertEquals(1, server.requestCount)
    }

    @Test fun `retry-after of exactly 30s still waits and retries`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))
        server.enqueue(MockResponse().setBody("ok"))
        assertTrue(fetcher().get(server.url("/x")) is FetchResult.Ok)
        assertTrue(sleeps.contains(30_000L))
    }

    @Test fun `challenge triggers solver then retries once`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody(robot))
        server.enqueue(MockResponse().setBody("fine"))
        val url = server.url("/x")
        assertTrue(fetcher().get(url) is FetchResult.Ok)
        assertEquals(listOf(url), solveCalls)
    }

    @Test fun `retry after a solve is spaced from the end of the solve`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody(robot))
        server.enqueue(MockResponse().setBody("fine"))
        val f = Ao3Fetcher(
            client = OkHttpClient(),
            userAgent = "TestAgent/1.0",
            solver = { now += 300; true },   // the solver's own page load took 300 ms
            minIntervalMs = 1000,
            clock = { now },
            sleep = { sleeps += it; now += it },
        )
        assertTrue(f.get(server.url("/x")) is FetchResult.Ok)
        assertEquals(listOf(1000L), sleeps)
    }

    @Test fun `challenge after solve returns Blocked`() = runTest {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(503).setBody(robot)) }
        assertEquals(FetchResult.Blocked, fetcher().get(server.url("/x")))
        assertEquals(1, solveCalls.size)
        assertEquals(2, server.requestCount)
    }

    @Test fun `non-interactive challenge returns Blocked without calling the solver`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody(robot))
        assertEquals(FetchResult.Blocked, fetcher().get(server.url("/x"), interactive = false))
        assertTrue(solveCalls.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test fun `non-interactive ok fetch is unaffected`() = runTest {
        server.enqueue(MockResponse().setBody("fine"))
        assertTrue(fetcher().get(server.url("/x"), interactive = false) is FetchResult.Ok)
    }

    @Test fun `solver returning false returns Blocked without retry`() = runTest {
        solveResult = false
        server.enqueue(MockResponse().setResponseCode(403).setBody(robot))
        assertEquals(FetchResult.Blocked, fetcher().get(server.url("/x")))
        assertEquals(1, server.requestCount)
    }

    @Test fun `requests are serialized and spaced`() = runTest {
        repeat(2) { server.enqueue(MockResponse().setBody("ok")) }
        val f = fetcher()
        val results = listOf(
            async { f.get(server.url("/a")) },
            async { f.get(server.url("/b")) },
        ).awaitAll()
        assertTrue(results.all { it is FetchResult.Ok })
        assertEquals(2, server.requestCount)
        assertTrue(sleeps.any { it in 1000L..1000L })
    }

    @Test fun `second request is not sent while the first is in flight`() = runTest {
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/a") release.await(5, TimeUnit.SECONDS)
                return MockResponse().setBody("ok")
            }
        }
        val f = fetcher()
        val a = async { f.get(server.url("/a")) }
        val b = async { f.get(server.url("/b")) }
        val first = withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
        assertEquals("/a", first?.path)
        // /a is still blocked on the server: /b must wait for it.
        val early = withContext(Dispatchers.IO) { server.takeRequest(500, TimeUnit.MILLISECONDS) }
        assertNull(early)
        release.countDown()
        assertTrue(a.await() is FetchResult.Ok)
        assertTrue(b.await() is FetchResult.Ok)
        assertEquals(2, server.requestCount)
    }

    @Test fun `cancelling a get cancels the in-flight call`() = runTest {
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                release.await(10, TimeUnit.SECONDS)
                return MockResponse().setBody("ok")
            }
        }
        val job = launch { fetcher().get(server.url("/slow")) }
        withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
        job.cancel()
        val finished = withContext(Dispatchers.Default) { withTimeoutOrNull(2_000) { job.join(); true } }
        release.countDown()
        assertEquals(true, finished)
    }

    @Test fun `io error returns Failed`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertTrue(fetcher().get(server.url("/x")) is FetchResult.Failed)
    }

    @Test fun `5xx returns Failed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(fetcher().get(server.url("/x")) is FetchResult.Failed)
    }

    @Test fun `non-interactive 429 returns RateLimited immediately`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "5"))
        server.enqueue(MockResponse().setBody("ok"))
        assertEquals(FetchResult.RateLimited(5), fetcher().get(server.url("/x"), interactive = false))
        assertEquals(1, server.requestCount)
        assertTrue(sleeps.isEmpty())
    }

    @Test fun `anonymous get tags the request`() = runTest {
        server.enqueue(MockResponse().setBody("ok"))
        server.enqueue(MockResponse().setBody("ok"))
        val tags = mutableListOf<Anonymous?>()
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor { chain -> tags += chain.request().tag(Anonymous::class.java); chain.proceed(chain.request()) }
            .build()
        val f = Ao3Fetcher(client, "TestAgent/1.0", { true }, clock = { now }, sleep = { now += it })
        f.get(server.url("/a"), anonymous = true)
        f.get(server.url("/b"))
        assertEquals(listOf(Anonymous, null), tags)
        assertEquals("no-cache, no-store", server.takeRequest().getHeader("Cache-Control"))
        assertNull(server.takeRequest().getHeader("Cache-Control"))
    }

    @Test fun `forceNetwork get bypasses the HTTP cache, a plain get may be served from it`() = runTest {
        repeat(3) { server.enqueue(MockResponse().setHeader("Cache-Control", "max-age=600").setBody("ok$it")) }
        val dir = java.nio.file.Files.createTempDirectory("ao3-http-cache").toFile()
        val client = OkHttpClient.Builder().cache(okhttp3.Cache(dir, 1L shl 20)).build()
        val f = Ao3Fetcher(client, "TestAgent/1.0", { true }, clock = { now }, sleep = { now += it })
        assertEquals("ok0", (f.get(server.url("/c")) as FetchResult.Ok).html)
        assertEquals("ok0", (f.get(server.url("/c")) as FetchResult.Ok).html)   // from the cache
        assertEquals(1, server.requestCount)
        assertEquals("ok1", (f.get(server.url("/c"), forceNetwork = true) as FetchResult.Ok).html)
        assertEquals(2, server.requestCount)
        server.takeRequest()
        assertEquals("no-cache", server.takeRequest().getHeader("Cache-Control"))
        dir.deleteRecursively()
    }

    @Test fun `post sends form fields with user agent`() = runTest {
        server.enqueue(MockResponse().setBody("<html>done</html>"))
        val r = fetcher().post(
            server.url("/users/login"),
            listOf("authenticity_token" to "a+b/c=", "user[login]" to "me", "user[password]" to "p w&d"),
        )
        assertEquals("<html>done</html>", (r as FetchResult.Ok).html)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("TestAgent/1.0", req.getHeader("User-Agent"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
        assertEquals(
            "authenticity_token=a%2Bb%2Fc%3D&user%5Blogin%5D=me&user%5Bpassword%5D=p+w%26d",
            req.body.readUtf8(),
        )
        assertNull(req.getHeader("X-Requested-With"))
    }

    @Test fun `post with accept sends accept and xhr headers`() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        fetcher().post(server.url("/kudos"), listOf("a" to "b"), accept = "application/json")
        val req = server.takeRequest()
        assertEquals("application/json", req.getHeader("Accept"))
        assertEquals("XMLHttpRequest", req.getHeader("X-Requested-With"))
    }

    @Test fun `post on challenge solves once and returns Blocked without resubmitting`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody(robot))
        server.enqueue(MockResponse().setBody("fine"))
        val url = server.url("/works/1/kudos")
        assertEquals(FetchResult.Blocked, fetcher().post(url, listOf("a" to "b")))
        assertEquals(listOf(url), solveCalls)
        assertEquals(1, server.requestCount)
    }

    @Test fun `non-interactive post on challenge returns Blocked without the solver`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody(robot))
        assertEquals(FetchResult.Blocked, fetcher().post(server.url("/users/login"), listOf("a" to "b"), interactive = false))
        assertTrue(solveCalls.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test fun `non-interactive post 429 returns RateLimited at once`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2"))
        assertEquals(FetchResult.RateLimited(2), fetcher().post(server.url("/x"), listOf("a" to "b"), interactive = false))
        assertTrue(sleeps.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test fun `post 429 follows the get rule`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "31"))
        assertEquals(FetchResult.RateLimited(31), fetcher().post(server.url("/x"), listOf("a" to "b")))
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2"))
        server.enqueue(MockResponse().setBody("ok"))
        assertTrue(fetcher().post(server.url("/x"), listOf("a" to "b")) is FetchResult.Ok)
        assertTrue(sleeps.contains(2000L))
        assertEquals(3, server.requestCount)
    }

    @Test fun `post shares the single mutex and spacing with get`() = runTest {
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/a") release.await(5, TimeUnit.SECONDS)
                return MockResponse().setBody("ok")
            }
        }
        val f = fetcher()
        val a = async { f.get(server.url("/a")) }
        val b = async { f.post(server.url("/b"), listOf("x" to "y")) }
        val first = withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
        assertEquals("/a", first?.path)
        val early = withContext(Dispatchers.IO) { server.takeRequest(500, TimeUnit.MILLISECONDS) }
        assertNull(early)
        release.countDown()
        assertTrue(a.await() is FetchResult.Ok)
        assertTrue(b.await() is FetchResult.Ok)
        assertEquals(listOf(1000L), sleeps)
        assertEquals("POST", withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }?.method)
    }

    @Test fun `isChallenge rules`() {
        val none = Headers.headersOf()
        assertFalse(isChallenge(429, none, "slow down"))
        assertTrue(isChallenge(403, Headers.headersOf("cf-mitigated", "challenge"), ""))
        assertTrue(isChallenge(503, Headers.headersOf("Set-Cookie", "__cfwaitingroom=abc; path=/"), ""))
        assertTrue(isChallenge(403, none, "<title>Just a moment...</title>"))
        assertTrue(isChallenge(503, none, "x cf-chl y"))
        assertTrue(isChallenge(200, none, robot))
        assertFalse(isChallenge(200, none, "Just a moment..."))
        assertFalse(isChallenge(200, Headers.headersOf("cf-mitigated", "challenge"), ""))
        assertTrue(isChallenge(200, none, "<p>Our Systems Think You\n may be a robot.</p>"))
    }

    @Test fun `robot sentence inside work text is not a challenge`() {
        val none = Headers.headersOf()
        val chapter = """<html><body><div id="main"><div id="workskin"><div class="userstuff">
            <p>"Our systems think you may be a robot," the android read aloud.</p></div></div></div></body></html>"""
        assertFalse(isChallenge(200, none, chapter))
        val summary = """<li class="blurb"><blockquote class="userstuff summary">our systems think you may be a robot</blockquote></li>"""
        assertFalse(isChallenge(200, none, summary))
        assertFalse(isChallenge(200, none, "<p>you may be a robot</p>"))
    }

    @Test fun `real AO3 pages are never challenges`() {
        val none = Headers.headersOf()
        for (name in listOf(
            "search_results_real.html", "search_results_last_real.html", "chapter_first_real.html",
            "chapter_later_real.html", "work_single_real.html", "navigate_real.html", "navigate_single_real.html",
        )) {
            val html = javaClass.getResource("/fixtures/$name")!!.readText()
            assertFalse(name, isRobotPage(html))
            assertFalse(name, isChallenge(200, none, html))
        }
    }

    @Test fun `post is never resent at the transport level after a dropped connection`() = runTest {
        // A pooled connection that the server drops once the request arrives: OkHttp's default would resend.
        server.enqueue(MockResponse().setBody("<html>prime</html>"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody("<html>second copy</html>"))
        val f = fetcher()
        assertTrue(f.get(server.url("/works/1")) is FetchResult.Ok)
        val r = f.post(server.url("/kudos"), listOf("a" to "1"))
        assertTrue(r.toString(), r is FetchResult.Failed)
        assertEquals(2, server.requestCount)
    }

    @Test fun `get keeps the client's transport retry`() = runTest {
        server.enqueue(MockResponse().setBody("<html>prime</html>"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody("<html>ok</html>"))
        val f = fetcher()
        assertTrue(f.get(server.url("/works/1")) is FetchResult.Ok)
        assertTrue(f.get(server.url("/works/2")) is FetchResult.Ok)
        assertEquals(3, server.requestCount)
    }

    @Test fun `unexpected status is a typed HttpStatusException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(422).setBody("nope"))
        val r = fetcher().post(server.url("/kudos"), emptyList())
        assertEquals(422, ((r as FetchResult.Failed).cause as HttpStatusException).code)
    }

    @Test fun `posts have a 60 s call timeout and no transport retry, gets keep the client's settings`() {
        val client = OkHttpClient()
        val f = Ao3Fetcher(client, "UA", { false })
        assertEquals(60_000, f.postClient.callTimeoutMillis)
        assertFalse(f.postClient.retryOnConnectionFailure)
        assertEquals(0, client.callTimeoutMillis)
        assertTrue(client.retryOnConnectionFailure)
    }
}
