package app.ao3reader.ao3.parse

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinksSeriesTest {
    private fun series(href: String) =
        Links.series(Jsoup.parse("<a href=\"$href\">S</a>", Ao3Urls.BASE_URI).selectFirst("a")!!, 1)

    @Test fun `digits-only ids are accepted`() {
        assertEquals(5L, series("/series/5")?.id)
    }

    @Test fun `signed or non-digit ids are rejected`() {
        assertNull(series("/series/+5"))
        assertNull(series("/series/-5"))
        assertNull(series("/series/5x"))
    }
}
