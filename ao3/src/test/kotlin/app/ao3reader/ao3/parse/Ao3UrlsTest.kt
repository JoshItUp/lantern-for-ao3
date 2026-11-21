package app.ao3reader.ao3.parse

import org.junit.Assert.*
import org.junit.Test

class Ao3UrlsTest {
    @Test fun `chapter urls`() {
        assertEquals("https://archiveofourown.org/works/5?view_adult=true", Ao3Urls.chapter(5, null).toString())
        assertEquals("https://archiveofourown.org/works/5/chapters/9?view_adult=true", Ao3Urls.chapter(5, 9).toString())
    }

    @Test fun `login and logout urls`() {
        assertEquals("https://archiveofourown.org/users/login", Ao3Urls.login().toString())
        assertEquals("https://archiveofourown.org/users/logout", Ao3Urls.logout().toString())
    }

    @Test fun `navigate url`() {
        assertEquals("https://archiveofourown.org/works/5/navigate", Ao3Urls.navigate(5).toString())
    }

    @Test fun `autocomplete encodes term`() {
        val u = Ao3Urls.autocomplete("fandom", "Salt & Pepper")
        assertEquals("/autocomplete/fandom", u.encodedPath)
        assertEquals("Salt & Pepper", u.queryParameter("term"))
        assertFalse(u.toString().contains("& P"))
    }

    @Test fun `full work url`() {
        assertEquals("https://archiveofourown.org/works/42?view_full_work=true", Ao3Urls.fullWork(42).toString())
    }
}
