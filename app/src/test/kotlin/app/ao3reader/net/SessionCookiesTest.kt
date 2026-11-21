package app.ao3reader.net

import android.webkit.CookieManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionCookiesTest {
    @Test fun `expires only AO3 login cookies`() {
        val cm = CookieManager.getInstance()
        val url = "https://archiveofourown.org"
        cm.setCookie(url, "_otwarchive_session=abc; Path=/")
        cm.setCookie(url, "remember_user_token=def; Path=/")
        cm.setCookie(url, "cf_clearance=keep; Path=/")

        clearAo3SessionCookies(cm)

        val left = cm.getCookie(url).orEmpty()
        assertFalse(left, left.contains("_otwarchive_session=abc"))
        assertFalse(left, left.contains("remember_user_token=def"))
        assertTrue(left, left.contains("cf_clearance=keep"))
    }
}
