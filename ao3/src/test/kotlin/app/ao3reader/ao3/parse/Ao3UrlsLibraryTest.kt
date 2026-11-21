package app.ao3reader.ao3.parse

import org.junit.Assert.assertEquals
import org.junit.Test

class Ao3UrlsLibraryTest {
    @Test fun `library urls`() {
        assertEquals("https://archiveofourown.org/users/me/readings", Ao3Urls.readings("me", 1, false).toString())
        assertEquals("https://archiveofourown.org/users/me/readings?show=to-read&page=3", Ao3Urls.readings("me", 3, true).toString())
        assertEquals("https://archiveofourown.org/users/me/bookmarks?page=2", Ao3Urls.bookmarks("me", 2).toString())
        assertEquals("https://archiveofourown.org/users/me/subscriptions", Ao3Urls.subscriptions("me", 1).toString())
    }

    @Test fun `username is path-segment encoded`() {
        assertEquals("/users/a%2Fb%20c/bookmarks", Ao3Urls.bookmarks("a/b c", 1).encodedPath)
    }
}
