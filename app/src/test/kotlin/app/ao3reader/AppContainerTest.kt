package app.ao3reader

import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AppContainerTest {
    private val ctx = RuntimeEnvironment.getApplication()

    @Test fun `evicting the HTTP cache never builds the client`() = runTest {
        val c = AppContainer(ctx)
        assertNull(c.okHttpClientIfBuilt())
        c.evictHttpCache()
        assertNull(c.okHttpClientIfBuilt())
    }

    @Test fun `evicting the HTTP cache empties the built client's cache`() = runTest {
        val c = AppContainer(ctx)
        val client = c.okHttpClient
        assertSame(client, c.okHttpClientIfBuilt())
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("hello").setHeader("Cache-Control", "max-age=600"))
            client.newCall(Request.Builder().url(server.url("/page")).build()).execute().use { it.body!!.string() }
            assertTrue(client.cache!!.urls().hasNext())
            c.evictHttpCache()
            assertFalse(client.cache!!.urls().hasNext())
            assertEquals(1, server.requestCount)
        }
    }
}
