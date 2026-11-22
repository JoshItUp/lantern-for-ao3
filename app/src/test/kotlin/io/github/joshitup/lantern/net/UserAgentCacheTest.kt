package io.github.joshitup.lantern.net

import android.content.Context
import org.robolectric.RuntimeEnvironment
import io.github.joshitup.lantern.ao3.net.FetchResult
import io.github.joshitup.lantern.ao3.net.Fetcher
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UserAgentCacheTest {
    private val prefs get() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("ua-test", Context.MODE_PRIVATE)
    private var computed = 0

    @Before fun clear() { prefs.edit().clear().commit() }

    private fun compute(ua: String): () -> String = { computed++; ua }

    @Test fun `first call computes and stores`() {
        assertEquals("UA/1", cachedUserAgent(prefs, "120.0", compute("UA/1")))
        assertEquals(1, computed)
    }

    @Test fun `same webview version reuses the stored value`() {
        cachedUserAgent(prefs, "120.0", compute("UA/1"))
        assertEquals("UA/1", cachedUserAgent(prefs, "120.0", compute("UA/2")))
        assertEquals(1, computed)
    }

    @Test fun `new webview version recomputes`() {
        cachedUserAgent(prefs, "120.0", compute("UA/1"))
        assertEquals("UA/2", cachedUserAgent(prefs, "121.0", compute("UA/2")))
        assertEquals(2, computed)
        assertEquals("UA/2", cachedUserAgent(prefs, "121.0", compute("UA/3")))
        assertEquals(2, computed)
    }

    @Test fun `unknown webview version always computes`() {
        cachedUserAgent(prefs, null, compute("UA/1"))
        assertEquals("UA/2", cachedUserAgent(prefs, null, compute("UA/2")))
        assertEquals(2, computed)
    }

    @Test fun `deferred fetcher creates its delegate on the first request only`() = runTest {
        var created = 0
        val interactives = mutableListOf<Boolean>()
        val anonymouses = mutableListOf<Boolean>()
        val posted = mutableListOf<List<Pair<String, String>>>()
        val delegate = object : Fetcher {
            override suspend fun get(url: HttpUrl, accept: String?, interactive: Boolean, anonymous: Boolean, forceNetwork: Boolean): FetchResult {
                interactives += interactive
                anonymouses += anonymous
                return FetchResult.NotFound
            }
            override suspend fun post(url: HttpUrl, fields: List<Pair<String, String>>, accept: String?, interactive: Boolean): FetchResult {
                posted += fields
                return FetchResult.NotFound
            }
        }
        val f = DeferredFetcher { created++; delegate }
        assertEquals(0, created)
        f.get("https://archiveofourown.org/".toHttpUrl())
        f.get("https://archiveofourown.org/".toHttpUrl(), interactive = false)
        assertEquals(1, created)
        assertEquals(listOf(true, false), interactives)
        f.get("https://archiveofourown.org/".toHttpUrl(), anonymous = true)
        f.post("https://archiveofourown.org/users/login".toHttpUrl(), listOf("a" to "b"))
        assertEquals(1, created)
        assertEquals(listOf(false, false, true), anonymouses)
        assertEquals(listOf(listOf("a" to "b")), posted)
    }
}
