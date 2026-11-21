package app.ao3reader.net

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PersistentCookieJarTest {
    private val manager: CookieManager get() = CookieManager.getInstance()
    private val url = "https://archiveofourown.org/works/1".toHttpUrl()

    @Before
    fun clear() {
        manager.removeAllCookies(null)
    }

    @Test
    fun `saved cookies are returned for matching url`() {
        val jar = PersistentCookieJar(manager)
        jar.saveFromResponse(url, listOf(Cookie.Builder().name("cf_clearance").value("abc").domain("archiveofourown.org").path("/").build()))
        val loaded = jar.loadForRequest(url)
        assertEquals(listOf("cf_clearance"), loaded.map { it.name })
        assertEquals("abc", loaded.single().value)
    }

    @Test
    fun `cookies not returned for other host`() {
        val jar = PersistentCookieJar(manager)
        jar.saveFromResponse(url, listOf(Cookie.Builder().name("a").value("b").domain("archiveofourown.org").path("/").build()))
        assertTrue(jar.loadForRequest("https://example.com/".toHttpUrl()).isEmpty())
    }

    @Test
    fun `cookie set via CookieManager directly is visible to OkHttp`() {
        manager.setCookie("https://archiveofourown.org/", "_otwarchive_session=xyz; Path=/")
        val loaded = PersistentCookieJar(manager).loadForRequest(url)
        assertEquals("xyz", loaded.single { it.name == "_otwarchive_session" }.value)
    }
}
